// A record, for the Record attribute, which is the only attribute whose
// entries hold attributes of their own.  Compiled at the default release,
// so this one is not part of the --release 8 corpus.
record Rec(int n, String s) {
    Rec {
        if (n < 0) {
            throw new IllegalArgumentException(s);
        }
    }
}
