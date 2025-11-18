package classv;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The type checking verifier, JVMS 4.10.1.
 *
 * This is the algorithm the JVM has used since Java 6 and has required since
 * Java 7: the StackMapTable says what the frame is at every branch target,
 * and the checker walks the code once, applying each instruction's effect on
 * the operand stack and the local variable array and asserting that the frame
 * it arrives with is assignable to the frame the table declares.
 *
 * The rule names in the comments are the spec's own Prolog predicate names,
 * so the two can be read side by side.  Where this file departs from the
 * spec it says so and says why.
 */
public final class Verifier {

    public enum Kind { OK, VERIFY, FORMAT, REFUSE }

    public static final class Result {
        public final Kind kind;
        public final String message;
        Result(Kind k, String m) { kind = k; message = m; }
    }

    /** A type error: the class would be rejected with a VerifyError. */
    static final class Fail extends Exception {
        private static final long serialVersionUID = 1L;
        final int bci;
        Fail(int bci, String msg) { super(msg); this.bci = bci; }
    }

    private final ClassFile cf;
    private final Hierarchy h;
    public boolean dumpFrames;
    public PrintStream out = System.out;

    /** Counters, so a harness can assert the new path actually ran. */
    public int methodsChecked, instructionsChecked, framesChecked, handlerChecks;

    public Verifier(ClassFile cf, Hierarchy h) {
        this.cf = cf;
        this.h = h;
        h.declare(cf);
    }

    // ------------------------------------------------------------- the frame

    private static final class Frame {
        VType[] locals;
        VType[] stack;
        int sp;
        boolean thisUninit;

        Frame copy() {
            Frame f = new Frame();
            f.locals = locals.clone();
            f.stack = stack.clone();
            f.sp = sp;
            f.thisUninit = thisUninit;
            return f;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder("locals[");
            for (int i = 0; i < locals.length; i++)
                b.append(i == 0 ? "" : ", ").append(locals[i]);
            b.append("] stack[");
            for (int i = 0; i < sp; i++) b.append(i == 0 ? "" : ", ").append(stack[i]);
            b.append(']');
            if (thisUninit) b.append(" flagThisUninit");
            return b.toString();
        }
    }

    // ------------------------------------------------------------- the entry

    public Result verify() {
        try {
            Result structural = structure();
            if (structural != null) return structural;
            if ((cf.accessFlags & ClassFile.ACC_MODULE) != 0)
                return new Result(Kind.REFUSE, "this is a module descriptor, not a class");
            if (cf.major < 50)
                return new Result(Kind.REFUSE, "class file version " + cf.major + "."
                        + cf.minor + " predates the StackMapTable attribute, so the JVM"
                        + " type-infers it (JVMS 4.10.2); that path is not implemented");
            if (cf.major == 50)
                return new Result(Kind.REFUSE, "class file version 50 is the one version"
                        + " where the JVM retries with the type inference verifier if type"
                        + " checking fails, so a rejection here would not predict the JVM's"
                        + " answer");
            for (ClassFile.Member m : cf.methods) {
                if (m.code == null) {
                    if ((m.flags & (ClassFile.ACC_NATIVE | ClassFile.ACC_ABSTRACT)) == 0)
                        return new Result(Kind.FORMAT, "method " + m.name + m.desc
                                + " is neither native nor abstract and has no Code attribute");
                    continue;
                }
                if ((m.flags & (ClassFile.ACC_NATIVE | ClassFile.ACC_ABSTRACT)) != 0)
                    return new Result(Kind.FORMAT, "method " + m.name + m.desc
                            + " is native or abstract and has a Code attribute");
                try {
                    method(m);
                } catch (Fail e) {
                    return new Result(Kind.VERIFY, cf.thisClass + "." + m.name + m.desc
                            + " @" + e.bci + ": " + e.getMessage());
                }
                methodsChecked++;
            }
            return new Result(Kind.OK, null);
        } catch (ClassFormatException e) {
            return new Result(Kind.FORMAT, e.getMessage());
        } catch (Refusal e) {
            return new Result(Kind.REFUSE, e.getMessage());
        }
    }

    /**
     * The load time structural rules, JVMS 4.1 and 5.3.5, that the JVM applies
     * before the verifier runs.  Only the ones a mutation sweep has shown to
     * be reachable are here, each with the JVM diagnosis it was added for;
     * this is not a complete class file validator and the README says so.
     */
    private Result structure() throws ClassFormatException {
        // The version the running JVM supports, not a constant: the oracle is
        // this JVM, and a class file one release too new is rejected by it
        // and would be accepted by the next one.
        int maxMajor = Runtime.version().feature() + 44;
        if (cf.major > maxMajor || (cf.major == maxMajor && cf.minor != 0
                && cf.minor != 65535))
            return new Result(Kind.FORMAT, "class file version " + cf.major + "."
                    + cf.minor + " is newer than the " + maxMajor
                    + ".0 this JVM supports (UnsupportedClassVersionError)");
        if (cf.major < 45)
            return new Result(Kind.FORMAT, "class file version " + cf.major
                    + " predates the first one, 45");
        if (cf.superClassIndex != 0 && cf.superClass.equals(cf.thisClass))
            return new Result(Kind.FORMAT, cf.thisClass + " is its own superclass"
                    + " (ClassCircularityError)");
        if (cf.thisClass.charAt(0) == '[')
            return new Result(Kind.FORMAT, "this_class names the array type "
                    + cf.thisClass);
        if (cf.isInterface()) {
            if (cf.superClassIndex == 0 || !cf.superClass.equals("java/lang/Object"))
                return new Result(Kind.FORMAT, "an interface must have java/lang/Object"
                        + " as its superclass, not "
                        + (cf.superClassIndex == 0 ? "none" : cf.superClass));
            if ((cf.accessFlags & ClassFile.ACC_FINAL) != 0)
                return new Result(Kind.FORMAT, "an interface cannot be final");
        } else if (cf.superClassIndex == 0 && !cf.thisClass.equals("java/lang/Object")) {
            return new Result(Kind.FORMAT, cf.thisClass + " has no superclass and is not"
                    + " java/lang/Object");
        }
        if ((cf.accessFlags & ClassFile.ACC_FINAL) != 0
                && (cf.accessFlags & ClassFile.ACC_ABSTRACT) != 0)
            return new Result(Kind.FORMAT, "a class cannot be both final and abstract");
        return null;
    }

