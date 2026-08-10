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
        checkPoolRefs(cf.pool);
        checkClassNames(cf.pool);
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
        for (Member m : cf.fields) checkDescriptor(m, false);
        for (Member m : cf.methods) checkDescriptor(m, true);
        readAttrs(b, cf.pool, cf.attrs);
        cf.trailing = b.remaining();
        for (Member m : cf.methods) {
            Attr a = m.attr("Code");
            if (a != null) m.code = readCode(a, cf.pool);
        }
        return cf;
    }

    /**
     * Every index one constant pool entry holds into another, JVMS 4.4.
     * HotSpot checks these while parsing and says "Invalid constant pool
     * index 30218" or "Bad method handle kind"; classv checked them only
     * where it happened to follow one, so a Fieldref pointing at a Utf8
     * went unnoticed unless an instruction used it.  Six of the families
     * random corruption was still finding were this.
     */
    private static void checkPoolRefs(ConstantPool p) throws ClassFormatException {
        for (int i = 1; i < p.count; i++) {
            switch (p.tag(i)) {
                case ConstantPool.CLASS: case ConstantPool.STRING:
                case ConstantPool.METHODTYPE: case ConstantPool.MODULE:
                case ConstantPool.PACKAGE:
                    poolRef(p, i, p.x(i), ConstantPool.UTF8);
                    break;
                case ConstantPool.FIELDREF: case ConstantPool.METHODREF:
                case ConstantPool.INTERFACEMETHODREF:
                    poolRef(p, i, p.x(i), ConstantPool.CLASS);
                    poolRef(p, i, p.y(i), ConstantPool.NAMEANDTYPE);
                    break;
                case ConstantPool.NAMEANDTYPE:
                    poolRef(p, i, p.x(i), ConstantPool.UTF8);
                    poolRef(p, i, p.y(i), ConstantPool.UTF8);
                    break;
                case ConstantPool.DYNAMIC: case ConstantPool.INVOKEDYNAMIC:
                    // x is an index into the BootstrapMethods attribute,
                    // which is not a constant pool index and is checked
                    // where that attribute is read.
                    poolRef(p, i, p.y(i), ConstantPool.NAMEANDTYPE);
                    break;
                case ConstantPool.METHODHANDLE: {
                    int kind = p.x(i);
                    if (kind < 1 || kind > 9)
                        throw new ClassFormatException(0, "constant pool entry " + i
                                + " is a MethodHandle of kind " + kind
                                + ", and the kinds are 1 to 9 (JVMS 4.4.8)");
                    int t = p.tag(p.y(i));
                    boolean field = kind <= 4;
                    boolean ok = field ? t == ConstantPool.FIELDREF
                            : (t == ConstantPool.METHODREF
                               || t == ConstantPool.INTERFACEMETHODREF);
                    if (!ok)
                        throw new ClassFormatException(0, "constant pool entry " + i
                                + " is a MethodHandle of kind " + kind + " naming entry "
                                + p.y(i) + ", which is " + ConstantPool.tagName(t)
                                + " and not a " + (field ? "Fieldref" : "method reference"));
                    break;
                }
                default:
                    break;
            }
        }
    }

    private static void poolRef(ConstantPool p, int from, int to, int tag)
            throws ClassFormatException {
        if (p.tag(to) != tag)
            throw new ClassFormatException(0, "constant pool entry " + from + ", a "
                    + ConstantPool.tagName(p.tag(from)) + ", names entry " + to
                    + ", which is " + (p.valid(to) ? ConstantPool.tagName(p.tag(to))
                                                   : "out of range or unusable")
                    + " where " + ConstantPool.tagName(tag) + " is required");
    }

    /**
     * Every CONSTANT_Class name, JVMS 4.2.1, in a second pass because a
     * Class entry may name a Utf8 that comes after it.
     *
     * HotSpot is laxer here than the language is, and the rule below is what
     * it does rather than what Java identifiers look like: a space, a '<'
     * and a leading digit are all accepted in a class name, and the suite
     * has a case for each so that being stricter would be caught.  What is
     * rejected is '.', ';', '[' outside an array descriptor, and an empty
     * component, which is what "a//b" and a leading or trailing slash give.
     */
    private static void checkClassNames(ConstantPool p) throws ClassFormatException {
        for (int i = 1; i < p.count; i++) {
            if (p.tag(i) != ConstantPool.CLASS) continue;
            String n = p.className(i);
            if (!legalClassName(n))
                throw new ClassFormatException(0, "constant pool entry " + i
                        + " names the class \"" + n + "\", which is not a legal class"
                        + " name (JVMS 4.2.1)");
        }
    }

    /**
     * JVMS 4.3: a field descriptor is one type, a method descriptor is a
     * parenthesised list and a return type.  HotSpot checks both while
     * parsing, with the message "Field x in class Y has illegal signature",
     * so a corrupted descriptor is a ClassFormatError and not something the
     * verifier reaches later.  Random corruption found it.
     */
    private static void checkDescriptor(Member m, boolean method)
            throws ClassFormatException {
        String d = m.desc;
        try {
            if (!method) {
                if (VType.fromDescriptor(d) == null)
                    throw new ClassFormatException(0, "void");
                return;
            }
            if (d.isEmpty() || d.charAt(0) != '(')
                throw new ClassFormatException(0, "no '('");
            int i = 1;
            int[] end = new int[1];
            while (i < d.length() && d.charAt(i) != ')') {
                if (VType.fromDescriptor(d.substring(i), end) == null)
                    throw new ClassFormatException(0, "void as a parameter type");
                i += end[0];
            }
            if (i >= d.length())
                throw new ClassFormatException(0, "no ')'");
            String ret = d.substring(i + 1);
            VType.fromDescriptor(ret, end);         // null is fine: void
            if (end[0] != ret.length())
                throw new ClassFormatException(0, "trailing bytes after the return type");
        } catch (ClassFormatException e) {
            throw new ClassFormatException(0, (method ? "method " : "field ") + m.name
                    + " has the descriptor \"" + d + "\", which is not a legal "
                    + (method ? "method" : "field") + " descriptor (JVMS 4.3): "
                    + e.getMessage());
        }
    }

    static boolean legalClassName(String n) {
        if (n.isEmpty()) return false;
        if (n.charAt(0) == '[') {
            try {
                VType.fromDescriptor(n);
                return true;
            } catch (ClassFormatException e) {
                return false;
            }
        }
        int start = 0;
        for (int i = 0; i <= n.length(); i++) {
            char c = i == n.length() ? '/' : n.charAt(i);
            if (c == '/') {
                if (i == start) return false;        // an empty component
                start = i + 1;
            } else if (c == '.' || c == ';' || c == '[') {
                return false;
            }
        }
        return true;
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
            Attr a = new Attr(p.utf8(ni), b.bytes((int) len), off);
            checkAttrLength(a, off);
            checkAttrIndexes(a, p, off);
            out.add(a);
        }
    }

    /**
     * An attribute whose shape is a count followed by fixed size entries has
     * a length the count determines, and a mismatch is a ClassFormatError in
     * HotSpot rather than something the verifier later trips over.  Random
     * corruption found these: bumping the count of a LineNumberTable by one
     * gave "LineNumberTable attribute has wrong length" from the JVM and an
     * untroubled OK from classv.
     *
     * The list is the attributes whose count HotSpot checks, measured by
     * bumping each one in turn.  Code, Signature and SourceFile are not in
     * it because their first two bytes are not a count.
     */
    private static void checkAttrLength(Attr a, int off) throws ClassFormatException {
        int entry;
        switch (a.name) {
            case "Exceptions": case "NestMembers": case "PermittedSubclasses":
                entry = 2; break;
            case "LineNumberTable": entry = 4; break;
            case "LocalVariableTable": case "LocalVariableTypeTable": entry = 10; break;
            case "InnerClasses": entry = 8; break;
            case "BootstrapMethods": checkBootstrapMethods(a, off); return;
            default: return;
        }
        if (a.data.length < 2)
            throw new ClassFormatException(off, a.name + " is " + a.data.length
                    + " bytes, too short to hold even its own count");
        int count = ((a.data[0] & 0xff) << 8) | (a.data[1] & 0xff);
        int want = 2 + entry * count;
        if (a.data.length != want)
            throw new ClassFormatException(off, a.name + " says it holds " + count
                    + " entries of " + entry + " bytes, which needs " + want
                    + " bytes, and the attribute is " + a.data.length);
    }

    /**
     * Attributes are mostly constant pool indexes, and an index of the wrong
     * kind is a ClassFormatError in HotSpot while parsing: "inner_name_index
     * 229 has bad constant type", "Invalid SourceFile attribute at constant
     * pool index 62952".  Found by random corruption, which reached these
     * long before it reached anything the verifier does.
     *
     * Zero is allowed where the spec allows it and nowhere else, which is
     * the whole content of several of these rules.
     */
    private static void checkAttrIndexes(Attr a, ConstantPool p, int off)
            throws ClassFormatException {
        Buf b = new Buf(a.data);
        switch (a.name) {
            case "SourceFile": case "Signature":
                need(p, b.u2(), ConstantPool.UTF8, false, a, off);
                break;
            case "NestHost": case "ModuleMainClass":
                need(p, b.u2(), ConstantPool.CLASS, false, a, off);
                break;
            case "ConstantValue": {
                int i = b.u2();
                int t = p.tag(i);
                if (t != ConstantPool.INTEGER && t != ConstantPool.FLOAT
                        && t != ConstantPool.LONG && t != ConstantPool.DOUBLE
                        && t != ConstantPool.STRING)
                    throw new ClassFormatException(off, "ConstantValue names constant pool"
                            + " entry " + i + ", which is "
                            + ConstantPool.tagName(t) + " and not a constant");
                break;
            }
            case "EnclosingMethod":
                need(p, b.u2(), ConstantPool.CLASS, false, a, off);
                need(p, b.u2(), ConstantPool.NAMEANDTYPE, true, a, off);
                break;
            case "Exceptions": case "NestMembers": case "PermittedSubclasses": {
                int n = b.u2();
                for (int i = 0; i < n; i++) need(p, b.u2(), ConstantPool.CLASS, false, a, off);
                break;
            }
            case "InnerClasses": {
                int n = b.u2();
                for (int i = 0; i < n; i++) {
                    need(p, b.u2(), ConstantPool.CLASS, false, a, off);
                    need(p, b.u2(), ConstantPool.CLASS, true, a, off);
                    need(p, b.u2(), ConstantPool.UTF8, true, a, off);
                    b.u2();                       // the inner class's own flags
                }
                break;
            }
            case "MethodParameters": {
                int n = b.u1();
                for (int i = 0; i < n; i++) {
                    need(p, b.u2(), ConstantPool.UTF8, true, a, off);
                    b.u2();
                }
                break;
            }
            case "BootstrapMethods": {
                int n = b.u2();
                for (int i = 0; i < n; i++) {
                    need(p, b.u2(), ConstantPool.METHODHANDLE, false, a, off);
                    int na = b.u2();
                    for (int k = 0; k < na; k++) b.u2();
                }
                break;
            }
            default:
                break;
        }
    }

    private static void need(ConstantPool p, int i, int tag, boolean zeroOk, Attr a, int off)
            throws ClassFormatException {
        if (i == 0 && zeroOk) return;
        if (p.tag(i) != tag)
            throw new ClassFormatException(off, a.name + " names constant pool entry " + i
                    + ", which is " + (p.valid(i) ? ConstantPool.tagName(p.tag(i))
                                                  : "out of range or unusable")
                    + " where " + ConstantPool.tagName(tag) + " is required");
    }

    /** The one variable shape attribute whose walk is three lines. */
    private static void checkBootstrapMethods(Attr a, int off) throws ClassFormatException {
        Buf b = new Buf(a.data);
        int n = b.u2();
        for (int i = 0; i < n; i++) {
            b.u2();                         // the method handle
            b.skip(2 * b.u2());             // its static arguments
        }
        if (b.remaining() != 0)
            throw new ClassFormatException(off, "BootstrapMethods holds " + n
                    + " methods and then " + b.remaining() + " bytes that are not part"
                    + " of any of them");
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
        for (Attr x : as) checkDebugTable(x, p, (int) clen, maxLocals);
        return new CodeAttr(maxStack, maxLocals, code, hs, as);
    }

    /**
     * The debug tables, which HotSpot checks while parsing even though
     * nothing in the verifier reads them.  Random corruption found five
     * distinct ClassFormatError messages in this one paragraph of JVMS
     * 4.7.12 and 4.7.13: a pc past the end of the code, a local index past
     * max_locals, a name or signature index that is not a Utf8, and a
     * signature that is not a field descriptor.  They are all the same
     * shape: an index into something, unchecked.
     */
    private static void checkDebugTable(Attr a, ConstantPool p, int codeLen, int maxLocals)
            throws ClassFormatException {
        boolean lvt = a.name.equals("LocalVariableTable");
        boolean lvtt = a.name.equals("LocalVariableTypeTable");
        if (!lvt && !lvtt && !a.name.equals("LineNumberTable")) return;
        Buf b = new Buf(a.data);
        int n = b.u2();
        for (int i = 0; i < n; i++) {
            int pc = b.u2();
            if (pc >= codeLen)
                throw new ClassFormatException(a.offset, a.name + " entry " + i
                        + " starts at pc " + pc + " and the code is " + codeLen
                        + " bytes");
            if (!lvt && !lvtt) {
                b.u2();                       // the line number itself
                continue;
            }
            int len = b.u2();
            if (pc + len > codeLen)
                throw new ClassFormatException(a.offset, a.name + " entry " + i
                        + " covers [" + pc + ", " + (pc + len) + ") and the code is "
                        + codeLen + " bytes");
            int ni = b.u2();
            int di = b.u2();
            int slot = b.u2();
            if (p.tag(ni) != ConstantPool.UTF8 || p.tag(di) != ConstantPool.UTF8)
                throw new ClassFormatException(a.offset, a.name + " entry " + i
                        + " names constant pool entries " + ni + " and " + di
                        + ", which are not both Utf8");
            // A LocalVariableTypeTable holds generic SIGNATURES, not
            // descriptors: "Ljava/util/List<Ljava/lang/String;>;" is a legal
            // entry there and not a legal field descriptor.  Checking it as
            // one rejected 2 of 3000 valid classes, which is the only reason
            // this comment exists.
            VType t = lvt ? VType.fromDescriptor(p.utf8(di)) : null;
            if (lvt && t == null)
                throw new ClassFormatException(a.offset, a.name + " entry " + i
                        + " has the descriptor V, which is not a field descriptor");
            if (slot >= maxLocals || (t != null && t.isTwoWord() && slot + 1 >= maxLocals))
                throw new ClassFormatException(a.offset, a.name + " entry " + i
                        + " is local " + slot + " and max_locals is " + maxLocals);
        }
    }

    // ---------------------------------------------------------------- flags

    public static final int ACC_PUBLIC = 0x0001, ACC_PRIVATE = 0x0002, ACC_PROTECTED = 0x0004,
            ACC_STATIC = 0x0008, ACC_FINAL = 0x0010, ACC_SUPER = 0x0020, ACC_SYNCHRONIZED = 0x0020,
            ACC_VOLATILE = 0x0040, ACC_BRIDGE = 0x0040, ACC_TRANSIENT = 0x0080,
            ACC_VARARGS = 0x0080, ACC_NATIVE = 0x0100, ACC_INTERFACE = 0x0200,
            ACC_ABSTRACT = 0x0400, ACC_STRICT = 0x0800, ACC_SYNTHETIC = 0x1000,
            ACC_ANNOTATION = 0x2000, ACC_ENUM = 0x4000, ACC_MODULE = 0x8000;

    public boolean isInterface() { return (accessFlags & ACC_INTERFACE) != 0; }

    /**
     * The return type of a method descriptor.
     *
     * Not indexOf(')'): a ')' inside an L...; class name is an ordinary
     * character of that name, and HotSpot accepts it.  This used to find
     * the wrong one and then fail to parse the remainder, so classv
     * rejected a class the JVM accepts, which random corruption found on a
     * descriptor reading (Ljdk/internal/classf)le/constantpool/ClassEntry;I)V.
     * The parameter list is walked the same way everything else walks it.
     */
    public static String descReturn(String desc) throws ClassFormatException {
        if (desc.isEmpty() || desc.charAt(0) != '(')
            throw new ClassFormatException(0, "method descriptor does not start with '(': "
                    + desc);
        int i = 1;
        int[] end = new int[1];
        while (i < desc.length() && desc.charAt(i) != ')') {
            VType.fromDescriptor(desc.substring(i), end);
            i += end[0];
        }
        if (i >= desc.length())
            throw new ClassFormatException(0, "method descriptor has no ')': " + desc);
        return desc.substring(i + 1);
    }
}
