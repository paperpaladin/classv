package classv;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * classv -- read a class file and say whether the JVM's type checker would
 * accept it, and if not, why.
 *
 * Exit status, all distinct on purpose so a harness can never fold two of
 * them together:
 *   0  the class parses and (with --verify) type checks
 *   1  the type checker rejects it         ("VERIFY" on the first line)
 *   2  usage error
 *   3  classv refuses to analyse it        ("REFUSE")
 *   4  the file is not a well formed class ("FORMAT")
 *   5  a defect in classv itself ("INTERNAL"), which is never an answer
 */
public final class Main {

    public static final int EXIT_OK = 0, EXIT_VERIFY = 1, EXIT_USAGE = 2,
            EXIT_REFUSE = 3, EXIT_FORMAT = 4, EXIT_INTERNAL = 5;

    private static final String USAGE =
            "usage: classv [--info] [--pool] [--disasm] [--smt] [--frames] [--verify]\n"
            + "              [--cp DIR]... [--all] FILE.class... | @LISTFILE";

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        boolean info = false, pool = false, disasm = false, verify = false, smt = false;
        boolean frames = false;
        Hierarchy hierarchy = new Hierarchy();
        boolean wantCp = false;
        java.util.List<String> files = new java.util.ArrayList<>();
        for (String a : args) {
            switch (a) {
                case "--info": info = true; break;
                case "--pool": pool = true; break;
                case "--disasm": disasm = true; break;
                case "--smt": smt = true; break;
                case "--verify": verify = true; break;
                case "--frames": frames = true; verify = true; break;
                case "--cp": wantCp = true; break;
                case "--all": info = pool = disasm = smt = verify = true; break;
                case "-h": case "--help": out.println(USAGE); return EXIT_OK;
                default:
                    if (wantCp) { hierarchy.addRoot(a); wantCp = false; break; }
                    if (a.startsWith("-")) { err.println(USAGE); return EXIT_USAGE; }
                    if (a.startsWith("@")) {
                        // The corpus is 27846 files; that does not fit in argv.
                        try {
                            for (String ln : Files.readAllLines(Path.of(a.substring(1))))
                                if (!ln.isEmpty()) files.add(ln);
                        } catch (Exception e) {
                            err.println("classv: cannot read list " + a.substring(1)
                                    + ": " + e.getMessage());
                            return EXIT_USAGE;
                        }
                    } else {
                        files.add(a);
                    }
            }
        }
        if (files.isEmpty()) { err.println(USAGE); return EXIT_USAGE; }
        if (!info && !pool && !disasm && !smt && !verify) verify = true;