    // ------------------------------------------------------------ one method

    private List<Op.Insn> insns;
    private Map<Integer, Integer> indexOf;     // bci -> index in insns
    private Map<Integer, Frame> mapFrames;     // bci -> declared frame
    private ClassFile.CodeAttr code;
    private VType returnType;                  // null for void
    private int maxStack, maxLocals;
    private ClassFile.Member method;

    private void method(ClassFile.Member m)
            throws Fail, Refusal, ClassFormatException {
        method = m;
        code = m.code;
        maxStack = code.maxStack;
        maxLocals = code.maxLocals;
        if (code.code.length == 0)
            throw new Fail(0, "the Code attribute has a zero length code array");
        insns = Op.decode(code.code);
        indexOf = new HashMap<>();
        for (int i = 0; i < insns.size(); i++) indexOf.put(insns.get(i).pc, i);

        String ret = ClassFile.descReturn(m.desc);
        returnType = ret.equals("V") ? null : VType.fromDescriptor(ret);

        Frame entry = entryFrame(m);
        readStackMap(entry, entryLocalsSize);
        walk(entry);
    }

    private int entryLocalsSize;

    private Frame entryFrame(ClassFile.Member m) throws Fail, ClassFormatException {
        Frame f = new Frame();
        f.locals = new VType[maxLocals];
        f.stack = new VType[maxStack + 2];
        f.sp = 0;
        java.util.Arrays.fill(f.locals, VType.TOP);
        int n = 0;
        if ((m.flags & ClassFile.ACC_STATIC) == 0) {
            VType self;
            if (m.name.equals("<init>")) {
                if (cf.thisClass.equals("java/lang/Object")) {
                    self = VType.object(cf.thisClass);
                } else {
                    self = VType.UNINIT_THIS;
                    f.thisUninit = true;
                }
            } else {
                self = VType.object(cf.thisClass);
            }
            n = put(f, n, self);
        }
        for (VType t : argTypes(m.desc)) n = put(f, n, t);
        entryLocalsSize = n;
        return f;
    }

    private int put(Frame f, int n, VType t) throws Fail {
        if (n >= maxLocals)
            throw new Fail(0, "the arguments of " + method.name + method.desc
                    + " need local slot " + n + " but max_locals is " + maxLocals);
        f.locals[n++] = t;
        if (t.isTwoWord()) {
            if (n >= maxLocals)
                throw new Fail(0, "the arguments of " + method.name + method.desc
                        + " need local slot " + n + " for the second half of a " + t
                        + " but max_locals is " + maxLocals);
            f.locals[n++] = VType.TOP;
        }
        return n;
    }

    static List<VType> argTypes(String desc) throws ClassFormatException {
        if (desc.isEmpty() || desc.charAt(0) != '(')
            throw new ClassFormatException(0, "method descriptor does not start with '(': "
                    + desc);
        List<VType> out = new ArrayList<>();
        int i = 1;
        int[] end = new int[1];
        while (i < desc.length() && desc.charAt(i) != ')') {
            VType t = VType.fromDescriptor(desc.substring(i), end);
            if (t == null)
                throw new ClassFormatException(0, "void as a parameter type in " + desc);
            out.add(t);
            i += end[0];
        }
        if (i >= desc.length())
            throw new ClassFormatException(0, "method descriptor has no ')': " + desc);
        return out;
    }

    private void readStackMap(Frame entry, int entrySize) throws Fail, ClassFormatException {
        mapFrames = new HashMap<>();
        ClassFile.Attr a = code.attr("StackMapTable");
        if (a == null) return;
        StackMap sm = StackMap.parse(a.data, cf.pool);
        StackMap.Frame e = new StackMap.Frame();
        e.locals = entry.locals;
        // Not a scan for the last non-top slot.  A method whose last
        // parameter is a long or a double has top in the slot after it, and
        // trimming that made every append frame in such a method land one
        // slot early: 135 classes of java.base failed with "local N is top
        // here and the declared frame wants ...".
        e.localsSize = entrySize;
        for (StackMap.Frame f : sm.expand(e, maxLocals)) {
            Frame g = new Frame();
            g.locals = f.locals;
            g.stack = new VType[maxStack + 2];
            g.sp = f.stack.length;
            if (g.sp > maxStack)
                throw new Fail(f.offset, "a declared stack map frame holds " + g.sp
                        + " stack slots but max_stack is " + maxStack);
            System.arraycopy(f.stack, 0, g.stack, 0, f.stack.length);
            // The declared frame inherits the flag: a frame inside a
            // constructor before the super call still has an uninitialized
            // this, and the attribute spells that as the type itself.
            g.thisUninit = holdsUninitThis(g);
            if (!indexOf.containsKey(f.offset))
                throw new Fail(f.offset, "a stack map frame is declared at bci "
                        + f.offset + ", which is not the start of an instruction");
            if (mapFrames.put(f.offset, g) != null)
                throw new Fail(f.offset, "two stack map frames are declared at bci "
                        + f.offset);
        }
    }

    private static boolean holdsUninitThis(Frame f) {
        for (VType t : f.locals) if (t == VType.UNINIT_THIS) return true;
        for (int i = 0; i < f.sp; i++) if (f.stack[i] == VType.UNINIT_THIS) return true;
        return false;
    }

    // --------------------------------------------------------------- the walk

