// The JVM oracle.
//
// Ask the real HotSpot verifier whether it accepts a class file, one class at
// a time, and print one line per class.  This is the strongest oracle
// available to classv, because it is the artefact classv is a model of.
//
// Two things about HotSpot matter here and both were measured, not assumed:
//
//  1. ClassLoader.defineClass does NOT verify.  It returns a perfectly good
//     Class object for a class whose bytecode is nonsense.  Verification
//     happens at link time, and getDeclaredMethods() is the cheapest way to
//     force linking without also running the static initialiser.
//
//  2. Classes loaded by the boot loader are not verified at all unless
//     -Xverify:all is on (BytecodeVerificationLocal defaults to false).  A
//     deliberately broken java.lang class patched in with --patch-module
//     links cleanly without the flag and throws VerifyError with it.
//
// Modes:
//   system   ask about the class the running JDK already has under that name.
//            The corpus is this JDK's own module image, so the bytes are the
//            same bytes, and there is no loader split to go wrong.  Needs
//            -Xverify:all, because most of the image is boot loaded.
//   define   define each file in one shared child-first ClassLoader and force
//            linking.  The only path that can be asked about a file the JDK
//            does not already have, so this is the one the mutation corpus
//            and the javac corpora use.  It cannot touch java.* at all.
//
// Output, one line per input, tab separated:
//   ACCEPT      path
//   VERIFY      path  message       VerifyError
//   FORMAT      path  message       ClassFormatError, UnsupportedClassVersionError
//   OTHER       path  message       a LinkageError about THIS class that is
//                                   neither of those, e.g. ClassCircularityError
//   ORACLE_NA   path  reason        the JVM could not be asked
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Probe {

    /**
     * A loader that prefers the corpus to its parent.
     *
     * One loader per class does not work.  A runtime package is identified by
     * (loader, package name), so a corpus class whose superclass is package
     * private in the same package fails with IllegalAccessError when the
     * superclass comes from the boot loader and the subclass does not: 153 of
     * the first 400 java.base classes failed that way, and no --add-exports
     * can fix it, because it is not a module access check.  Defining the whole
     * corpus in one loader puts both halves in the same runtime package.
     *
     * Child first, except for java.*, which a user loader may not define at
     * all.  Those go to the parent and are answered about by the boot mode.
     */
    static final class Corpus extends ClassLoader {
        private final java.util.Map<String, String> index;
        private final List<Path> roots;
        Corpus(java.util.Map<String, String> index, List<Path> roots) {
            super(Probe.class.getClassLoader());
            this.index = index;
            this.roots = roots;
        }

        /**
         * Where a name that is not one of the files under test comes from.
         * Without this, a mutant whose superclass is package private in the
         * same package gets the boot loader's copy, which is a different
         * runtime package, and the JVM answers IllegalAccessError instead of
         * answering about the class file: 523 of 2959 mutants in one sweep.
         */
        private String find(String name) {
            String s = index.get(name);
            if (s != null) return s;
            String rel = name.replace('.', '/') + ".class";
            for (Path r : roots) {
                Path p = r.resolve(rel);
                if (Files.isRegularFile(p)) return p.toString();
            }
            return null;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                String src = c == null && !name.startsWith("java.") ? find(name) : null;
                if (src != null) {
                    // A LinkageError from defineClass is the answer, not a
                    // reason to fall back to the parent.  Swallowing it made
                    // a truncated class file and a class file claiming major
                    // version 99 both come back as ORACLE_NA, because the
                    // parent then found the JDK's own good copy.
                    byte[] b;
                    try {
                        b = Files.readAllBytes(Path.of(src));
                    } catch (IOException e) {
                        b = null;
                    }
                    if (b != null) c = defineClass(name, b, 0, b.length);
                }
                if (c == null) c = super.loadClass(name, false);
                if (resolve) resolveClass(c);
                return c;
            }
        }

        Class<?> force(String name) throws ClassNotFoundException {
            return loadClass(name, false);
        }
    }

    static String flat(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = "";
        return clean(t.getClass().getName() + ": " + m);
    }

    /**
     * One line, one record.  A corrupted this_class can hold any byte at
     * all, including a newline, and a name interpolated into a message put
     * that newline in the middle of a tab separated record: the comparison
     * script then read half a path as a whole line and died on it.  Every
     * control character goes, not just the newline and the tab, because the
     * next one to turn up will be a carriage return or a form feed.
     */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c < 0x20 || c == 0x7f ? '.' : c);
        }
        return b.toString();
    }

    /**
     * The class a ClassFormatError or an UnsupportedClassVersionError names,
     * which is not always the one being asked about.  Two shapes:
     * "... in class file a/b/C" and "a/b/C has been compiled by ...".
     */
    static String inClassFile(String m) {
        if (m == null) return null;
        int i = m.indexOf("in class file ");
        if (i >= 0) {
            String s = m.substring(i + 14).trim();
            int sp = s.indexOf(' ');
            return (sp < 0 ? s : s.substring(0, sp)).replace('/', '.');
        }
        i = m.indexOf(" has been compiled by ");
        if (i > 0) return m.substring(0, i).trim().replace('/', '.');
        return null;
    }

    /** this_class, read out of the file directly: the path need not match. */
    static String nameOf(byte[] b) {
        int cp = 10;
        int count = ((b[8] & 0xff) << 8) | (b[9] & 0xff);
        int[] off = new int[count];
        for (int i = 1; i < count; i++) {
            off[i] = cp;
            int tag = b[cp] & 0xff;
            switch (tag) {
                case 1: cp += 3 + (((b[cp + 1] & 0xff) << 8) | (b[cp + 2] & 0xff)); break;
                case 7: case 8: case 16: case 19: case 20: cp += 3; break;
                case 15: cp += 4; break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    cp += 5; break;
                case 5: case 6: cp += 9; i++; break;
                default: return null;
            }
        }
        int thisClass = ((b[cp + 2] & 0xff) << 8) | (b[cp + 3] & 0xff);
        if (thisClass <= 0 || thisClass >= count) return null;
        int nameIdx = ((b[off[thisClass] + 1] & 0xff) << 8) | (b[off[thisClass] + 2] & 0xff);
        if (nameIdx <= 0 || nameIdx >= count) return null;
        int o = off[nameIdx];
        int len = ((b[o + 1] & 0xff) << 8) | (b[o + 2] & 0xff);
        return new String(b, o + 3, len, java.nio.charset.StandardCharsets.UTF_8)
                .replace('/', '.');
    }

    /** The class named on the "Location:" line of a HotSpot VerifyError. */
    static String locationClass(String msg) {
        if (msg == null) return null;
        int i = msg.indexOf("Location:");
        if (i < 0) return null;
        int nl = msg.indexOf('\n', i);
        if (nl < 0) return null;
        int nl2 = msg.indexOf('\n', nl + 1);
        String line = (nl2 < 0 ? msg.substring(nl + 1) : msg.substring(nl + 1, nl2)).trim();
        int at = line.indexOf('(');
        if (at < 0) return null;
        int dot = line.lastIndexOf('.', at);
        if (dot < 0) return null;
        return line.substring(0, dot).replace('/', '.');
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Probe system|define LISTFILE [ROOT:ROOT...]");
            System.exit(2);
        }
        String mode = args[0];
        List<String> paths = Files.readAllLines(Path.of(args[1]));
        java.util.Map<String, String> index = new java.util.HashMap<>();
        java.util.Set<String> dup = new java.util.HashSet<>();
        if (!mode.equals("system")) {
            for (String p : paths) {
                if (p.isEmpty()) continue;
                try {
                    String n = nameOf(Files.readAllBytes(Path.of(p)));
                    if (n == null) continue;
                    if (index.put(n, p) != null) dup.add(n);
                } catch (IOException | RuntimeException e) {
                    // reported per file below
                }
            }
        }
        List<Path> roots = new ArrayList<>();
        if (args.length > 2 && !args[2].isEmpty())
            for (String r : args[2].split(java.io.File.pathSeparator))
                roots.add(Path.of(r));
        Corpus corpus = new Corpus(index, roots);
        PrintStream out = new PrintStream(new java.io.BufferedOutputStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), 1 << 16), false);
        for (String p : paths) {
            if (p.isEmpty()) continue;
            byte[] b;
            try {
                b = Files.readAllBytes(Path.of(p));
            } catch (IOException e) {
                out.println("ORACLE_NA\t" + p + "\tunreadable: " + e.getMessage());
                continue;
            }
            String name = null;
            try {
                name = nameOf(b);
            } catch (RuntimeException e) {
                name = null;
            }
            if (name == null) {
                out.println("ORACLE_NA\t" + p + "\tcannot read this_class to name it");
                continue;
            }
            try {
                Class<?> c;
                if (mode.equals("system")) {
                    c = Class.forName(name, false, ClassLoader.getSystemClassLoader());
                } else {
                    if (name.startsWith("java.")) {
                        out.println("ORACLE_NA\t" + p
                                + "\tprohibited package name for a user class loader");
                        continue;
                    }
                    if (dup.contains(name)) {
                        out.println("ORACLE_NA\t" + p + "\tthe corpus holds more than one "
                                + clean(name) + "; one loader can define only the first");
                        continue;
                    }
                    c = corpus.force(name);
                    if (c.getClassLoader() != corpus) {
                        out.println("ORACLE_NA\t" + p + "\tdefined by "
                                + clean(String.valueOf(c.getClassLoader()))
                                + ", not from the corpus file");
                        continue;
                    }
                }
                c.getDeclaredMethods();     // forces linking, hence verification
                c.getDeclaredFields();
                out.println("ACCEPT\t" + p);
            } catch (VerifyError e) {
                // A VerifyError raised while linking some OTHER class is not
                // an answer about this file.  In "define" mode a corpus class
                // whose supertype chain crosses into a java.* package picks up
                // the boot loader's copy of a type halfway along, and the two
                // copies are not assignable to each other: 324 java.desktop
                // classes failed that way, every one of them naming
                // javax/swing/JPanel.getAccessibleContext or a sibling rather
                // than itself.  That is a defect in the harness, so it is a
                // refusal and not a verdict.
                String blamed = locationClass(e.getMessage());
                if (blamed != null && !blamed.equals(name))
                    out.println("ORACLE_NA\t" + p + "\tVerifyError raised while linking "
                            + clean(blamed) + ", not this class");
                else
                    out.println("VERIFY\t" + p + "\t" + flat(e));
            } catch (ClassFormatError e) {
                // The same trap as the VerifyError above, and it was found
                // the same way.  Defining a batch of mutants in one loader
                // means a mutant's superclass can be another mutant, so the
                // ClassFormatError raised while linking this one can be
                // about that one: "Invalid length 53253 in LocalVariableTable
                // in class file com/sun/crypto/provider/SymmetricCipher"
                // arrived for a file whose own name is something else, and
                // asking about that file alone gives ACCEPT.  2 of 6000
                // random mutants, and both would have been recorded as
                // classv accepting a file the JVM rejects.
                String blamed = inClassFile(e.getMessage());
                if (blamed != null && !blamed.equals(name))
                    out.println("ORACLE_NA\t" + p + "\tClassFormatError about "
                            + clean(blamed) + ", not this class");
                else
                    out.println("FORMAT\t" + p + "\t" + flat(e));
            } catch (ClassCircularityError e) {
                // A real rejection, and neither a format nor a verification
                // error.  Kept as its own word rather than folded into either,
                // because folding a third answer into one of two is how a
                // comparison starts agreeing about things it never checked.
                out.println("OTHER\t" + p + "\t" + flat(e));
            } catch (IllegalAccessError e) {
                // Always the harness and never the class file: a corpus class
                // in the unnamed module reaching a superclass that its own
                // module does not export.  Never a verdict.
                out.println("ORACLE_NA\t" + p + "\t" + flat(e));
            } catch (LinkageError e) {
                String m = e.getMessage();
                if (m != null && (m.contains(name) || m.contains(name.replace('.', '/'))))
                    out.println("OTHER\t" + p + "\t" + flat(e));
                else
                    out.println("ORACLE_NA\t" + p + "\t" + flat(e));
            } catch (Throwable e) {
                // NoClassDefFoundError, IllegalAccessError, SecurityException,
                // and anything else the JVM raises before it gets as far as
                // the verifier.  Never an answer about the class file.
                out.println("ORACLE_NA\t" + p + "\t" + flat(e));
            }
        }
        out.flush();
    }
}
