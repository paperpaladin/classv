package classv;

/**
 * A verification type, JVMS 4.10.1.2.
 *
 * The spec's lattice is written as Prolog terms: top, oneWord, twoWord, int,
 * float, long, double, reference, uninitialized, uninitializedThis,
 * uninitialized(Offset), null, class(N, L), arrayOf(T).  Only the ones a
 * frame can actually hold are constructed here; oneWord, twoWord and
 * reference exist as the interior nodes isAssignable walks through and are
 * never stored in a frame.
 *
 * Array types are kept as a descriptor string rather than a nested object:
 * arrayOf(arrayOf(int)) is "[[I", which is also exactly what a CONSTANT_Class
 * holds for an array, so the two representations never need converting.
 */
public final class VType {
    public enum Kind {
        TOP, INTEGER, FLOAT, LONG, DOUBLE, NULL, UNINIT_THIS, UNINIT, OBJECT,
        // The interior nodes of the lattice.  Never in a frame; used by
        // isAssignable and by the instruction rules that accept a category.
        ONE_WORD, TWO_WORD, REFERENCE
    }

    public final Kind kind;
    /** For OBJECT: the internal name, e.g. "java/lang/String" or "[I". */
    public final String name;
    /** For UNINIT: the bci of the new instruction that made it. */
    public final int offset;

    private VType(Kind k, String name, int offset) {
        this.kind = k;
        this.name = name;
        this.offset = offset;
    }

    public static final VType TOP = new VType(Kind.TOP, null, 0);
    public static final VType INTEGER = new VType(Kind.INTEGER, null, 0);
    public static final VType FLOAT = new VType(Kind.FLOAT, null, 0);
    public static final VType LONG = new VType(Kind.LONG, null, 0);
    public static final VType DOUBLE = new VType(Kind.DOUBLE, null, 0);
    public static final VType NULL = new VType(Kind.NULL, null, 0);
    public static final VType UNINIT_THIS = new VType(Kind.UNINIT_THIS, null, 0);
    public static final VType ONE_WORD = new VType(Kind.ONE_WORD, null, 0);
    public static final VType TWO_WORD = new VType(Kind.TWO_WORD, null, 0);
    public static final VType REFERENCE = new VType(Kind.REFERENCE, null, 0);
    public static final VType OBJECT_CLASS = object("java/lang/Object");

    public static VType object(String internalName) {
        return new VType(Kind.OBJECT, internalName, 0);
    }

    public static VType uninit(int offset) {
        return new VType(Kind.UNINIT, null, offset);
    }

    public boolean isTwoWord() { return kind == Kind.LONG || kind == Kind.DOUBLE; }

    public boolean isArray() { return kind == Kind.OBJECT && name.charAt(0) == '['; }

    public boolean isReference() {
        return kind == Kind.OBJECT || kind == Kind.NULL
                || kind == Kind.UNINIT || kind == Kind.UNINIT_THIS;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VType)) return false;
        VType t = (VType) o;
        if (kind != t.kind) return false;
        if (kind == Kind.OBJECT) return name.equals(t.name);
        if (kind == Kind.UNINIT) return offset == t.offset;
        return true;
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + (name != null ? name.hashCode() : offset);
    }

    /** The rendering javap uses in its StackMapTable dump, minus its quoting. */
    @Override
    public String toString() {
        switch (kind) {
            case TOP: return "top";
            case INTEGER: return "int";
            case FLOAT: return "float";
            case LONG: return "long";
            case DOUBLE: return "double";
            case NULL: return "null";
            case UNINIT_THIS: return "this";
            case UNINIT: return "uninitialized " + offset;
            case OBJECT: return "class " + name;
            default: return kind.toString().toLowerCase(java.util.Locale.ROOT);
        }
    }

    // ------------------------------------------------------------ descriptors

    /**
     * The verification type a field or parameter descriptor denotes.  byte,
     * char, short and boolean all become int: JVMS 4.10.1.2 has no narrower
     * integral type, which is why a verifier cannot catch storing an int into
     * a boolean array.
     */
    public static VType fromDescriptor(String d, int[] end) throws ClassFormatException {
        if (d.isEmpty()) throw new ClassFormatException(0, "empty type descriptor");
        char c = d.charAt(0);
        switch (c) {
            case 'B': case 'C': case 'S': case 'Z': case 'I':
                end[0] = 1; return INTEGER;
            case 'F': end[0] = 1; return FLOAT;
            case 'J': end[0] = 1; return LONG;
            case 'D': end[0] = 1; return DOUBLE;
            case 'V': end[0] = 1; return null;
            case 'L': {
                int semi = d.indexOf(';');
                if (semi < 0)
                    throw new ClassFormatException(0, "object descriptor has no ';': " + d);
                end[0] = semi + 1;
                return object(d.substring(1, semi));
            }
            case '[': {
                int i = 0;
                while (i < d.length() && d.charAt(i) == '[') i++;
                if (i > 255)
                    throw new ClassFormatException(0, "array descriptor has " + i
                            + " dimensions; 255 is the limit");
                if (i >= d.length())
                    throw new ClassFormatException(0, "array descriptor has no element type: " + d);
                int[] e = new int[1];
                VType elem = fromDescriptor(d.substring(i), e);
                if (elem == null)
                    throw new ClassFormatException(0, "array of void: " + d);
                end[0] = i + e[0];
                return object(d.substring(0, end[0]));
            }
            default:
                throw new ClassFormatException(0, "not a type descriptor: " + d);
        }
    }

    public static VType fromDescriptor(String d) throws ClassFormatException {
        int[] e = new int[1];
        VType t = fromDescriptor(d, e);
        if (e[0] != d.length())
            throw new ClassFormatException(0, "trailing bytes in type descriptor: " + d);
        return t;
    }

    /**
     * The verification type a CONSTANT_Class names.  "[I" is an array type;
     * anything else is a class name, NOT a descriptor, which is the one
     * asymmetry in the class file format that catches everybody once.
     */
    public static VType fromClassName(String n) throws ClassFormatException {
        if (n.isEmpty()) throw new ClassFormatException(0, "empty class name");
        if (n.charAt(0) == '[') {
            fromDescriptor(n);      // for the validity check only
            return object(n);
        }
        return object(n);
    }

    /** The element type of an array type, or null if this is not an array. */
    public VType componentType() throws ClassFormatException {
        if (!isArray()) return null;
        return fromDescriptor(name.substring(1));
    }
}
