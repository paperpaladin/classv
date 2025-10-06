package classv;

/**
 * The constant pool, JVMS 4.4.
 *
 * Stored as parallel arrays rather than an object per entry: the pool of a
 * large class has tens of thousands of entries and the only thing ever done
 * with most of them is a tag check and an index follow.  Index 0 is unused
 * and every long/double swallows the following index, both of which are in
 * the spec and both of which are easy to get wrong silently.
 */
public final class ConstantPool {
    public static final int UTF8 = 1, INTEGER = 3, FLOAT = 4, LONG = 5, DOUBLE = 6,
            CLASS = 7, STRING = 8, FIELDREF = 9, METHODREF = 10, INTERFACEMETHODREF = 11,
            NAMEANDTYPE = 12, METHODHANDLE = 15, METHODTYPE = 16, DYNAMIC = 17,
            INVOKEDYNAMIC = 18, MODULE = 19, PACKAGE = 20;

    private static final String[] TAGNAME = new String[21];
    static {
        TAGNAME[UTF8] = "Utf8";
        TAGNAME[INTEGER] = "Integer";
        TAGNAME[FLOAT] = "Float";
        TAGNAME[LONG] = "Long";
        TAGNAME[DOUBLE] = "Double";
        TAGNAME[CLASS] = "Class";
        TAGNAME[STRING] = "String";
        TAGNAME[FIELDREF] = "Fieldref";
        TAGNAME[METHODREF] = "Methodref";
        TAGNAME[INTERFACEMETHODREF] = "InterfaceMethodref";
        TAGNAME[NAMEANDTYPE] = "NameAndType";
        TAGNAME[METHODHANDLE] = "MethodHandle";
        TAGNAME[METHODTYPE] = "MethodType";
        TAGNAME[DYNAMIC] = "Dynamic";
        TAGNAME[INVOKEDYNAMIC] = "InvokeDynamic";
        TAGNAME[MODULE] = "Module";
        TAGNAME[PACKAGE] = "Package";
    }

    public static String tagName(int t) {
        return (t >= 0 && t < TAGNAME.length && TAGNAME[t] != null) ? TAGNAME[t] : ("tag" + t);
    }

    public final int count;
    final int[] tag;
    final int[] x;
    final int[] y;
    final long[] bits;
    final String[] str;
    final byte[][] raw;
    /** Set where a Utf8 entry's bytes are not well formed modified UTF-8. */
    final boolean[] badUtf8;

    ConstantPool(int count) {
        this.count = count;
        tag = new int[count];
        x = new int[count];
        y = new int[count];
        bits = new long[count];
        str = new String[count];
        raw = new byte[count][];
        badUtf8 = new boolean[count];
    }

    public int tag(int i) {
        return (i > 0 && i < count) ? tag[i] : 0;
    }

    public boolean valid(int i) {
        return i > 0 && i < count && tag[i] != 0;
    }

    public int x(int i) { return x[i]; }
    public int y(int i) { return y[i]; }
    public long bits(int i) { return bits[i]; }

    private void require(int i, int t) throws ClassFormatException {
        if (i <= 0 || i >= count)
            throw new ClassFormatException(0, "constant pool index " + i + " out of range 1.."
                    + (count - 1));
        if (tag[i] == 0)
            throw new ClassFormatException(0, "constant pool index " + i
                    + " names the unusable second half of a long/double entry");
        if (tag[i] != t)
            throw new ClassFormatException(0, "constant pool index " + i + " is "
                    + tagName(tag[i]) + " where " + tagName(t) + " is required");
    }

    public String utf8(int i) throws ClassFormatException {
        require(i, UTF8);
        return str[i];
    }

    public byte[] utf8Raw(int i) throws ClassFormatException {
        require(i, UTF8);
        return raw[i];
    }

    /** The internal name a CONSTANT_Class names, e.g. "java/lang/String" or "[I". */
    public String className(int i) throws ClassFormatException {
        require(i, CLASS);
        return utf8(x[i]);
    }

    /** The two halves of a CONSTANT_NameAndType. */
    public String ntName(int i) throws ClassFormatException {
        require(i, NAMEANDTYPE);
        return utf8(x[i]);
    }

    public String ntDesc(int i) throws ClassFormatException {
        require(i, NAMEANDTYPE);
        return utf8(y[i]);
    }