    private void walk(Frame entry) throws Fail, Refusal, ClassFormatException {
        checkHandlers();
        Frame cur = entry;
        boolean afterGoto = false;
        for (Op.Insn in : insns) {
            Frame declared = mapFrames.get(in.pc);
            if (declared != null) {
                if (!afterGoto) {
                    if (cur == null)
                        throw new Fail(in.pc, "unreachable code with a stack map frame");
                    assignable(in.pc, cur, declared, "falling into bci " + in.pc);
                }
                cur = declared.copy();
                afterGoto = false;
                framesChecked++;
            } else if (afterGoto) {
                throw new Fail(in.pc, "expecting a stack map frame at bci " + in.pc
                        + ": the previous instruction does not fall through and nothing"
                        + " declares the frame here");
            }
            if (cur == null)
                throw new Fail(in.pc, "no frame is known at bci " + in.pc);
            if (dumpFrames)
                out.println("    " + in.pc + " " + Op.NAME[in.op] + "  " + cur);
            satisfiesHandlers(in.pc, cur);
            Frame next = cur.copy();
            boolean falls = step(in, next);
            instructionsChecked++;
            cur = falls ? next : null;
            afterGoto = !falls;
        }
        if (!afterGoto && cur != null)
            throw new Fail(code.code.length, "control falls off the end of the code array");
    }

    /** Structural checks on the exception table, JVMS 4.10.1.5 and 4.7.3. */
    private void checkHandlers() throws Fail, Refusal, ClassFormatException {
        for (ClassFile.Handler hd : code.handlers) {
            if (hd.start >= hd.end)
                throw new Fail(hd.start, "exception handler range [" + hd.start + ", "
                        + hd.end + ") is empty or inverted");
            if (!indexOf.containsKey(hd.start)
                    || (hd.end != code.code.length && !indexOf.containsKey(hd.end)))
                throw new Fail(hd.start, "exception handler range [" + hd.start + ", "
                        + hd.end + ") does not start and end on instruction boundaries");
            if (!indexOf.containsKey(hd.handler))
                throw new Fail(hd.handler, "exception handler target " + hd.handler
                        + " is not the start of an instruction");
            if (hd.catchType != 0) {
                VType t = VType.fromClassName(cf.pool.className(hd.catchType));
                if (t.isArray() || !h.isJavaSubclassOf(t.name, "java/lang/Throwable"))
                    throw new Fail(hd.handler, "exception handler catches " + t
                            + ", which is not a subclass of java/lang/Throwable");
            }
        }
    }

    /** instructionSatisfiesHandlers, JVMS 4.10.1.6. */
    private void satisfiesHandlers(int pc, Frame cur) throws Fail, Refusal, ClassFormatException {
        for (ClassFile.Handler hd : code.handlers) {
            if (pc < hd.start || pc >= hd.end) continue;
            Frame target = mapFrames.get(hd.handler);
            if (target == null)
                throw new Fail(hd.handler, "no stack map frame is declared at exception"
                        + " handler target " + hd.handler);
            Frame exc = new Frame();
            exc.locals = cur.locals;
            exc.stack = new VType[maxStack + 2];
            exc.sp = 0;
            exc.thisUninit = cur.thisUninit;
            String ct = hd.catchType == 0 ? "java/lang/Throwable"
                    : cf.pool.className(hd.catchType);
            exc.stack[exc.sp++] = VType.object(ct);
            handlerChecks++;
            assignable(pc, exc, target, "entering the handler at bci " + hd.handler
                    + " from bci " + pc);
        }
    }

    /** frameIsAssignable, JVMS 4.10.1.2. */
    private void assignable(int pc, Frame from, Frame to, String what)
            throws Fail, Refusal, ClassFormatException {
        if (from.sp != to.sp)
            throw new Fail(pc, what + ": the operand stack has " + from.sp
                    + " slots here and the declared frame has " + to.sp);
        for (int i = 0; i < from.locals.length && i < to.locals.length; i++)
            if (!h.isAssignable(from.locals[i], to.locals[i]))
                throw new Fail(pc, what + ": local " + i + " is " + from.locals[i]
                        + " here and the declared frame wants " + to.locals[i]);
        for (int i = 0; i < from.sp; i++)
            if (!h.isAssignable(from.stack[i], to.stack[i]))
                throw new Fail(pc, what + ": stack slot " + i + " is " + from.stack[i]
                        + " here and the declared frame wants " + to.stack[i]);
        if (from.thisUninit && !to.thisUninit)
            throw new Fail(pc, what + ": this is still uninitialized here and the declared"
                    + " frame says it is not");
    }

    private void target(int pc, Frame f, int t) throws Fail, Refusal, ClassFormatException {
        Frame declared = mapFrames.get(t);
        if (declared == null)
            throw new Fail(pc, "expecting a stack map frame at branch target " + t);
        assignable(pc, f, declared, "branching to bci " + t);
    }

    // ------------------------------------------------- the frame manipulation

    private void push(Frame f, VType t) throws Fail {
        if (t == null) return;                       // void pushes nothing
        if (f.sp + (t.isTwoWord() ? 2 : 1) > maxStack)
            throw new Fail(-1, "operand stack overflow: pushing " + t + " onto " + f.sp
                    + " slots exceeds max_stack " + maxStack);
        f.stack[f.sp++] = t;
        if (t.isTwoWord()) f.stack[f.sp++] = VType.TOP;
    }

    /** popMatchingType, JVMS 4.10.1.4. */
    private VType pop(Frame f, VType want) throws Fail, Refusal, ClassFormatException {
        if (want.isTwoWord()) {
            if (f.sp < 2)
                throw new Fail(-1, "operand stack underflow: want a " + want + " and the"
                        + " stack holds " + f.sp + " slots");
            if (f.stack[f.sp - 1] != VType.TOP)
                throw new Fail(-1, "want a " + want + " but the top of the stack is "
                        + f.stack[f.sp - 1] + ", not the second half of a two word value");
            VType got = f.stack[f.sp - 2];
            if (!h.isAssignable(got, want))
                throw new Fail(-1, "want a " + want + " on the stack and found " + got);
            f.sp -= 2;
            return got;
        }
        if (f.sp < 1)
            throw new Fail(-1, "operand stack underflow: want a " + want
                    + " and the stack is empty");
        VType got = f.stack[--f.sp];
        if (!h.isAssignable(got, want)) {
            f.sp++;
            throw new Fail(-1, "want a " + want + " on the stack and found " + got);
        }
        return got;
    }

