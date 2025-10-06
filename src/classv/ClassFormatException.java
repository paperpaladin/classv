package classv;

/**
 * The bytes are not a well formed class file.  This is the analogue of the
 * JVM's ClassFormatError: the file is rejected before type checking can
 * begin, and the message says which structural rule failed and at which
 * byte offset.
 */
public class ClassFormatException extends Exception {
    private static final long serialVersionUID = 1L;
    public final int offset;

    public ClassFormatException(int offset, String msg) {
        super(msg + " (at byte " + offset + ")");
        this.offset = offset;
    }
}
