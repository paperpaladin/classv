package classv;

/** The opcode table, JVMS 6.5, and the instruction decoder. */
public final class Op {
    private Op() { }

    // Operand layouts.
    static final int NONE = 0;      // no operands
    static final int S1 = 1;        // signed byte immediate (bipush)
    static final int S2 = 2;        // signed short immediate (sipush)
    static final int CP1 = 3;       // u1 constant pool index (ldc)
    static final int CP2 = 4;       // u2 constant pool index
    static final int LOCAL = 5;     // u1 local index (u2 under wide)
    static final int IINC = 6;      // u1 local, s1 delta (u2,s2 under wide)
    static final int BR2 = 7;       // signed 2 byte branch
    static final int BR4 = 8;       // signed 4 byte branch
    static final int TABLE = 9;
    static final int LOOKUP = 10;
    static final int NEWARRAY = 11; // u1 atype
    static final int IINTF = 12;    // u2 cp, u1 count, u1 zero
    static final int INDY = 13;     // u2 cp, u2 zero
    static final int MULTI = 14;    // u2 cp, u1 dims
    static final int WIDE = 15;

    public static final String[] NAME = new String[256];
    static final int[] FMT = new int[256];
    static { java.util.Arrays.fill(FMT, -1); }

    private static void op(int c, String n, int f) { NAME[c] = n; FMT[c] = f; }

    static {
        op(0, "nop", NONE); op(1, "aconst_null", NONE);
        op(2, "iconst_m1", NONE);
        for (int i = 0; i <= 5; i++) op(3 + i, "iconst_" + i, NONE);
        op(9, "lconst_0", NONE); op(10, "lconst_1", NONE);
        op(11, "fconst_0", NONE); op(12, "fconst_1", NONE); op(13, "fconst_2", NONE);
        op(14, "dconst_0", NONE); op(15, "dconst_1", NONE);
        op(16, "bipush", S1); op(17, "sipush", S2);
        op(18, "ldc", CP1); op(19, "ldc_w", CP2); op(20, "ldc2_w", CP2);
        op(21, "iload", LOCAL); op(22, "lload", LOCAL); op(23, "fload", LOCAL);
        op(24, "dload", LOCAL); op(25, "aload", LOCAL);
        String[] t = {"i", "l", "f", "d", "a"};
        for (int k = 0; k < 5; k++)
            for (int i = 0; i <= 3; i++) op(26 + k * 4 + i, t[k] + "load_" + i, NONE);
        op(46, "iaload", NONE); op(47, "laload", NONE); op(48, "faload", NONE);
        op(49, "daload", NONE); op(50, "aaload", NONE); op(51, "baload", NONE);
        op(52, "caload", NONE); op(53, "saload", NONE);
        op(54, "istore", LOCAL); op(55, "lstore", LOCAL); op(56, "fstore", LOCAL);
        op(57, "dstore", LOCAL); op(58, "astore", LOCAL);
        for (int k = 0; k < 5; k++)
            for (int i = 0; i <= 3; i++) op(59 + k * 4 + i, t[k] + "store_" + i, NONE);
        op(79, "iastore", NONE); op(80, "lastore", NONE); op(81, "fastore", NONE);
        op(82, "dastore", NONE); op(83, "aastore", NONE); op(84, "bastore", NONE);
        op(85, "castore", NONE); op(86, "sastore", NONE);
        op(87, "pop", NONE); op(88, "pop2", NONE); op(89, "dup", NONE);
        op(90, "dup_x1", NONE); op(91, "dup_x2", NONE); op(92, "dup2", NONE);
        op(93, "dup2_x1", NONE); op(94, "dup2_x2", NONE); op(95, "swap", NONE);
        String[] arith = {"add", "sub", "mul", "div", "rem"};
        for (int a = 0; a < 5; a++)
            for (int k = 0; k < 4; k++) op(96 + a * 4 + k, t[k] + arith[a], NONE);
        for (int k = 0; k < 4; k++) op(116 + k, t[k] + "neg", NONE);
        String[] sh = {"shl", "shr", "ushr"};
        for (int a = 0; a < 3; a++) {
            op(120 + a * 2, "i" + sh[a], NONE);
            op(121 + a * 2, "l" + sh[a], NONE);
        }
        op(126, "iand", NONE); op(127, "land", NONE);
        op(128, "ior", NONE); op(129, "lor", NONE);
        op(130, "ixor", NONE); op(131, "lxor", NONE);
        op(132, "iinc", IINC);
        op(133, "i2l", NONE); op(134, "i2f", NONE); op(135, "i2d", NONE);
        op(136, "l2i", NONE); op(137, "l2f", NONE); op(138, "l2d", NONE);
        op(139, "f2i", NONE); op(140, "f2l", NONE); op(141, "f2d", NONE);
        op(142, "d2i", NONE); op(143, "d2l", NONE); op(144, "d2f", NONE);
        op(145, "i2b", NONE); op(146, "i2c", NONE); op(147, "i2s", NONE);
        op(148, "lcmp", NONE); op(149, "fcmpl", NONE); op(150, "fcmpg", NONE);
        op(151, "dcmpl", NONE); op(152, "dcmpg", NONE);
        String[] cc = {"eq", "ne", "lt", "ge", "gt", "le"};
        for (int i = 0; i < 6; i++) op(153 + i, "if" + cc[i], BR2);
        for (int i = 0; i < 6; i++) op(159 + i, "if_icmp" + cc[i], BR2);
        op(165, "if_acmpeq", BR2); op(166, "if_acmpne", BR2);
        op(167, "goto", BR2); op(168, "jsr", BR2); op(169, "ret", LOCAL);
        op(170, "tableswitch", TABLE); op(171, "lookupswitch", LOOKUP);
        for (int k = 0; k < 5; k++) op(172 + k, t[k] + "return", NONE);
        op(177, "return", NONE);
        op(178, "getstatic", CP2); op(179, "putstatic", CP2);
        op(180, "getfield", CP2); op(181, "putfield", CP2);
        op(182, "invokevirtual", CP2); op(183, "invokespecial", CP2);
        op(184, "invokestatic", CP2); op(185, "invokeinterface", IINTF);
        op(186, "invokedynamic", INDY);
        op(187, "new", CP2); op(188, "newarray", NEWARRAY); op(189, "anewarray", CP2);
        op(190, "arraylength", NONE); op(191, "athrow", NONE);
        op(192, "checkcast", CP2); op(193, "instanceof", CP2);
        op(194, "monitorenter", NONE); op(195, "monitorexit", NONE);
        op(196, "wide", WIDE); op(197, "multianewarray", MULTI);
        op(198, "ifnull", BR2); op(199, "ifnonnull", BR2);
        op(200, "goto_w", BR4); op(201, "jsr_w", BR4);
    }