    /** popCategory1: one slot, and not the second half of anything. */
    private VType pop1(Frame f) throws Fail {
        if (f.sp < 1) throw new Fail(-1, "operand stack underflow");
        VType t = f.stack[f.sp - 1];
        if (t == VType.TOP || t.isTwoWord())
            throw new Fail(-1, "want a one word value on the stack and found " + t);
        f.sp--;
        return t;
    }

    /** popCategory2: two slots, a long or a double. */
    private VType pop2(Frame f) throws Fail {
        if (f.sp < 2) throw new Fail(-1, "operand stack underflow");
        if (f.stack[f.sp - 1] != VType.TOP || !f.stack[f.sp - 2].isTwoWord())
            throw new Fail(-1, "want a two word value on the stack and found "
                    + f.stack[f.sp - 1]);
        VType t = f.stack[f.sp - 2];
        f.sp -= 2;
        return t;
    }

    private VType local(Frame f, int i) throws Fail {
        if (i < 0 || i >= maxLocals)
            throw new Fail(-1, "local variable index " + i + " is outside max_locals "
                    + maxLocals);
        return f.locals[i];
    }

    private void loadLocal(Frame f, int i, VType want)
            throws Fail, Refusal, ClassFormatException {
        VType got = local(f, i);
        if (!h.isAssignable(got, want))
            throw new Fail(-1, "local " + i + " holds " + got + " and a " + want
                    + " is wanted");
        push(f, got);
    }

    /** modifyLocalVariable, JVMS 4.10.1.4. */
    private void storeLocal(Frame f, int i, VType t) throws Fail {
        if (i < 0 || i >= maxLocals || (t.isTwoWord() && i + 1 >= maxLocals))
            throw new Fail(-1, "local variable index " + i + " is outside max_locals "
                    + maxLocals);
        // Storing at i invalidates a two word value that began at i-1.
        if (i > 0 && f.locals[i - 1].isTwoWord()) f.locals[i - 1] = VType.TOP;
        f.locals[i] = t;
        if (t.isTwoWord()) f.locals[i + 1] = VType.TOP;
    }

    private void substitute(Frame f, VType from, VType to) {
        for (int i = 0; i < f.locals.length; i++)
            if (f.locals[i].equals(from)) f.locals[i] = to;
        for (int i = 0; i < f.sp; i++)
            if (f.stack[i].equals(from)) f.stack[i] = to;
    }
    // --------------------------------------------------- the transition table
    //
    // JVMS 4.10.1.9, instructionIsTypeSafe.  Returns true if the instruction
    // falls through to the next one.  A Fail raised in here carries bci -1
    // and the caller fills in the real one, so that none of the two hundred
    // rules below has to remember to.

    private boolean step(Op.Insn in, Frame f)
            throws Fail, Refusal, ClassFormatException {
        try {
            return rule(in, f);
        } catch (Fail e) {
            if (e.bci >= 0) throw e;
            throw new Fail(in.pc, Op.NAME[in.op] + ": " + e.getMessage());
        }
    }

    private static final VType THROWABLE = VType.object("java/lang/Throwable");
    private static final VType OBJECT = VType.object("java/lang/Object");
    private static final VType STRING = VType.object("java/lang/String");
    private static final VType CLASS = VType.object("java/lang/Class");

