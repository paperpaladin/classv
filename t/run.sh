#!/bin/sh
# classv test suite.
#
# Everything here is measured against something outside classv:
#   javap   -- the JDK's own disassembler, for the parse and the bytecode
#   java    -- the real HotSpot verifier, through t/Probe.java
#
# The corpus is the JDK's own lib/modules, unpacked with jimage.  By default
# that is the java.base module; CLASSV_FULL=1 uses every module in the image.
#
# Scratch lives in a per run mktemp directory: two concurrent runs must not
# feed each other their corpora.
set -u

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$here/.." && pwd)
CLASSV=${CLASSV:-$root/classv}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM

pass=0
fail=0
ok()  { pass=$((pass+1)); printf 'ok   %s\n' "$*"; }
bad() { fail=$((fail+1)); printf 'FAIL %s\n' "$*"; }

[ -f "$root/build/classv/Main.class" ] || { echo "no build; run make"; exit 1; }
newest=$(ls -t "$root"/src/classv/*.java | head -1)
[ "$newest" -nt "$root/build/classv/Main.class" ] && {
	echo "$newest is newer than the build; run make"; exit 1; }
command -v javap >/dev/null || { echo "javap not found; it is the only oracle"; exit 2; }
command -v jimage >/dev/null || { echo "jimage not found; no corpus"; exit 2; }

JDKHOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
MODULES=$JDKHOME/lib/modules
[ -f "$MODULES" ] || { echo "no module image at $MODULES; no corpus"; exit 2; }

echo "--- corpus: $MODULES"
if [ "${CLASSV_FULL:-0}" = 1 ]; then
	jimage extract --dir "$work/jdk" "$MODULES" >/dev/null
else
	jimage extract --include 'glob:/java.base/**' --dir "$work/jdk" "$MODULES" >/dev/null
fi
find "$work/jdk" -name '*.class' | sort > "$work/corpus.txt"
n=$(wc -l < "$work/corpus.txt")
echo "--- $n class files"
[ "$n" -gt 1000 ] || { echo "corpus of $n is too small to mean anything"; exit 2; }

# ------------------------------------------------- oracle 1: javap, fact by fact
out=$(python3 "$here/javap_cmp.py" "$CLASSV" "$work/corpus.txt" 300)
echo "$out" | grep -v '^JAVAP_' 
p=$(printf '%s\n' "$out" | sed -n 's/^JAVAP_PASS //p')
f=$(printf '%s\n' "$out" | sed -n 's/^JAVAP_FAIL //p')
[ -n "$p" ] || { echo "javap_cmp.py produced no count"; exit 2; }
pass=$((pass + p))
fail=$((fail + f))

# ------------------------------------------- oracle 2: the JVM's own verifier
#
# Before any agreement number is believed, the oracle has to be shown saying
# no.  A verifier oracle that accepts everything would make classv look
# perfect and would be measuring nothing.
echo
echo "--- oracle 2: HotSpot, through t/Probe.java"
mkdir -p "$work/probe"
javac -d "$work/probe" "$here/Probe.java" 2>&1 || { echo "cannot build Probe"; exit 2; }

# Every system module resolved, or 2276 of the 27846 classes are not reachable
# from the system class loader and the oracle simply has no opinion on them.
java -XX:+UnlockDiagnosticVMOptions -version >/dev/null 2>&1
PROBE="java -Xverify:all --add-modules ALL-SYSTEM -cp $work/probe Probe"

$PROBE system "$work/corpus.txt" > "$work/jvm.txt" 2>"$work/jvm.err"
acc=$(awk -F'	' '$1=="ACCEPT"' "$work/jvm.txt" | wc -l)
rej=$(awk -F'	' '$1=="VERIFY" || $1=="FORMAT"' "$work/jvm.txt" | wc -l)
na=$(awk -F'	' '$1=="ORACLE_NA"' "$work/jvm.txt" | wc -l)
echo "--- JVM verdicts over the corpus: $acc accept, $rej reject, $na no opinion"
if [ "$acc" -gt 1000 ]; then
	ok "the JVM answers about $acc of $n corpus classes"
else
	bad "the JVM answered about only $acc classes; the oracle is not working"
fi

# The capability check: planted defects the JVM must reject.
javac -d "$work/fix" "$here/fixtures/Fix.java" 2>&1 || { echo "cannot build Fix"; exit 2; }
FIX=$work/fix/Fix.class
probe_one() {
	echo "$1" > "$work/one.txt"
	$PROBE define "$work/one.txt" 2>/dev/null | head -1
}
v=$(probe_one "$FIX" | cut -f1)
if [ "$v" = ACCEPT ]; then
	ok "the JVM accepts the untouched fixture"
else
	bad "the JVM rejects the untouched fixture: $v"
fi

plant() {
	recipe=$1; want=$2
	if ! why=$(python3 "$here/mutate.py" "$recipe" "$FIX" "$work/m.class" 2>&1); then
		bad "mutation $recipe does not apply to the fixture: $why"
		return
	fi
	got=$(probe_one "$work/m.class" | cut -f1)
	if [ "$got" = "$want" ]; then
		ok "the JVM answers $want to $recipe"
	else
		bad "the JVM answers $got to $recipe, wanted $want"
	fi
}
# The wanted column is measured, not guessed: each of these was run once by
# hand and the JVM's own answer written down.  They cover six distinct
# HotSpot diagnoses -- bad type on operand stack, bad local variable type,
# operand stack underflow and overflow, StackMapTable bad offset, and a
# missing stackmap frame -- plus three ways to be malformed before the
# verifier runs at all.
plant subst:ireturn:areturn  VERIFY
plant subst:iload_0:aload_0  VERIFY
plant subst:aload_0:iload_0  VERIFY
plant subst:dup:dup2         VERIFY
plant subst:return:athrow    VERIFY
plant subst:iadd:ladd        VERIFY
plant subst:pop:pop2         VERIFY
plant maxstack:-1            VERIFY
plant smtdelta:1             VERIFY
plant smtdelta:-1            VERIFY
plant dropsmt                VERIFY
plant cpindex:1              VERIFY
plant cpindex:-1             VERIFY
plant maxlocals:-1           FORMAT
plant truncate:4             FORMAT
plant truncate:1             FORMAT
plant major:99               FORMAT
plant major:45               FORMAT
plant superself              OTHER

# -Xverify:all is load bearing, and this is the only place that says so.
# Without it a class loaded by the boot loader is not verified at all
# (BytecodeVerificationLocal defaults to false), so the whole boot path of
# the oracle would be answering ACCEPT to everything.
mkdir -p "$work/patch/java/lang"
cat > "$work/Zz.java" <<'JEOF'
package java.lang;
public class Zz { public static int f() { return 7; } }
JEOF
javac --patch-module java.base="$work" -d "$work/pbuild" "$work/Zz.java" 2>/dev/null
python3 - "$work/pbuild/java/lang/Zz.class" "$work/patch/java/lang/Zz.class" <<'PEOF2'
import sys
d = bytearray(open(sys.argv[1], 'rb').read())
i = d.find(b'\x10\x07\xac')      # bipush 7; ireturn
assert i > 0, 'fixture shape changed'
d[i] = 0x01                      # aconst_null
d[i + 1] = 0x00                  # nop
open(sys.argv[2], 'wb').write(bytes(d))
PEOF2
cat > "$work/Force.java" <<'JEOF'
public class Force { public static void main(String[] a) throws Exception {
  try { Class.forName(a[0], false, null).getDeclaredMethods(); System.out.println("LINKED"); }
  catch (Throwable t) { System.out.println(t.getClass().getSimpleName()); } } }
JEOF
javac -d "$work/probe" "$work/Force.java" 2>/dev/null
off=$(java --patch-module java.base="$work/patch" -cp "$work/probe" Force java.lang.Zz)
on=$(java -Xverify:all --patch-module java.base="$work/patch" -cp "$work/probe" Force java.lang.Zz)
if [ "$off" = LINKED ] && [ "$on" = VerifyError ]; then
	ok "a boot loaded class is verified only under -Xverify:all (off: $off, on: $on)"
else
	bad "the -Xverify:all experiment gave off=$off on=$on, wanted LINKED and VerifyError"
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
