// A fixture with one of most things the type checker has a rule for, so that
// a mutation has somewhere to land.  Compiled by the suite with javac at
// several --release levels; nothing here is clever, it only has to be varied.
import java.util.ArrayList;
import java.util.List;

public class Fix {
    int field;
    static long slong;
    static double sdouble;
    String name = "x";

    public Fix(int n) {
        this.field = n;
    }

    public Fix() {
        this(3);
    }

    static int branches(int a, int b) {
        int s = 0;
        for (int i = 0; i < a; i++) {
            if (i % 2 == 0) {
                s += i;
            } else {
                s -= b;
            }
        }
        return s;
    }

    static long arith(long a, double b, float c, int d) {
        long r = a + (long) b;
        r += (long) (c * d);
        return r ^ (r >>> 3);
    }

    static Object arrays(int n) {
        int[] a = new int[n];
        String[][] s = new String[n][n];
        byte[] bs = new byte[4];
        a[0] = bs[1];
        s[0][0] = "y";
        return new Object[] { a, s, bs };
    }

    static String tryCatch(List<String> l) {
        try {
            return l.get(0);
        } catch (IndexOutOfBoundsException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        } finally {
            l.clear();
        }
    }

    static synchronized void sync(Object o) {
        synchronized (o) {
            o.hashCode();
        }
    }

    static int switches(int x) {
        switch (x) {
            case 1: return 10;
            case 2: return 20;
            case 1000: return 30;
            default: return 0;
        }
    }

    static List<String> made() {
        List<String> l = new ArrayList<>();
        l.add(new String("a"));
        return l;
    }

    static Runnable lambda(int captured) {
        return () -> System.out.print(captured);
    }

    static String concat(String a, int b) {
        return a + b + "!" + slong + sdouble;
    }

    static Object casts(Object o) {
        if (o instanceof String) {
            return ((String) o).length();
        }
        return (CharSequence) o;
    }

    public static void main(String[] args) {
        System.out.print(branches(3, 1) + switches(2) + made().size()
                + arith(1L, 2.0, 3.0f, 4) + arrays(2).hashCode()
                + tryCatch(made()) + concat("a", 1) + casts("z"));
        sync(args);
        lambda(1).run();
    }
}