    public static final String[] ATYPE = new String[12];
    static {
        ATYPE[4] = "boolean"; ATYPE[5] = "char"; ATYPE[6] = "float"; ATYPE[7] = "double";
        ATYPE[8] = "byte"; ATYPE[9] = "short"; ATYPE[10] = "int"; ATYPE[11] = "long";
    }

    /** One decoded instruction. */
    public static final class Insn {
        public int pc;
        public int op;          // the real opcode; for a wide form, the widened one
        public boolean wide;
        public int len;         // total bytes including the 0xc4 of a wide prefix
        public int a, b;        // first and second operand, meaning per layout
        public int defaultTarget, low, high;
        public int[] keys;
        public int[] targets;
        public String name() { return NAME[op]; }
    }

    /**
     * Decode the whole code array.  A branch offset is resolved to an absolute
     * pc here and nowhere else, so the one place that can get the base wrong is
     * one place.
     */
    public static java.util.List<Insn> decode(byte[] code) throws ClassFormatException {
        java.util.List<Insn> out = new java.util.ArrayList<>();
        Buf b = new Buf(code);
        while (b.remaining() > 0) {
            Insn in = new Insn();
            in.pc = b.pos();
            int o = b.u1();
            if (o == 196) {
                in.wide = true;
                o = b.u1();
                int f = (o >= 0 && o < 256) ? FMT[o] : -1;
                if (f != LOCAL && f != IINC)
                    throw new ClassFormatException(in.pc, "wide prefixes "
                            + (NAME[o] == null ? "unknown opcode " + o : NAME[o])
                            + ", which takes no local variable index");
                in.op = o;
                in.a = b.u2();
                if (f == IINC) in.b = b.s2();
            } else {
                in.op = o;
                int f = FMT[o];
                if (f < 0)
                    throw new ClassFormatException(in.pc, "opcode " + o
                            + " is not defined" + (o == 202 ? " (0xca is reserved for breakpoint)"
                            : (o >= 254 ? " (reserved for an implementation)" : "")));
                switch (f) {
                    case NONE: break;
                    case S1: in.a = b.s1(); break;
                    case S2: in.a = b.s2(); break;
                    case CP1: in.a = b.u1(); break;
                    case CP2: in.a = b.u2(); break;
                    case LOCAL: in.a = b.u1(); break;
                    case IINC: in.a = b.u1(); in.b = b.s1(); break;
                    case BR2: in.a = in.pc + b.s2(); break;
                    case BR4: in.a = in.pc + b.s4(); break;
                    case NEWARRAY: in.a = b.u1(); break;
                    case IINTF: in.a = b.u2(); in.b = b.u1(); b.u1(); break;
                    case INDY: in.a = b.u2(); b.u2(); break;
                    case MULTI: in.a = b.u2(); in.b = b.u1(); break;
                    case TABLE: {
                        while ((b.pos() & 3) != 0) b.u1();
                        in.defaultTarget = in.pc + b.s4();
                        in.low = b.s4();
                        in.high = b.s4();
                        long n = (long) in.high - in.low + 1;
                        if (in.low > in.high || n > b.remaining() / 4L)
                            throw new ClassFormatException(in.pc,
                                    "tableswitch low=" + in.low + " high=" + in.high
                                    + " needs " + n + " targets, which does not fit");
                        in.targets = new int[(int) n];
                        for (int i = 0; i < n; i++) in.targets[i] = in.pc + b.s4();
                        break;
                    }
                    case LOOKUP: {
                        while ((b.pos() & 3) != 0) b.u1();
                        in.defaultTarget = in.pc + b.s4();
                        int n = b.s4();
                        if (n < 0 || n > b.remaining() / 8)
                            throw new ClassFormatException(in.pc,
                                    "lookupswitch npairs=" + n + " does not fit");
                        in.keys = new int[n];
                        in.targets = new int[n];
                        for (int i = 0; i < n; i++) {
                            in.keys[i] = b.s4();
                            in.targets[i] = in.pc + b.s4();
                        }
                        break;
                    }
                    default: throw new IllegalStateException();
                }
            }
            in.len = b.pos() - in.pc;
            out.add(in);
        }
        return out;
    }

