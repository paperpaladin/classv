// A source file whose only job is to make javac emit as many different stack
// map shapes as it can be persuaded to.  Everything here compiles under
// --release 8 as well as 21, because the point is to compile the SAME source
// at every release and compare the class files that come out: string
// concatenation alone changes from StringBuilder to an invokedynamic against
// StringConcatFactory, which is a different instruction, a different constant
// pool entry and a different frame.
//
// Nothing is clever and nothing is useful.  Each method exists because it
// forces a shape: a branch target that needs a frame, a two word local, an
// exception range, a type that only a merge can name.
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public class Shapes {

    interface Named {
        String name();
        default String greet() { return "hello " + name(); }
        static Named of(String s) { return () -> s; }
    }

    enum Colour { RED, GREEN, BLUE }

    static class Box<T> implements Supplier<T> {
        private final T value;
        Box(T value) { this.value = value; }
        @Override public T get() { return value; }
    }

    static final List<String> STATICS = new ArrayList<>();

    static {
        STATICS.add("one");
        STATICS.add("two");
    }

    private int counter;
    private long total;
    private double ratio;

    // An inner class capturing its outer instance: javac assigns this$0
    // before calling super(), which is the putfield rule for an
    // uninitializedThis and the reason that rule exists.
    class Inner {
        int seen;
        Inner() { seen = counter; }
        int outerCounter() { return counter; }
    }

    // A merge where the two branches have different reference types, so the
    // frame at the join has to name their common supertype.
    static Object merge(boolean b) {
        Object o;
        if (b) {
            o = new StringBuilder("x");
        } else {
            o = new ArrayList<String>();
        }
        return o;
    }

    // A dense switch: tableswitch, with a frame at every case.
    static int dense(int n) {
        switch (n) {
            case 0: return 10;
            case 1: return 11;
            case 2: return 12;
            case 3: return 13;
            default: return -1;
        }
    }

    // A sparse one: lookupswitch.
    static int sparse(int n) {
        switch (n) {
            case 1: return 1;
            case 100: return 2;
            case 100000: return 3;
            default: return 0;
        }
    }

    // Switch on a string is two switches and a synthetic local.
    static int onString(String s) {
        switch (s) {
            case "alpha": return 1;
            case "beta": return 2;
            case "gamma": return 3;
            default: return 0;
        }
    }

    // Switch on an enum goes through a synthetic $SwitchMap class.
    static int onEnum(Colour c) {
        switch (c) {
            case RED: return 1;
            case GREEN: return 2;
            default: return 3;
        }
    }

    // Two word values in locals and on the stack, and the top slots that go
    // with them.
    double arithmetic(long a, double b) {
        long x = a * 3L + (a >> 2);
        double y = b / 2.0 - Math.sqrt(b);
        total += x;
        ratio = y;
        return x + y;
    }

    static int widenings(int i) {
        byte b = (byte) i;
        char c = (char) i;
        short s = (short) i;
        float f = i;
        return b + c + s + (int) f;
    }

    // Arrays, including a multianewarray and an array of arrays.
    static int[][] grid(int n) {
        int[][] g = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                g[i][j] = i * j;
            }
        }
        return g;
    }

    static Object[] objects() {
        return new Object[] { new int[2], new String[1], "s", null };
    }

    // An exception table with several rows, a finally duplicated into each
    // path, and a multi catch whose verification type is a merge.
    static String risky(int n) throws IOException {
        StringBuilder sb = new StringBuilder();
        try {
            if (n == 0) throw new IOException("zero");
            if (n == 1) throw new IllegalStateException("one");
            sb.append(n);
        } catch (IOException | IllegalStateException e) {
            sb.append(e.getMessage());
        } finally {
            sb.append('.');
        }
        return sb.toString();
    }

    static int nested(int n) {
        int r = 0;
        try {
            try {
                r = 10 / n;
            } finally {
                r += 1;
            }
        } catch (ArithmeticException e) {
            r = -1;
        }
        return r;
    }

    static void resources(Closeable a, Closeable b) throws IOException {
        try (Closeable x = a; Closeable y = b) {
            x.toString();
            y.toString();
        }
    }

    synchronized int locked() {
        synchronized (STATICS) {
            counter++;
        }
        return counter;
    }

    // Lambdas and a method reference: invokedynamic, a bootstrap method
    // table, and synthetic methods whose frames nobody writes by hand.
    static List<String> lambdas(List<String> in) {
        List<String> out = new ArrayList<>();
        in.forEach(s -> out.add(s.trim()));
        in.stream().map(String::toUpperCase).forEach(out::add);
        Function<String, Integer> f = String::length;
        out.add(String.valueOf(f.apply("four")));
        return out;
    }

    // String concatenation: StringBuilder under --release 8, an
    // invokedynamic against StringConcatFactory from 9 onwards.
    String describe(int n, Object o) {
        return "n=" + n + " o=" + o + " c=" + counter + " t=" + total;
    }

    static int varargs(String first, Object... rest) {
        return first.length() + rest.length;
    }

    // A labelled break out of two loops, and a continue.
    static int labelled(int[] xs) {
        int found = -1;
        outer:
        for (int i = 0; i < xs.length; i++) {
            for (int j = i + 1; j < xs.length; j++) {
                if (xs[j] == 0) continue;
                if (xs[i] == xs[j]) {
                    found = i;
                    break outer;
                }
            }
        }
        return found;
    }

    static boolean kinds(Object o) {
        if (o instanceof String) {
            return ((String) o).isEmpty();
        } else if (o instanceof List) {
            return ((List<?>) o).isEmpty();
        }
        return o == null;
    }

    static String anonymous(final String tag) {
        Named n = new Named() {
            @Override public String name() { return tag; }
        };
        return n.greet();
    }

    static String boxes() {
        Box<String> b = new Box<>("b");
        Supplier<String> s = b;
        return b.get() + s.get();
    }

    static String all(int n) throws IOException {
        Shapes sh = new Shapes();
        Shapes.Inner in = sh.new Inner();
        return sh.describe(n, Arrays.toString(grid(2)))
                + dense(n) + sparse(n) + onString("beta") + onEnum(Colour.GREEN)
                + widenings(n) + sh.arithmetic(n, n) + risky(2) + nested(1)
                + locked0(sh) + lambdas(STATICS) + varargs("v", 1, 2)
                + labelled(new int[] { 1, 2, 1 }) + kinds("k") + anonymous("a")
                + boxes() + in.outerCounter() + Arrays.toString(objects());
    }

    private static int locked0(Shapes sh) { return sh.locked(); }

    public static void main(String[] args) throws IOException {
        System.out.println(all(args.length));
    }
}
