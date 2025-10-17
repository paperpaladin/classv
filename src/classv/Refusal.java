package classv;

/**
 * classv cannot answer.  Not "the class is bad" and not "the class is fine":
 * a question this program is unable to decide, such as a subtype question
 * about a class it cannot find.
 *
 * It is a separate type from ClassFormatException because the two must never
 * be counted together.  A verifier that refuses to analyse most of its corpus
 * and agrees with the JVM about the rest has not agreed about anything much.
 */
public class Refusal extends Exception {
    private static final long serialVersionUID = 1L;

    public Refusal(String msg) { super(msg); }
}
