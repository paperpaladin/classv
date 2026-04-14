package classv;

import java.util.ArrayList;
import java.util.List;

/** A parsed class file, JVMS 4.1. */
public final class ClassFile {

    public static final class Attr {
        public final String name;
        public final byte[] data;
        /** Offset of the first byte of the attribute's info, for diagnostics. */
        public final int offset;
        Attr(String name, byte[] data, int offset) {
            this.name = name; this.data = data; this.offset = offset;
        }
    }

    public static final class Member {
        public final int flags;
        public final String name;
        public final String desc;
        public final int nameIndex;
        public final int descIndex;
        public final List<Attr> attrs;
        public CodeAttr code;
        Member(int flags, String name, String desc, int ni, int di, List<Attr> attrs) {
            this.flags = flags; this.name = name; this.desc = desc;
            this.nameIndex = ni; this.descIndex = di; this.attrs = attrs;
        }
        public Attr attr(String n) {
            for (Attr a : attrs) if (a.name.equals(n)) return a;
            return null;
        }
    }

    public static final class Handler {
        public final int start, end, handler, catchType;
        Handler(int s, int e, int h, int c) { start = s; end = e; handler = h; catchType = c; }
    }

    public static final class CodeAttr {
        public final int maxStack, maxLocals;
        public final byte[] code;
        public final List<Handler> handlers;
        public final List<Attr> attrs;
        CodeAttr(int ms, int ml, byte[] code, List<Handler> h, List<Attr> a) {
            maxStack = ms; maxLocals = ml; this.code = code; handlers = h; attrs = a;
        }
        public Attr attr(String n) {
            for (Attr a : attrs) if (a.name.equals(n)) return a;
            return null;
        }
    }

    public final byte[] bytes;
    public int minor, major;
    public ConstantPool pool;
    public int accessFlags;
    public int thisClassIndex, superClassIndex;
    public String thisClass;
    public String superClass;      // null for java/lang/Object only
    public int[] interfaceIndexes;
    public String[] interfaces;
    public final List<Member> fields = new ArrayList<>();
    public final List<Member> methods = new ArrayList<>();
    public final List<Attr> attrs = new ArrayList<>();
    /** Bytes after the last structure the spec accounts for; must be zero. */
    public int trailing;

    private ClassFile(byte[] bytes) { this.bytes = bytes; }

    public static ClassFile parse(byte[] data) throws ClassFormatException {
        ClassFile cf = new ClassFile(data);
        Buf b = new Buf(data);
        long magic = b.u4();
        if (magic != 0xCAFEBABEL)
            throw new ClassFormatException(0, String.format(
                    "magic is 0x%08x, not 0xcafebabe", magic));
        cf.minor = b.u2();
        cf.major = b.u2();
        cf.pool = readPool(b);
        cf.accessFlags = b.u2();
        cf.thisClassIndex = b.u2();
        cf.thisClass = cf.pool.className(cf.thisClassIndex);
        cf.superClassIndex = b.u2();
        cf.superClass = cf.superClassIndex == 0 ? null : cf.pool.className(cf.superClassIndex);
        int ni = b.u2();
        cf.interfaceIndexes = new int[ni];
        cf.interfaces = new String[ni];
        for (int i = 0; i < ni; i++) {
            cf.interfaceIndexes[i] = b.u2();
            cf.interfaces[i] = cf.pool.className(cf.interfaceIndexes[i]);
        }
        readMembers(b, cf.pool, cf.fields);
        readMembers(b, cf.pool, cf.methods);
        readAttrs(b, cf.pool, cf.attrs);
        cf.trailing = b.remaining();
        for (Member m : cf.methods) {
            Attr a = m.attr("Code");
            if (a != null) m.code = readCode(a, cf.pool);
        }
        return cf;
    }