    private boolean rule(Op.Insn in, Frame f)
            throws Fail, Refusal, ClassFormatException {
        int op = in.op;
        switch (op) {
            case 0: return true;                                  // nop
            case 1: push(f, VType.NULL); return true;             // aconst_null
            case 2: case 3: case 4: case 5: case 6: case 7: case 8:
                push(f, VType.INTEGER); return true;              // iconst_m1..5
            case 9: case 10: push(f, VType.LONG); return true;    // lconst_0/1
            case 11: case 12: case 13: push(f, VType.FLOAT); return true;
            case 14: case 15: push(f, VType.DOUBLE); return true;
            case 16: case 17: push(f, VType.INTEGER); return true; // bipush, sipush
            case 18: case 19: push(f, ldcType(in.a, false)); return true;
            case 20: push(f, ldcType(in.a, true)); return true;

            case 21: loadLocal(f, in.a, VType.INTEGER); return true;      // iload
            case 22: loadLocal(f, in.a, VType.LONG); return true;
            case 23: loadLocal(f, in.a, VType.FLOAT); return true;
            case 24: loadLocal(f, in.a, VType.DOUBLE); return true;
            case 25: loadLocal(f, in.a, VType.REFERENCE); return true;    // aload
            case 26: case 27: case 28: case 29:
                loadLocal(f, op - 26, VType.INTEGER); return true;
            case 30: case 31: case 32: case 33:
                loadLocal(f, op - 30, VType.LONG); return true;
            case 34: case 35: case 36: case 37:
                loadLocal(f, op - 34, VType.FLOAT); return true;
            case 38: case 39: case 40: case 41:
                loadLocal(f, op - 38, VType.DOUBLE); return true;
            case 42: case 43: case 44: case 45:
                loadLocal(f, op - 42, VType.REFERENCE); return true;

            case 46: arrayLoad(f, "[I", VType.INTEGER); return true;      // iaload
            case 47: arrayLoad(f, "[J", VType.LONG); return true;
            case 48: arrayLoad(f, "[F", VType.FLOAT); return true;
            case 49: arrayLoad(f, "[D", VType.DOUBLE); return true;
            case 50: {                                                    // aaload
                VType arr = peek(f, 2);
                VType comp = componentOf(arr);
                pop(f, VType.INTEGER);
                pop(f, VType.object("[Ljava/lang/Object;"));
                push(f, comp);
                return true;
            }
            case 51: smallArrayLoad(f); return true;                      // baload
            case 52: arrayLoad(f, "[C", VType.INTEGER); return true;
            case 53: arrayLoad(f, "[S", VType.INTEGER); return true;

            case 54: storeLocal(f, in.a, pop(f, VType.INTEGER)); return true;   // istore
            case 55: storeLocal(f, in.a, pop(f, VType.LONG)); return true;
            case 56: storeLocal(f, in.a, pop(f, VType.FLOAT)); return true;
            case 57: storeLocal(f, in.a, pop(f, VType.DOUBLE)); return true;
            case 58: storeLocal(f, in.a, pop(f, VType.REFERENCE)); return true; // astore
            case 59: case 60: case 61: case 62:
                storeLocal(f, op - 59, pop(f, VType.INTEGER)); return true;
            case 63: case 64: case 65: case 66:
                storeLocal(f, op - 63, pop(f, VType.LONG)); return true;
            case 67: case 68: case 69: case 70:
                storeLocal(f, op - 67, pop(f, VType.FLOAT)); return true;
            case 71: case 72: case 73: case 74:
                storeLocal(f, op - 71, pop(f, VType.DOUBLE)); return true;
            case 75: case 76: case 77: case 78:
                storeLocal(f, op - 75, pop(f, VType.REFERENCE)); return true;

            case 79: arrayStore(f, "[I", VType.INTEGER); return true;     // iastore
            case 80: arrayStore(f, "[J", VType.LONG); return true;
            case 81: arrayStore(f, "[F", VType.FLOAT); return true;
            case 82: arrayStore(f, "[D", VType.DOUBLE); return true;
            case 83:                                                      // aastore
                pop(f, OBJECT);
                pop(f, VType.INTEGER);
                pop(f, VType.object("[Ljava/lang/Object;"));
                return true;
            case 84: {                                                    // bastore
                VType arr = peek(f, 3);
                if (!isSmallArray(arr))
                    throw new Fail(-1, "wants a byte[] or boolean[] and the stack holds "
                            + arr);
                pop(f, VType.INTEGER);
                pop(f, VType.INTEGER);
                pop(f, VType.TOP);
                return true;
            }
            case 85: arrayStore(f, "[C", VType.INTEGER); return true;
            case 86: arrayStore(f, "[S", VType.INTEGER); return true;

            case 87: pop1(f); return true;                                // pop
            case 88:                                                      // pop2
                if (isCategory2(f)) { pop2(f); } else { pop1(f); pop1(f); }
                return true;
            case 89: { VType t = pop1(f); push(f, t); push(f, t); return true; }
            case 90: {                                                    // dup_x1
                VType t1 = pop1(f), t2 = pop1(f);
                push(f, t1); push(f, t2); push(f, t1); return true;
            }
            case 91: {                                                    // dup_x2
                VType t1 = pop1(f);
                if (isCategory2(f)) {
                    VType t2 = pop2(f);
                    push(f, t1); push(f, t2); push(f, t1);
                } else {
                    VType t2 = pop1(f), t3 = pop1(f);
                    push(f, t1); push(f, t3); push(f, t2); push(f, t1);
                }
                return true;
            }
            case 92:                                                      // dup2
                if (isCategory2(f)) {
                    VType t = pop2(f); push(f, t); push(f, t);
                } else {
                    VType t1 = pop1(f), t2 = pop1(f);
                    push(f, t2); push(f, t1); push(f, t2); push(f, t1);
                }
                return true;
            case 93:                                                      // dup2_x1
                if (isCategory2(f)) {
                    VType t1 = pop2(f), t2 = pop1(f);
                    push(f, t1); push(f, t2); push(f, t1);
                } else {
                    VType t1 = pop1(f), t2 = pop1(f), t3 = pop1(f);
                    push(f, t2); push(f, t1); push(f, t3); push(f, t2); push(f, t1);
                }
                return true;
            case 94:                                                      // dup2_x2
                if (isCategory2(f)) {
                    VType t1 = pop2(f);
                    if (isCategory2(f)) {
                        VType t2 = pop2(f);
                        push(f, t1); push(f, t2); push(f, t1);
                    } else {
                        VType t2 = pop1(f), t3 = pop1(f);
                        push(f, t1); push(f, t3); push(f, t2); push(f, t1);
                    }
                } else {
                    VType t1 = pop1(f), t2 = pop1(f);
                    if (isCategory2(f)) {
                        VType t3 = pop2(f);
                        push(f, t2); push(f, t1); push(f, t3);
                        push(f, t2); push(f, t1);
                    } else {
                        VType t3 = pop1(f), t4 = pop1(f);
                        push(f, t2); push(f, t1); push(f, t4); push(f, t3);
                        push(f, t2); push(f, t1);
                    }
                }
                return true;
            case 95: {                                                    // swap
                VType t1 = pop1(f), t2 = pop1(f);
                push(f, t1); push(f, t2); return true;
            }

            case 96: case 100: case 104: case 108: case 112:              // i* arith
                pop(f, VType.INTEGER); pop(f, VType.INTEGER);
                push(f, VType.INTEGER); return true;
            case 97: case 101: case 105: case 109: case 113:              // l* arith
                pop(f, VType.LONG); pop(f, VType.LONG);
                push(f, VType.LONG); return true;
            case 98: case 102: case 106: case 110: case 114:              // f* arith
                pop(f, VType.FLOAT); pop(f, VType.FLOAT);
                push(f, VType.FLOAT); return true;
            case 99: case 103: case 107: case 111: case 115:              // d* arith
                pop(f, VType.DOUBLE); pop(f, VType.DOUBLE);
                push(f, VType.DOUBLE); return true;
            case 116: pop(f, VType.INTEGER); push(f, VType.INTEGER); return true;
            case 117: pop(f, VType.LONG); push(f, VType.LONG); return true;
            case 118: pop(f, VType.FLOAT); push(f, VType.FLOAT); return true;
            case 119: pop(f, VType.DOUBLE); push(f, VType.DOUBLE); return true;
            case 120: case 122: case 124: case 126: case 128: case 130:   // i shifts/bits
                pop(f, VType.INTEGER); pop(f, VType.INTEGER);
                push(f, VType.INTEGER); return true;
            case 121: case 123: case 125:                                 // lshl/lshr/lushr
                pop(f, VType.INTEGER); pop(f, VType.LONG);
                push(f, VType.LONG); return true;
            case 127: case 129: case 131:                                 // land/lor/lxor
                pop(f, VType.LONG); pop(f, VType.LONG);
                push(f, VType.LONG); return true;
            case 132: {                                                   // iinc
                VType t = local(f, in.a);
                if (!h.isAssignable(t, VType.INTEGER))
                    throw new Fail(-1, "local " + in.a + " holds " + t + " and iinc wants int");
                return true;
            }
            case 133: pop(f, VType.INTEGER); push(f, VType.LONG); return true;
            case 134: pop(f, VType.INTEGER); push(f, VType.FLOAT); return true;
            case 135: pop(f, VType.INTEGER); push(f, VType.DOUBLE); return true;
            case 136: pop(f, VType.LONG); push(f, VType.INTEGER); return true;
            case 137: pop(f, VType.LONG); push(f, VType.FLOAT); return true;
            case 138: pop(f, VType.LONG); push(f, VType.DOUBLE); return true;
            case 139: pop(f, VType.FLOAT); push(f, VType.INTEGER); return true;
            case 140: pop(f, VType.FLOAT); push(f, VType.LONG); return true;
            case 141: pop(f, VType.FLOAT); push(f, VType.DOUBLE); return true;
            case 142: pop(f, VType.DOUBLE); push(f, VType.INTEGER); return true;
            case 143: pop(f, VType.DOUBLE); push(f, VType.LONG); return true;
            case 144: pop(f, VType.DOUBLE); push(f, VType.FLOAT); return true;
            case 145: case 146: case 147:                                 // i2b/i2c/i2s
                pop(f, VType.INTEGER); push(f, VType.INTEGER); return true;
            case 148: pop(f, VType.LONG); pop(f, VType.LONG);
                push(f, VType.INTEGER); return true;
            case 149: case 150: pop(f, VType.FLOAT); pop(f, VType.FLOAT);
                push(f, VType.INTEGER); return true;
            case 151: case 152: pop(f, VType.DOUBLE); pop(f, VType.DOUBLE);
                push(f, VType.INTEGER); return true;

            case 153: case 154: case 155: case 156: case 157: case 158:   // if<cond>
                pop(f, VType.INTEGER); target(in.pc, f, in.a); return true;
            case 159: case 160: case 161: case 162: case 163: case 164:   // if_icmp
                pop(f, VType.INTEGER); pop(f, VType.INTEGER);
                target(in.pc, f, in.a); return true;
            case 165: case 166:                                           // if_acmp
                pop(f, VType.REFERENCE); pop(f, VType.REFERENCE);
                target(in.pc, f, in.a); return true;
            case 167: case 200: target(in.pc, f, in.a); return false;     // goto, goto_w
            case 168: case 201: case 169:                                 // jsr, jsr_w, ret
                throw new Fail(in.pc, Op.NAME[op] + " is not allowed by the type checking"
                        + " verifier (JVMS 4.10.1); it exists only on the type inference"
                        + " path, which class file version " + cf.major + " does not use");
            case 170: case 171: {                                         // switches
                pop(f, VType.INTEGER);
                if (op == 171) {
                    for (int i = 1; i < in.keys.length; i++)
                        if (in.keys[i - 1] >= in.keys[i])
                            throw new Fail(in.pc, "lookupswitch keys are not sorted in"
                                    + " increasing order: " + in.keys[i - 1] + " then "
                                    + in.keys[i]);
                }
                target(in.pc, f, in.defaultTarget);
                for (int t : in.targets) target(in.pc, f, t);
                return false;
            }

            case 172: return ret(f, VType.INTEGER);
            case 173: return ret(f, VType.LONG);
            case 174: return ret(f, VType.FLOAT);
            case 175: return ret(f, VType.DOUBLE);
            case 176: {                                                   // areturn
                if (returnType == null || !returnType.isReference())
                    throw new Fail(-1, "areturn in a method returning "
                            + (returnType == null ? "void" : returnType.toString()));
                pop(f, returnType);
                return false;
            }
            case 177:                                                     // return
                if (returnType != null)
                    throw new Fail(-1, "return in a method returning " + returnType);
                // JVMS 4.10.1.9: returning is type safe only if the frame does
                // not carry flagThisUninit.  It is the one rule in the whole
                // table that reads the flag rather than the stack or the
                // locals, and HotSpot phrases it "Constructor must call
                // super() or this() before return".
                if (f.thisUninit)
                    throw new Fail(-1, "return from " + method.name + method.desc
                            + ", which has not called another constructor:"
                            + " this is still uninitialized");
                return false;

            case 178: push(f, fieldType(in.a)); return true;               // getstatic
            case 179: pop(f, fieldType(in.a)); return true;                // putstatic
            case 180: {                                                    // getfield
                VType t = fieldType(in.a);
                pop(f, VType.object(cf.pool.refOwner(in.a)));
                push(f, t);
                return true;
            }
            case 181: {                                                    // putfield
                VType t = fieldType(in.a);
                String owner = cf.pool.refOwner(in.a);
                pop(f, t);
                // JVMS 4.10.1.9 gives putfield two rules, and the second one
                // is the whole reason a constructor can assign to its own
                // fields before calling super(): the objectref may be
                // uninitializedThis as long as the field belongs to the class
                // being verified.  3415 of java.base's 7574 classes failed
                // without it.
                if (f.sp > 0 && f.stack[f.sp - 1] == VType.UNINIT_THIS
                        && owner.equals(cf.thisClass)) {
                    f.sp--;
                } else {
                    pop(f, VType.object(owner));
                }
                return true;
            }

            case 182: case 183: case 184: case 185: case 186:
                return invoke(in, f);

            case 187: {                                                    // new
                requireTag(in.a, ConstantPool.CLASS, "new");
                String n = cf.pool.className(in.a);
                if (n.charAt(0) == '[')
                    throw new Fail(-1, "new of an array type " + n
                            + "; anewarray and multianewarray are the array instructions");
                VType u = VType.uninit(in.pc);
                for (int i = 0; i < f.sp; i++)
                    if (f.stack[i].equals(u))
                        throw new Fail(-1, "the operand stack already holds the"
                                + " uninitialized object made by the new at bci " + in.pc);
                substitute(f, u, VType.TOP);
                push(f, u);
                return true;
            }
            case 188: {                                                    // newarray
                if (in.a < 4 || in.a > 11)
                    throw new Fail(-1, "newarray atype " + in.a + " is not one of 4..11");
                pop(f, VType.INTEGER);
                push(f, VType.object("[" + "ZCFDBSIJ".charAt(in.a - 4)));
                return true;
            }
            case 189: {                                                    // anewarray
                requireTag(in.a, ConstantPool.CLASS, "anewarray");
                String n = cf.pool.className(in.a);
                pop(f, VType.INTEGER);
                push(f, VType.object(n.charAt(0) == '[' ? "[" + n : "[L" + n + ";"));
                return true;
            }
            case 190: {                                                    // arraylength
                VType arr = peek(f, 1);
                componentOf(arr);
                pop(f, VType.TOP);
                push(f, VType.INTEGER);
                return true;
            }
            case 191: pop(f, THROWABLE); return false;                     // athrow
            case 192: {                                                    // checkcast
                requireTag(in.a, ConstantPool.CLASS, "checkcast");
                pop(f, OBJECT);
                push(f, VType.fromClassName(cf.pool.className(in.a)));
                return true;
            }
            case 193:                                                      // instanceof
                requireTag(in.a, ConstantPool.CLASS, "instanceof");
                pop(f, OBJECT);
                push(f, VType.INTEGER);
                return true;
            case 194: case 195: pop(f, VType.REFERENCE); return true;      // monitor
            case 197: {                                                    // multianewarray
                requireTag(in.a, ConstantPool.CLASS, "multianewarray");
                String n = cf.pool.className(in.a);
                int dims = in.b;
                if (dims == 0)
                    throw new Fail(-1, "multianewarray with zero dimensions");
                int have = 0;
                while (have < n.length() && n.charAt(have) == '[') have++;
                if (have < dims)
                    throw new Fail(-1, "multianewarray of " + n + " with " + dims
                            + " dimensions, but the type has only " + have);
                for (int i = 0; i < dims; i++) pop(f, VType.INTEGER);
                push(f, VType.fromClassName(n));
                return true;
            }
            case 198: case 199:                                            // ifnull
                pop(f, VType.REFERENCE); target(in.pc, f, in.a); return true;
            default:
                throw new Fail(in.pc, "opcode " + op + " has no type rule");
        }
    }

