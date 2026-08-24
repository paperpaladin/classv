// A class with one ordinary field and two ordinary methods, for the access
// flag sweep.  The name is three characters because the sweep renames each
// variant in place, and a class file cannot be made longer without moving
// everything after the constant pool.
class Flg {
    int f;
    int fld;

    void m() {
        f++;
    }

    int met(int n) {
        return fld + n;
    }

    static int s(int n) {
        return n + 1;
    }
}