    private static ConstantPool readPool(Buf b) throws ClassFormatException {
        int count = b.u2();
        if (count < 1)
            throw new ClassFormatException(b.pos() - 2, "constant_pool_count is 0; the "
                    + "smallest legal value is 1 (an empty pool)");
        ConstantPool p = new ConstantPool(count);
        boolean[] bad = new boolean[1];
        for (int i = 1; i < count; i++) {
            int tag = b.u1();
            p.tag[i] = tag;
            switch (tag) {
                case ConstantPool.UTF8: {
                    int len = b.u2();
                    byte[] raw = b.bytes(len);
                    p.raw[i] = raw;
                    bad[0] = false;
                    p.str[i] = ConstantPool.decodeUtf8(raw, bad);
                    p.badUtf8[i] = bad[0];
                    if (bad[0])
                        // JVMS 4.4.7, and HotSpot agrees exactly: 0x00 on its
                        // own, 0xf0 and above, a continuation byte with no
                        // lead, a truncated sequence, and any overlong form
                        // except 0xc0 0x80 are all rejected; the unpaired
                        // surrogate ed a0 80 is not, which is the whole
                        // reason this encoding is not UTF-8.  Measured one
                        // case at a time against the JVM, and the suite
                        // keeps the measurement.
                        throw new ClassFormatException(b.pos() - len,
                                "constant pool entry " + i + " is " + len + " bytes that"
                                + " are not well formed modified UTF-8 (JVMS 4.4.7)");
                    break;
                }
                case ConstantPool.INTEGER:
                case ConstantPool.FLOAT:
                    p.bits[i] = b.s4() & 0xffffffffL;
                    if (tag == ConstantPool.INTEGER) p.bits[i] = (int) p.bits[i];
                    break;
                case ConstantPool.LONG:
                case ConstantPool.DOUBLE:
                    p.bits[i] = b.s8();
                    // JVMS 4.4.5: the next index is unusable.  Leaving it tagged
                    // 0 is what makes require() able to say so by name.
                    i++;
                    if (i >= count)
                        throw new ClassFormatException(b.pos(), "a " + ConstantPool.tagName(tag)
                                + " constant at index " + (i - 1)
                                + " needs the following index, which is past the end of the pool");
                    break;
                case ConstantPool.CLASS:
                case ConstantPool.STRING:
                case ConstantPool.METHODTYPE:
                case ConstantPool.MODULE:
                case ConstantPool.PACKAGE:
                    p.x[i] = b.u2();
                    break;
                case ConstantPool.FIELDREF:
                case ConstantPool.METHODREF:
                case ConstantPool.INTERFACEMETHODREF:
                case ConstantPool.NAMEANDTYPE:
                case ConstantPool.DYNAMIC:
                case ConstantPool.INVOKEDYNAMIC:
                    p.x[i] = b.u2();
                    p.y[i] = b.u2();
                    break;
                case ConstantPool.METHODHANDLE:
                    p.x[i] = b.u1();
                    p.y[i] = b.u2();
                    break;
                default:
                    throw new ClassFormatException(b.pos() - 1,
                            "constant pool entry " + i + " has unknown tag " + tag);
            }
        }
        return p;
    }

    private static void readMembers(Buf b, ConstantPool p, List<Member> out)
            throws ClassFormatException {
        int n = b.u2();
        for (int i = 0; i < n; i++) {
            int flags = b.u2();
            int ni = b.u2();
            int di = b.u2();
            List<Attr> as = new ArrayList<>();
            readAttrs(b, p, as);
            out.add(new Member(flags, p.utf8(ni), p.utf8(di), ni, di, as));
        }
    }

    private static void readAttrs(Buf b, ConstantPool p, List<Attr> out)
            throws ClassFormatException {
        int n = b.u2();
        for (int i = 0; i < n; i++) {
            int ni = b.u2();
            long len = b.u4();
            if (len > Integer.MAX_VALUE || len > b.remaining())
                throw new ClassFormatException(b.pos(), "attribute "
                        + (p.valid(ni) && p.tag(ni) == ConstantPool.UTF8 ? p.utf8(ni) : "#" + ni)
                        + " claims " + len + " bytes but only " + b.remaining() + " remain");
            int off = b.pos();
            out.add(new Attr(p.utf8(ni), b.bytes((int) len), off));
        }
    }

    private static CodeAttr readCode(Attr a, ConstantPool p) throws ClassFormatException {
        Buf b = new Buf(a.data);
        int maxStack = b.u2();
        int maxLocals = b.u2();
        long clen = b.u4();
        if (clen > b.remaining())
            throw new ClassFormatException(a.offset + b.pos(),
                    "Code attribute claims code_length " + clen + " with " + b.remaining()
                    + " bytes left in the attribute");
        byte[] code = b.bytes((int) clen);
        int nh = b.u2();
        List<Handler> hs = new ArrayList<>();
        for (int i = 0; i < nh; i++)
            hs.add(new Handler(b.u2(), b.u2(), b.u2(), b.u2()));
        List<Attr> as = new ArrayList<>();
        readAttrs(b, p, as);
        return new CodeAttr(maxStack, maxLocals, code, hs, as);
    }

    // ---------------------------------------------------------------- flags

    public static final int ACC_PUBLIC = 0x0001, ACC_PRIVATE = 0x0002, ACC_PROTECTED = 0x0004,
            ACC_STATIC = 0x0008, ACC_FINAL = 0x0010, ACC_SUPER = 0x0020, ACC_SYNCHRONIZED = 0x0020,
            ACC_VOLATILE = 0x0040, ACC_BRIDGE = 0x0040, ACC_TRANSIENT = 0x0080,
            ACC_VARARGS = 0x0080, ACC_NATIVE = 0x0100, ACC_INTERFACE = 0x0200,
            ACC_ABSTRACT = 0x0400, ACC_STRICT = 0x0800, ACC_SYNTHETIC = 0x1000,
            ACC_ANNOTATION = 0x2000, ACC_ENUM = 0x4000, ACC_MODULE = 0x8000;

    public boolean isInterface() { return (accessFlags & ACC_INTERFACE) != 0; }

    public static String descReturn(String desc) throws ClassFormatException {
        int i = desc.indexOf(')');
        if (i < 0) throw new ClassFormatException(0, "method descriptor has no ')': " + desc);
        return desc.substring(i + 1);
    }
}