    private boolean ret(Frame f, VType want) throws Fail, Refusal, ClassFormatException {
        if (returnType == null || !returnType.equals(want))
            throw new Fail(-1, "returns a " + want + " from a method declared to return "
                    + (returnType == null ? "void" : returnType.toString()));
        pop(f, want);
        return false;
    }

    private VType peek(Frame f, int fromTop) throws Fail {
        if (f.sp < fromTop) throw new Fail(-1, "operand stack underflow");
        return f.stack[f.sp - fromTop];
    }

    private boolean isCategory2(Frame f) {
        return f.sp >= 2 && f.stack[f.sp - 1] == VType.TOP && f.stack[f.sp - 2].isTwoWord();
    }

    private static boolean isSmallArray(VType t) {
        return t == VType.NULL
                || (t.kind == VType.Kind.OBJECT && (t.name.equals("[B") || t.name.equals("[Z")));
    }

    /** arrayComponentType, JVMS 4.10.1.9: null has component type null. */
    private VType componentOf(VType arr) throws Fail, ClassFormatException {
        if (arr == VType.NULL) return VType.NULL;
        if (arr.kind == VType.Kind.OBJECT && arr.isArray()) return arr.componentType();
        throw new Fail(-1, "wants an array on the stack and found " + arr);
    }

