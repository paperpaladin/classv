package classv;

import java.util.ArrayList;
import java.util.List;

/**
 * The StackMapTable attribute, JVMS 4.7.4.
 *
 * Two things come out of this: the raw entries exactly as the file spells
 * them, which is what the javap comparison is made against, and the expanded
 * absolute frames, which is what the type checker consumes.  Keeping both is
 * deliberate -- the expansion is where the off-by-one lives (the implicit +1
 * on every delta after the first), and comparing only the expanded form
 * against javap would have nothing to compare, since javap prints the deltas.
 */
public final class StackMap {

    /** One entry, holding only the fields its frame type actually spells out. */
    public static final class Entry {
        public int frameType;
        public boolean hasDelta;
        public int delta;
        public VType[] locals;   // null when the frame type does not carry any
        public VType[] stack;    // null when the frame type does not carry any
        /** Absolute bci, after the implicit +1 on every delta but the first. */
        public int offset;

        public String kindName() {
            if (frameType <= 63) return "same";
            if (frameType <= 127) return "same_locals_1_stack_item";
            if (frameType <= 246) return "reserved";
            if (frameType == 247) return "same_locals_1_stack_item_extended";
            if (frameType <= 250) return "chop";
            if (frameType == 251) return "same_frame_extended";
            if (frameType <= 254) return "append";
            return "full_frame";
        }
    }

    /** An expanded frame: the complete locals and stack at one bci. */
    public static final class Frame {
        public int offset;
        public VType[] locals;   // one entry per slot; TOP follows a two word type
        public int localsSize;   // slots in use
        public VType[] stack;
        public int stackSize;
        public boolean flagThisUninit;
    }

    public final List<Entry> entries = new ArrayList<>();

    public static StackMap parse(byte[] data, ConstantPool p) throws ClassFormatException {
        StackMap sm = new StackMap();
        Buf b = new Buf(data);
        int n = b.u2();
        int prev = -1;
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.frameType = b.u1();
            int ft = e.frameType;
            if (ft <= 63) {
                e.delta = ft;
            } else if (ft <= 127) {
                e.delta = ft - 64;
                e.stack = new VType[] { readType(b, p) };
            } else if (ft <= 246) {
                throw new ClassFormatException(b.pos() - 1,
                        "stack map frame type " + ft + " is reserved (128..246)");
            } else if (ft == 247) {
                e.hasDelta = true;
                e.delta = b.u2();
                e.stack = new VType[] { readType(b, p) };
            } else if (ft <= 251) {
                e.hasDelta = true;
                e.delta = b.u2();
            } else if (ft <= 254) {
                e.hasDelta = true;
                e.delta = b.u2();
                int k = ft - 251;
                e.locals = new VType[k];
                for (int j = 0; j < k; j++) e.locals[j] = readType(b, p);
            } else {
                e.hasDelta = true;
                e.delta = b.u2();
                int nl = b.u2();
                e.locals = new VType[nl];
                for (int j = 0; j < nl; j++) e.locals[j] = readType(b, p);
                int ns = b.u2();
                e.stack = new VType[ns];
                for (int j = 0; j < ns; j++) e.stack[j] = readType(b, p);
            }
            e.offset = prev < 0 ? e.delta : prev + e.delta + 1;
            prev = e.offset;
            sm.entries.add(e);
        }
        if (b.remaining() != 0)
            throw new ClassFormatException(b.pos(), "StackMapTable has " + b.remaining()
                    + " bytes after its last frame");
        return sm;
    }

    private static VType readType(Buf b, ConstantPool p) throws ClassFormatException {
        int tag = b.u1();
        switch (tag) {
            case 0: return VType.TOP;
            case 1: return VType.INTEGER;
            case 2: return VType.FLOAT;
            case 3: return VType.DOUBLE;
            case 4: return VType.LONG;
            case 5: return VType.NULL;
            case 6: return VType.UNINIT_THIS;
            case 7: return VType.fromClassName(p.className(b.u2()));
            case 8: return VType.uninit(b.u2());
            default:
                throw new ClassFormatException(b.pos() - 1,
                        "verification_type_info tag " + tag + " is not one of 0..8");
        }
    }

    /**
     * Expand the deltas into absolute frames, starting from the method entry
     * frame.  Appending and chopping count verification types, not slots, so
     * chopping one long takes two slots off; getting that wrong shifts every
     * local in every later frame of the method and is invisible until some
     * instruction happens to read the shifted slot.
     */
    public List<Frame> expand(Frame entry, int maxLocals) throws ClassFormatException {
        List<Frame> out = new ArrayList<>();
        VType[] locals = entry.locals.clone();
        int nl = entry.localsSize;
        for (Entry e : entries) {
            Frame f = new Frame();
            f.offset = e.offset;
            int ft = e.frameType;
            if (ft <= 63 || ft == 251) {
                // same
            } else if (ft <= 127 || ft == 247) {
                // same_locals_1_stack_item: locals unchanged
            } else if (ft <= 250) {
                int k = 251 - ft;
                for (int j = 0; j < k; j++) {
                    if (nl <= 0)
                        throw new ClassFormatException(0, "chop frame at bci " + e.offset
                                + " removes more locals than the frame has");
                    nl--;
                    if (nl > 0 && locals[nl] == VType.TOP && locals[nl - 1] != null
                            && locals[nl - 1].isTwoWord())
                        nl--;
                }
            } else if (ft <= 254) {
                for (VType t : e.locals) {
                    if (nl >= maxLocals)
                        throw new ClassFormatException(0, "append frame at bci " + e.offset
                                + " needs local slot " + nl + " but max_locals is " + maxLocals);
                    locals[nl++] = t;
                    if (t.isTwoWord()) {
                        if (nl >= maxLocals)
                            throw new ClassFormatException(0, "append frame at bci " + e.offset
                                    + " needs local slot " + nl + " for the second half of a "
                                    + t + " but max_locals is " + maxLocals);
                        locals[nl++] = VType.TOP;
                    }
                }
            } else {
                nl = 0;
                for (VType t : e.locals) {
                    if (nl >= maxLocals)
                        throw new ClassFormatException(0, "full frame at bci " + e.offset
                                + " needs local slot " + nl + " but max_locals is " + maxLocals);
                    locals[nl++] = t;
                    if (t.isTwoWord()) {
                        if (nl >= maxLocals)
                            throw new ClassFormatException(0, "full frame at bci " + e.offset
                                    + " needs local slot " + nl + " for the second half of a "
                                    + t + " but max_locals is " + maxLocals);
                        locals[nl++] = VType.TOP;
                    }
                }
            }
            f.locals = new VType[maxLocals];
            System.arraycopy(locals, 0, f.locals, 0, nl);
            for (int j = nl; j < maxLocals; j++) f.locals[j] = VType.TOP;
            f.localsSize = nl;
            if (ft <= 63 || ft == 251) {
                f.stack = new VType[0];
                f.stackSize = 0;
            } else if (ft <= 127 || ft == 247) {
                f.stack = expandStack(e.stack);
                f.stackSize = f.stack.length;
            } else if (ft <= 254) {
                f.stack = new VType[0];
                f.stackSize = 0;
            } else {
                f.stack = expandStack(e.stack);
                f.stackSize = f.stack.length;
            }
            out.add(f);
        }
        return out;
    }

    private static VType[] expandStack(VType[] s) {
        int n = 0;
        for (VType t : s) n += t.isTwoWord() ? 2 : 1;
        VType[] out = new VType[n];
        int k = 0;
        for (VType t : s) {
            out[k++] = t;
            if (t.isTwoWord()) out[k++] = VType.TOP;
        }
        return out;
    }
}
