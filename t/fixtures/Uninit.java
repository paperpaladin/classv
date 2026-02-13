// The putfield rule for an uninitialized this, which is the one place where
// HotSpot is stricter than the letter of JVMS 4.10.1.9.
//
// Both constructors below assign to a field through a Fieldref naming their
// own class, which is what javac emits.  Own.v is declared in Own; Sub.m is
// inherited from Base.  With the superclass constructor call removed by the
// noinit recipe, the spec's second putfield rule allows both, because it
// asks only that the Fieldref name the current class.  HotSpot allows only
// the first: its find_local_field call means an inherited field is not
// reachable through an uninitializedThis.
class Base {
    int m;
}

class Sub extends Base {
    Sub() {
        m = 1;
    }
}

class Own {
    int v;

    Own() {
        v = 1;
    }
}