    private void arrayLoad(Frame f, String arrayDesc, VType result)
            throws Fail, Refusal, ClassFormatException {
        pop(f, VType.INTEGER);
        pop(f, VType.object(arrayDesc));
        push(f, result);
    }

    private void smallArrayLoad(Frame f) throws Fail, Refusal, ClassFormatException {
        VType arr = peek(f, 2);
        if (!isSmallArray(arr))
            throw new Fail(-1, "wants a byte[] or boolean[] and the stack holds " + arr);
        pop(f, VType.INTEGER);
        pop(f, VType.TOP);
        push(f, VType.INTEGER);
    }

    private void arrayStore(Frame f, String arrayDesc, VType value)
            throws Fail, Refusal, ClassFormatException {
        pop(f, value);
        pop(f, VType.INTEGER);
        pop(f, VType.object(arrayDesc));
    }

    private void requireTag(int idx, int tag, String what) throws Fail {
        if (!cf.pool.valid(idx) || cf.pool.tag(idx) != tag)
            throw new Fail(-1, what + " names constant pool entry " + idx + ", which is "
                    + (cf.pool.valid(idx) ? ConstantPool.tagName(cf.pool.tag(idx))
                                          : "out of range or unusable")
                    + " where " + ConstantPool.tagName(tag) + " is required");
    }

    private VType fieldType(int idx) throws Fail, ClassFormatException {
        requireTag(idx, ConstantPool.FIELDREF, "a field instruction");
        return VType.fromDescriptor(cf.pool.refDesc(idx));
    }