        // More than one file in one JVM: starting a JVM per class costs more
        // than reading the class, and the corpus is tens of thousands of them.
        // The per-file status word is the answer in that mode; the exit status
        // only reports whether every file was processed.
        int worst = EXIT_OK;
        for (String f : files) {
            if (files.size() > 1) out.println("== " + f);
            int st;
            try {
                st = one(f, info, pool, disasm, smt, verify, frames, hierarchy, out, err);
            } catch (RuntimeException | StackOverflowError e) {
                // A bug in classv, and it gets a status of its own.  Letting
                // it out of main would exit 1, which is the status for a
                // class the type checker rejects, and would end the run:
                // one escaping exception left 396 of 400 fuzz mutants with
                // no answer, counted as a batch that had been looked at.
                out.println("INTERNAL " + f + ": " + e + atFirstFrame(e));
                st = EXIT_INTERNAL;
            }
            if (st == EXIT_USAGE) return EXIT_USAGE;
            if (st > worst) worst = st;
        }
        return files.size() > 1 ? EXIT_OK : worst;
    }

    /** The first line of classv's own that the exception came through. */
    private static String atFirstFrame(Throwable e) {
        for (StackTraceElement t : e.getStackTrace())
            if (t.getClassName().startsWith("classv."))
                return " at " + t.getClassName() + "." + t.getMethodName()
                        + " line " + t.getLineNumber();
        return "";
    }

    static int one(String file, boolean info, boolean pool, boolean disasm, boolean smt,
                   boolean verify, boolean frames, Hierarchy hierarchy,
                   PrintStream out, PrintStream err) {

        byte[] data;
        try {
            data = Files.readAllBytes(Path.of(file));
        } catch (Exception e) {
            err.println("classv: cannot read " + file + ": " + e.getMessage());
            return EXIT_USAGE;
        }

        ClassFile cf;
        try {
            cf = ClassFile.parse(data);
        } catch (ClassFormatException e) {
            out.println("FORMAT " + file + ": " + e.getMessage());
            return EXIT_FORMAT;
        }

        try {
            if (info) printInfo(cf, out);
            if (pool) printPool(cf, out);
            if (disasm || smt) printDisasm(cf, out, disasm, smt);
        } catch (ClassFormatException e) {
            out.println("FORMAT " + file + ": " + e.getMessage());
            return EXIT_FORMAT;
        }

        if (!verify) {
            out.println("OK " + cf.thisClass);
            return EXIT_OK;
        }

        Verifier v = new Verifier(cf, hierarchy);
        v.dumpFrames = frames;
        v.out = out;
        if (frames) out.println("frames " + cf.thisClass);
        Verifier.Result r = v.verify();
        switch (r.kind) {
            case OK:
                out.println("OK " + cf.thisClass);
                return EXIT_OK;
            case VERIFY:
                out.println("VERIFY " + r.message);
                for (String d : r.detail) out.println("    " + d);
                return EXIT_VERIFY;
            case FORMAT:
                out.println("FORMAT " + cf.thisClass + ": " + r.message);
                return EXIT_FORMAT;
            default:
                out.println("REFUSE " + cf.thisClass + ": " + r.message);
                return EXIT_REFUSE;
        }
    }

    private static void printInfo(ClassFile cf, PrintStream out) {
        out.println("class " + cf.thisClass);
        out.println("major " + cf.major);
        out.println("minor " + cf.minor);
        out.println("flags 0x" + Integer.toHexString(cf.accessFlags));
        out.println("super " + (cf.superClass == null ? "-" : cf.superClass));
        for (String i : cf.interfaces) out.println("interface " + i);
        out.println("pool_count " + cf.pool.count);
        for (ClassFile.Member f : cf.fields)
            out.println("field " + f.name + " " + f.desc + " 0x" + Integer.toHexString(f.flags));
        for (ClassFile.Member m : cf.methods)
            out.println("method " + m.name + " " + m.desc + " 0x" + Integer.toHexString(m.flags));
        for (ClassFile.Attr a : cf.attrs)
            out.println("attribute " + a.name + " " + a.data.length);
        out.println("trailing " + cf.trailing);
    }

    private static void printPool(ClassFile cf, PrintStream out) {
        ConstantPool p = cf.pool;
        for (int i = 1; i < p.count; i++) {
            if (p.tag[i] == 0) continue;   // second half of a long or double
            String operand = p.tag[i] == ConstantPool.UTF8
                    ? escape(p.operandText(i)) : p.operandText(i);
            out.println("#" + i + " = " + ConstantPool.tagName(p.tag[i]) + " " + operand);
        }
    }

    /**
     * Every character outside printable ASCII becomes \\uXXXX.  A constant pool
     * string can hold a newline, a lone surrogate or a trailing space, and all
     * three make a line oriented dump ambiguous -- the first one silently
     * turned one pool entry into two when the javap comparison first ran.
     */
    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c >= 0x20 && c < 0x7f) b.append(c);
            else b.append(String.format("\\u%04x", (int) c));
        }
        return b.toString();
    }

    private static void printDisasm(ClassFile cf, PrintStream out, boolean disasm, boolean smt)
            throws ClassFormatException {
        for (ClassFile.Member m : cf.methods) {
            out.println("code " + m.name + " " + m.desc);
            if (m.code == null) { out.println("  nocode"); continue; }
            if (disasm) {
                out.println("  max_stack " + m.code.maxStack
                        + " max_locals " + m.code.maxLocals
                        + " code_length " + m.code.code.length);
                List<Op.Insn> insns = Op.decode(m.code.code);
                for (Op.Insn in : insns) out.println("  " + Op.text(in));
                for (ClassFile.Handler h : m.code.handlers)
                    out.println("  handler " + h.start + " " + h.end + " " + h.handler
                            + " " + (h.catchType == 0 ? "any" : cf.pool.className(h.catchType)));
            }
            if (smt) {
                ClassFile.Attr a = m.code.attr("StackMapTable");
                if (a == null) continue;
                StackMap sm = StackMap.parse(a.data, cf.pool);
                out.println("  smt " + sm.entries.size());
                for (StackMap.Entry e : sm.entries) {
                    out.println("  frame " + e.frameType + " " + e.kindName()
                            + " at " + e.offset);
                    if (e.hasDelta) out.println("    delta " + e.delta);
                    if (e.locals != null) out.println("    locals " + typeList(e.locals));
                    if (e.stack != null) out.println("    stack " + typeList(e.stack));
                }
            }
        }
    }

    private static String typeList(VType[] ts) {
        StringBuilder b = new StringBuilder();
        b.append(ts.length);
        for (int i = 0; i < ts.length; i++) b.append(i == 0 ? " " : "|").append(ts[i]);
        return b.toString();
    }
}