    /** For a Fieldref/Methodref/InterfaceMethodref: the owning class's name. */
    public String refOwner(int i) throws ClassFormatException {
        return className(x[i]);
    }

    public String refName(int i) throws ClassFormatException {
        return ntName(y[i]);
    }

    public String refDesc(int i) throws ClassFormatException {
        return ntDesc(y[i]);
    }

    /**
     * The operand text javap prints in the third column of its constant pool
     * dump, so the two can be compared entry by entry.  Deliberately not a
     * resolved description: resolving would hide an index read from the wrong
     * place behind a name that happens to look right.
     */
    public String operandText(int i) {
        switch (tag[i]) {
            case UTF8: return str[i];
            case INTEGER: return Integer.toString((int) bits[i]);
            case FLOAT: return floatText(Float.intBitsToFloat((int) bits[i]));
            case LONG: return Long.toString(bits[i]) + "l";
            case DOUBLE: return doubleText(Double.longBitsToDouble(bits[i]));
            case CLASS: case STRING: case METHODTYPE: case MODULE: case PACKAGE:
                return "#" + x[i];
            case FIELDREF: case METHODREF: case INTERFACEMETHODREF:
                return "#" + x[i] + ".#" + y[i];
            case NAMEANDTYPE:
                return "#" + x[i] + ":#" + y[i];
            case METHODHANDLE:
                return x[i] + ":#" + y[i];
            case DYNAMIC: case INVOKEDYNAMIC:
                return "#" + x[i] + ":#" + y[i];
            default:
                return "?";
        }
    }

    /** javap's rendering: Float.toString with an 'f', and the three specials spelled out. */
    static String floatText(float f) {
        if (Float.isNaN(f)) return "NaNf";
        if (f == Float.POSITIVE_INFINITY) return "Infinityf";
        if (f == Float.NEGATIVE_INFINITY) return "-Infinityf";
        return Float.toString(f) + "f";
    }

    static String doubleText(double d) {
        if (Double.isNaN(d)) return "NaNd";
        if (d == Double.POSITIVE_INFINITY) return "Infinityd";
        if (d == Double.NEGATIVE_INFINITY) return "-Infinityd";
        return Double.toString(d) + "d";
    }

    /**
     * JVMS 4.4.7 modified UTF-8.  Not java.io.DataInput's decoder: that one
     * throws on malformed input, and here a malformed constant is a finding to
     * report rather than an exception to propagate.  The flag is returned
     * alongside so the caller can say which entry was wrong.
     */
    static String decodeUtf8(byte[] b, boolean[] badOut) {
        StringBuilder sb = new StringBuilder(b.length);
        boolean bad = false;
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xff;
            if (c == 0 || c >= 0xf0) {
                // 0x00 and 0xf0..0xff may not appear at all (JVMS 4.4.7).
                bad = true;
                sb.append('\uFFFD');
                i++;
            } else if (c < 0x80) {
                sb.append((char) c);
                i++;
            } else if ((c & 0xe0) == 0xc0) {
                if (i + 1 >= b.length || (b[i + 1] & 0xc0) != 0x80) {
                    bad = true; sb.append('\uFFFD'); i++;
                } else {
                    int v = ((c & 0x1f) << 6) | (b[i + 1] & 0x3f);
                    // Only 0xc0 0x80 may encode a value below 0x80.
                    if (v < 0x80 && !(c == 0xc0 && (b[i + 1] & 0xff) == 0x80)) bad = true;
                    sb.append((char) v);
                    i += 2;
                }
            } else if ((c & 0xf0) == 0xe0) {
                if (i + 2 >= b.length || (b[i + 1] & 0xc0) != 0x80 || (b[i + 2] & 0xc0) != 0x80) {
                    bad = true; sb.append('\uFFFD'); i++;
                } else {
                    int v = ((c & 0x0f) << 12) | ((b[i + 1] & 0x3f) << 6) | (b[i + 2] & 0x3f);
                    if (v < 0x800) bad = true;
                    sb.append((char) v);
                    i += 3;
                }
            } else {
                bad = true;
                sb.append('\uFFFD');
                i++;
            }
        }
        if (badOut != null) badOut[0] = bad;
        return sb.toString();
    }
}