    /** The canonical one line rendering the javap comparison is made against. */
    public static String text(Insn in) {
        StringBuilder s = new StringBuilder();
        s.append(in.pc).append(' ');
        if (in.wide) s.append("wide ");
        s.append(NAME[in.op]);
        int f = FMT[in.op];
        switch (f) {
            case NONE: break;
            case S1: case S2: case LOCAL: s.append(' ').append(in.a); break;
            case CP1: case CP2: s.append(" #").append(in.a); break;
            case IINC: s.append(' ').append(in.a).append(' ').append(in.b); break;
            case BR2: case BR4: s.append(' ').append(in.a); break;
            case NEWARRAY:
                s.append(' ').append(in.a >= 4 && in.a <= 11 ? ATYPE[in.a] : "atype" + in.a);
                break;
            case IINTF: s.append(" #").append(in.a).append(' ').append(in.b); break;
            case INDY: s.append(" #").append(in.a); break;
            case MULTI: s.append(" #").append(in.a).append(' ').append(in.b); break;
            case TABLE:
                s.append(" default=").append(in.defaultTarget);
                for (int i = 0; i < in.targets.length; i++)
                    s.append(' ').append(in.low + i).append(':').append(in.targets[i]);
                break;
            case LOOKUP:
                s.append(" default=").append(in.defaultTarget);
                for (int i = 0; i < in.targets.length; i++)
                    s.append(' ').append(in.keys[i]).append(':').append(in.targets[i]);
                break;
            default: break;
        }
        return s.toString();
    }
}