    private VType ldcType(int idx, boolean wide) throws Fail, ClassFormatException {
        if (!cf.pool.valid(idx))
            throw new Fail(-1, "ldc names constant pool entry " + idx
                    + ", which is out of range or unusable");
        int tag = cf.pool.tag(idx);
        VType t;
        switch (tag) {
            case ConstantPool.INTEGER: t = VType.INTEGER; break;
            case ConstantPool.FLOAT: t = VType.FLOAT; break;
            case ConstantPool.LONG: t = VType.LONG; break;
            case ConstantPool.DOUBLE: t = VType.DOUBLE; break;
            case ConstantPool.STRING: t = STRING; break;
            case ConstantPool.CLASS: t = CLASS; break;
            case ConstantPool.METHODTYPE: t = VType.object("java/lang/invoke/MethodType"); break;
            case ConstantPool.METHODHANDLE:
                t = VType.object("java/lang/invoke/MethodHandle"); break;
            case ConstantPool.DYNAMIC:
                t = VType.fromDescriptor(cf.pool.ntDesc(cf.pool.y(idx))); break;
            default:
                throw new Fail(-1, "ldc names a " + ConstantPool.tagName(tag)
                        + ", which is not a loadable constant");
        }
        if (t.isTwoWord() != wide)
            throw new Fail(-1, (wide ? "ldc2_w" : "ldc") + " names a " + t
                    + ", which is the wrong width for this instruction");
        return t;
    }

    // ------------------------------------------------------------ invocation

    private boolean invoke(Op.Insn in, Frame f)
            throws Fail, Refusal, ClassFormatException {
        int op = in.op;
        int idx = in.a;
        if (op == 186) {
            requireTag(idx, ConstantPool.INVOKEDYNAMIC, "invokedynamic");
        } else if (op == 185) {
            requireTag(idx, ConstantPool.INTERFACEMETHODREF, "invokeinterface");
        } else if (op == 182) {
            requireTag(idx, ConstantPool.METHODREF, "invokevirtual");
        } else {
            if (!cf.pool.valid(idx) || (cf.pool.tag(idx) != ConstantPool.METHODREF
                    && cf.pool.tag(idx) != ConstantPool.INTERFACEMETHODREF))
                throw new Fail(-1, Op.NAME[op] + " names constant pool entry " + idx
                        + ", which is not a Methodref or an InterfaceMethodref");
        }
        String name = op == 186 ? cf.pool.ntName(cf.pool.y(idx)) : cf.pool.refName(idx);
        String desc = op == 186 ? cf.pool.ntDesc(cf.pool.y(idx)) : cf.pool.refDesc(idx);
        List<VType> args = argTypes(desc);
        String rd = ClassFile.descReturn(desc);
        VType result = rd.equals("V") ? null : VType.fromDescriptor(rd);

        if (name.equals("<clinit>"))
            throw new Fail(-1, "a class initialiser cannot be invoked");
        if (name.equals("<init>") && op != 183)
            throw new Fail(-1, "<init> can only be reached by invokespecial");

        if (op == 185) {
            int size = 1;
            for (VType t : args) size += t.isTwoWord() ? 2 : 1;
            if (in.b != size)
                throw new Fail(-1, "invokeinterface count is " + in.b + " and the argument"
                        + " list needs " + size);
        }

        if (op == 183 && name.equals("<init>")) return initCall(in, f, idx, args, rd);

        for (int i = args.size() - 1; i >= 0; i--) pop(f, args.get(i));
        if (op != 184 && op != 186) {
            String owner = cf.pool.refOwner(idx);
            VType got = pop(f, VType.fromClassName(owner));
            if (op == 183) {
                // JVMS 4.10.1.9 invokespecial: the objectref must be assignable
                // to the current class as well as to the method's class.  That
                // is the rule that stops invokespecial being a way to call a
                // protected method on someone else's object.
                if (!h.isAssignable(got, VType.object(cf.thisClass)))
                    throw new Fail(-1, "invokespecial of " + owner + "." + name
                            + " on a " + got + ", which is not assignable to the current"
                            + " class " + cf.thisClass);
            }
        }
        push(f, result);
        return true;
    }

    /** invokespecial of a constructor: the only place a type changes identity. */
    private boolean initCall(Op.Insn in, Frame f, int idx, List<VType> args, String rd)
            throws Fail, Refusal, ClassFormatException {
        if (!rd.equals("V"))
            throw new Fail(-1, "<init> declared to return " + rd + ", not void");
        for (int i = args.size() - 1; i >= 0; i--) pop(f, args.get(i));
        if (f.sp < 1) throw new Fail(-1, "operand stack underflow looking for the"
                + " uninitialized object to initialise");
        VType self = f.stack[--f.sp];
        String owner = cf.pool.refOwner(idx);
        if (self == VType.UNINIT_THIS) {
            if (!owner.equals(cf.thisClass) && !owner.equals(cf.superClass))
                throw new Fail(-1, "this constructor calls " + owner + ".<init>, which is"
                        + " neither this class " + cf.thisClass + " nor its direct"
                        + " superclass " + cf.superClass);
            substitute(f, VType.UNINIT_THIS, VType.object(cf.thisClass));
            f.thisUninit = false;
        } else if (self.kind == VType.Kind.UNINIT) {
            Integer at = indexOf.get(self.offset);
            if (at == null)
                throw new Fail(-1, "an uninitialized object claims to come from bci "
                        + self.offset + ", which is not an instruction");
            Op.Insn mk = insns.get(at);
            if (mk.op != 187)
                throw new Fail(-1, "an uninitialized object claims to come from bci "
                        + self.offset + ", which is " + Op.NAME[mk.op] + ", not new");
            String made = cf.pool.className(mk.a);
            if (!made.equals(owner))
                throw new Fail(-1, "the new at bci " + self.offset + " makes a " + made
                        + " and the constructor called is " + owner + ".<init>");
            substitute(f, self, VType.object(owner));
        } else {
            throw new Fail(-1, "<init> called on " + self
                    + ", which is already initialized");
        }
        return true;
    }
}
