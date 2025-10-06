package classv;

/**
 * A bounds checked cursor over the class file.  Every read in the parser goes
 * through this; nothing indexes the byte array directly.  A read past the end
 * is a ClassFormatException naming the offset, never an
 * ArrayIndexOutOfBoundsException, because a truncated file is ordinary input
 * for this program and a stack trace is not a diagnosis.
 */
public final class Buf {
    private final byte[] b;
    private final int limit;
    private int p;

    public Buf(byte[] b) { this(b, 0, b.length); }

    public Buf(byte[] b, int start, int limit) {
        this.b = b;
        this.limit = limit;
        this.p = start;
    }

    public int pos() { return p; }
    public void pos(int np) { p = np; }
    public int limit() { return limit; }
    public int remaining() { return limit - p; }
    public byte[] array() { return b; }

    private void need(int n) throws ClassFormatException {
        if (n < 0 || p + n > limit)
            throw new ClassFormatException(p, "read of " + n
                    + " bytes runs past the end of the structure (limit " + limit + ")");
    }

    public int u1() throws ClassFormatException {
        need(1);
        return b[p++] & 0xff;
    }

    public int s1() throws ClassFormatException {
        need(1);
        return b[p++];
    }

    public int u2() throws ClassFormatException {
        need(2);
        int v = ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
        p += 2;
        return v;
    }

    public int s2() throws ClassFormatException {
        return (short) u2();
    }

    public int s4() throws ClassFormatException {
        need(4);
        int v = ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16)
              | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
        p += 4;
        return v;
    }

    public long u4() throws ClassFormatException {
        return s4() & 0xffffffffL;
    }

    public long s8() throws ClassFormatException {
        return (s4() & 0xffffffffL) << 32 | (s4() & 0xffffffffL);
    }

    public byte[] bytes(int n) throws ClassFormatException {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(b, p, out, 0, n);
        p += n;
        return out;
    }

    public void skip(int n) throws ClassFormatException {
        need(n);
        p += n;
    }

    /** A sub-cursor over the next n bytes; this cursor advances past them. */
    public Buf slice(int n) throws ClassFormatException {
        need(n);
        Buf s = new Buf(b, p, p + n);
        p += n;
        return s;
    }
}
