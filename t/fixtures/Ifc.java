// An interface with a field and three methods, for the access flag sweep.
// The rules in JVMS 4.6 for a method of an interface are different from the
// rules for a method of a class, so sweeping only a class would measure
// half of them.  Three characters in the name, like the other fixture.
interface Ifc {
    int F = 1;

    void m();

    default int d() {
        return F;
    }

    static int s(int n) {
        return n + 1;
    }
}
