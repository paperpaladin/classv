package classv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the subtype questions get answered, and the rules of JVMS 4.10.1.2
 * that need them.
 *
 * The type checker is written as if the class hierarchy were a given, and in
 * the real JVM it is: the verifier asks the defining loader, which may load
 * classes to answer.  Here the sources are, in order,
 *
 *   1. class files under the roots given with --cp, read with our own parser
 *      and never loaded, so nothing is initialised and no code runs;
 *   2. reflection on the running JVM, which is the same hierarchy HotSpot's
 *      own verifier would consult for these names;
 *
 * and if neither can answer, the result is a Refusal and not a guess.  That
 * is the whole reason Refusal exists: a verifier that silently assumed "not a
 * subtype" would reject valid code, and one that assumed "is a subtype" would
 * accept invalid code, and both would do it quietly.
 */
public final class Hierarchy {

    private static final String OBJECT = "java/lang/Object";

    /** What the checker needs to know about one class. */
    static final class Info {
        final boolean isInterface;
        final boolean isFinal;
        final String superName;      // null for java/lang/Object and interfaces with no super
        Info(boolean isInterface, boolean isFinal, String superName) {
            this.isInterface = isInterface;
            this.isFinal = isFinal;
            this.superName = superName;
        }
    }

    private final List<Path> roots = new ArrayList<>();
    private final Map<String, Info> cache = new HashMap<>();
    private final Map<String, Info> local = new HashMap<>();
    /** Counted so a suite can assert the lookup path actually ran. */
    public int fileLookups, reflectLookups, misses;

    public void addRoot(String dir) { roots.add(Path.of(dir)); }

    /**
     * Register the class being verified itself.  It need not be on any root,
     * and in the normal case (verifying a file by path) it is not.
     */
    public void declare(ClassFile cf) {
        local.put(cf.thisClass, new Info(cf.isInterface(),
                (cf.accessFlags & ClassFile.ACC_FINAL) != 0, cf.superClass));
    }

    Info info(String name) throws Refusal {
        Info i = local.get(name);
        if (i != null) return i;
        i = cache.get(name);
        if (i != null) return i;
        i = fromFile(name);
        if (i == null) i = fromReflection(name);
        if (i == null) {
            misses++;
            throw new Refusal("cannot resolve class " + name
                    + "; it is on no --cp root and not visible to this JVM");
        }
        cache.put(name, i);
        return i;
    }

    private Info fromFile(String name) {
        for (Path r : roots) {
            Path p = r.resolve(name + ".class");
            if (!Files.isRegularFile(p)) continue;
            try {
                ClassFile cf = ClassFile.parse(Files.readAllBytes(p));
                fileLookups++;
                return new Info(cf.isInterface(),
                        (cf.accessFlags & ClassFile.ACC_FINAL) != 0, cf.superClass);
            } catch (Exception e) {
                // A root holding a broken file is not a reason to claim the
                // class does not exist; fall through to the next source.
                return null;
            }
        }
        return null;
    }

    private Info fromReflection(String name) {
        try {
            Class<?> c = Class.forName(name.replace('/', '.'), false,
                    Hierarchy.class.getClassLoader());
            reflectLookups++;
            Class<?> s = c.getSuperclass();
            if (s == null && c.isInterface()) {
                // Reflection says an interface has no superclass.  The class
                // file says java/lang/Object, and so does JVMS 4.1, and the
                // verifier's isJavaSubclassOf walks super_class.  Taking
                // reflection at its word made every interface typed value
                // fail to be assignable to Object, which is most of
                // java.lang.String.
                return new Info(true, false, OBJECT);
            }
            boolean fin = java.lang.reflect.Modifier.isFinal(c.getModifiers());
            return new Info(c.isInterface(), fin,
                    s == null ? null : s.getName().replace('.', '/'));
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    public boolean isInterface(String name) throws Refusal { return info(name).isInterface; }

    public boolean isFinal(String name) throws Refusal { return info(name).isFinal; }

    /** The superclass chain, JVMS 4.10.1.2 isJavaSubclassOf. */
    public boolean isJavaSubclassOf(String sub, String sup) throws Refusal {
        if (sub.equals(sup)) return true;
        String c = sub;
        for (int guard = 0; guard < 4096; guard++) {
            Info i = info(c);
            if (i.superName == null) return false;
            if (i.superName.equals(sup)) return true;
            c = i.superName;
        }
        throw new Refusal("superclass chain of " + sub + " does not terminate");
    }

    // ------------------------------------------------------- JVMS 4.10.1.2



    /**
     * isAssignable(from, to).  Written in the order the spec writes it so the
     * two can be read side by side; the interior nodes oneWord, twoWord and
     * reference are folded into the switch rather than being searched for.
     */
    public boolean isAssignable(VType from, VType to) throws Refusal, ClassFormatException {
        if (from.equals(to)) return true;
        switch (to.kind) {
            case TOP:
                return true;                       // everything is assignable to top
            case ONE_WORD:
                return !from.isTwoWord() && from.kind != VType.Kind.TOP;
            case TWO_WORD:
                return from.isTwoWord();
            case REFERENCE:
                return from.isReference();
            case INTEGER: case FLOAT: case LONG: case DOUBLE:
                return false;                      // only X to X, handled above
            case NULL: case UNINIT_THIS:
                return false;
            case UNINIT:
                return false;
            case OBJECT:
                break;
            default:
                return false;
        }
        // to is an object or array type.
        switch (from.kind) {
            case NULL:
                // null is assignable to every reference type.
                return true;
            case OBJECT:
                return isJavaAssignable(from, to);
            case UNINIT: case UNINIT_THIS:
                // An uninitialized type is a reference, and reference is
                // assignable only to oneWord and top, never to a class type.
                return false;
            default:
                return false;
        }
    }

    /** isJavaAssignable, JVMS 4.10.1.2.  Both sides are object or array types. */
    public boolean isJavaAssignable(VType from, VType to) throws Refusal, ClassFormatException {
        boolean fromArray = from.isArray();
        boolean toArray = to.isArray();
        if (!toArray) {
            // Any reference is assignable to any interface type.  The verifier
            // does not check interface implementation at all; the invokeinterface
            // call site checks it at run time instead.  Being stricter here is
            // the single most common way to write a verifier that rejects
            // perfectly good code.
            if (isInterface(to.name)) return true;
            if (fromArray) return to.name.equals(OBJECT);
            return isJavaSubclassOf(from.name, to.name);
        }
        if (!fromArray) return false;
        // The element comparison is on DESCRIPTORS, not on verification
        // types.  byte, char, short, boolean and int are all the verification
        // type int, so comparing verification types makes [C assignable to
        // [I, which the JVM does not allow and Class.isAssignableFrom caught
        // on the first run: 40 pairs, every one of them a primitive array.
        String fd = from.name.substring(1);
        String td = to.name.substring(1);
        boolean fp = fd.charAt(0) != 'L' && fd.charAt(0) != '[';
        boolean tp = td.charAt(0) != 'L' && td.charAt(0) != '[';
        if (fp || tp) return fp && tp && fd.equals(td);
        return isJavaAssignable(VType.fromDescriptor(fd), VType.fromDescriptor(td));
    }
}
