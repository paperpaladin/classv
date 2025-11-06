// Compare classv's isJavaAssignable against java.lang.Class.isAssignableFrom.
//
// The JVM's own reflective subtype relation is an outside authority for the
// part of JVMS 4.10.1.2 that is a question about the class hierarchy.  It is
// NOT an authority for the whole rule: the verifier deliberately answers
// "yes" for every interface target without checking anything, and defers the
// real check to the invokeinterface call site.  So the comparison is made on
// non-interface targets, and the interface divergence is counted separately
// and asserted to be non-empty -- if it were empty, the permissive rule would
// be unreachable and the comparison would prove nothing about it.
//
// Pairs are not random.  A random pair of JDK classes is almost always "not
// assignable", and a comparison whose answer is the same constant 99% of the
// time is a weak instrument however many pairs it runs.  So the pairs are
// built along real superclass chains, in both directions, plus arrays of
// each, plus every pair of the eight primitive array types.
import classv.Hierarchy;
import classv.VType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AssignCmp {

    static int pass, fail, trueCases, falseCases, ifaceDiverge, ifaceAgree, refusals;
    static final List<String> FAILURES = new ArrayList<>();

    static void check(Hierarchy h, VType from, VType to, boolean want, String why) {
        boolean got;
        try {
            got = h.isJavaAssignable(from, to);
        } catch (Exception e) {
            refusals++;
            return;
        }
        if (want) trueCases++; else falseCases++;
        if (got == want) {
            pass++;
        } else {
            fail++;
            if (FAILURES.size() < 20)
                FAILURES.add(why + ": classv says " + got + ", reflection says " + want
                        + "  (" + from + " -> " + to + ")");
        }
    }

    static String internal(Class<?> c) {
        return c.isArray() ? c.getName().replace('.', '/') : c.getName().replace('.', '/');
    }

    public static void main(String[] args) throws Exception {
        List<String> names = Files.readAllLines(Path.of(args[0]));
        Hierarchy h = new Hierarchy();
        List<Class<?>> cls = new ArrayList<>();
        for (String n : names) {
            if (n.isEmpty()) continue;
            try {
                cls.add(Class.forName(n, false, AssignCmp.class.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError e) {
                // not our business here
            }
        }
        if (cls.size() < 50) {
            System.out.println("FAIL AssignCmp got only " + cls.size() + " classes");
            System.out.println("ASSIGN_PASS 0");
            System.out.println("ASSIGN_FAIL 1");
            return;
        }

        List<Class<?>> ifaces = new ArrayList<>();
        for (Class<?> c : cls) if (c.isInterface()) ifaces.add(c);

        for (int i = 0; i < cls.size(); i++) {
            Class<?> c = cls.get(i);
            if (c.isPrimitive()) continue;
            // along the superclass chain, both directions
            for (Class<?> s = c; s != null; s = s.getSuperclass()) {
                pair(h, c, s);
                pair(h, s, c);
                pair(h, array(c), array(s));
                pair(h, array(s), array(c));
            }
            // a cross pair, to get some unrelated types in
            // Every reference type is assignable to Object, including an
            // interface type, whose getSuperclass() is null.  That case was
            // missing from the first version of this comparison and the hole
            // it left made every interface typed value fail to be assignable
            // to Object, which java/lang/String.contentEquals found instead.
            pair(h, c, Object.class);
            Class<?> other = cls.get((i * 7919 + 13) % cls.size());
            pair(h, c, other);
            pair(h, array(c), array(other));
            pair(h, array(c), Object.class);
            pair(h, array(c), Cloneable.class);
            // Interfaces the class does implement and interfaces it does
            // not: the verifier must say yes to both, and the second group is
            // the one that shows the permissive rule is actually reached.
            for (Class<?> ifc : c.getInterfaces()) ifacePair(h, c, ifc);
            if (!ifaces.isEmpty()) {
                ifacePair(h, c, ifaces.get((i * 104729 + 7) % ifaces.size()));
                ifacePair(h, c, ifaces.get((i * 7919 + 31) % ifaces.size()));
                ifacePair(h, array(c), array(ifaces.get(i % ifaces.size())));
            }
        }

        Class<?>[] prims = { int[].class, long[].class, float[].class, double[].class,
                byte[].class, char[].class, short[].class, boolean[].class };
        for (Class<?> a : prims)
            for (Class<?> b : prims) {
                pair(h, a, b);
                pair(h, array(a), array(b));
            }

        for (String f : FAILURES) System.out.println("FAIL " + f);
        System.out.println("--- isAssignable vs Class.isAssignableFrom: "
                + (trueCases + falseCases)
                + " pairs on non-interface targets, " + trueCases + " assignable, "
                + falseCases + " not, " + refusals + " refused; interface targets: "
                + ifaceDiverge + " where the verifier is more permissive than reflection, "
                + ifaceAgree + " where both say yes");
        boolean vacuous = trueCases < 100 || falseCases < 100 || ifaceDiverge < 10;
        if (vacuous) {
            System.out.println("FAIL the comparison is vacuous: " + trueCases
                    + " true, " + falseCases + " false, " + ifaceDiverge + " divergent");
            fail++;
        }
        System.out.println("ASSIGN_PASS " + pass);
        System.out.println("ASSIGN_FAIL " + fail);
    }

    static Class<?> array(Class<?> c) {
        return java.lang.reflect.Array.newInstance(c, 0).getClass();
    }

    /** The element type under any number of array dimensions. */
    static Class<?> leaf(Class<?> c) {
        while (c.isArray()) c = c.getComponentType();
        return c;
    }

    static void pair(Hierarchy h, Class<?> from, Class<?> to) {
        // Not reflection's question to answer, and that includes an array of
        // an interface: isJavaAssignable(arrayOf(X), arrayOf(Y)) recurses into
        // the element types, so [LFoo; -> [LRunnable; is true for the verifier
        // and false for reflection, for exactly the same reason Foo ->
        // Runnable is.  85 of the first 8854 pairs were that case, and
        // scoring them as failures would have been scoring the spec.
        if (leaf(to).isInterface()) return;
        if (from.isPrimitive() || to.isPrimitive()) return;
        check(h, VType.object(internal(from)), VType.object(internal(to)),
              to.isAssignableFrom(from), from.getName() + " -> " + to.getName());
    }

    /**
     * An interface target.  The verifier must say yes; reflection is asked
     * only so that the two answers can be counted apart.
     */
    static void ifacePair(Hierarchy h, Class<?> from, Class<?> ifc) {
        if (from.isPrimitive() || leaf(from).isPrimitive()) return;
        boolean got;
        try {
            got = h.isJavaAssignable(VType.object(internal(from)), VType.object(internal(ifc)));
        } catch (Exception e) {
            refusals++;
            return;
        }
        if (!got) {
            fail++;
            if (FAILURES.size() < 20)
                FAILURES.add("interface target " + ifc.getName() + " from " + from.getName()
                        + ": classv says no, the verifier rule says yes unconditionally");
            return;
        }
        pass++;
        if (ifc.isAssignableFrom(from)) ifaceAgree++; else ifaceDiverge++;
    }
}
