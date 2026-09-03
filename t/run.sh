#!/bin/sh
# classv test suite.
#
# Everything here is measured against something outside classv:
#   javap   -- the JDK's own disassembler, for the parse and the bytecode
#   java    -- the real HotSpot verifier, through t/Probe.java
#   Class.isAssignableFrom -- the JVM's own subtype relation, for the part of
#              JVMS 4.10.1.2 that is a question about the class hierarchy
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
	$PROBE define "$work/one.txt" "${2:-}" 2>/dev/null | head -1
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

# The putfield rule for an uninitialized this.  Two classes that differ only
# in whether the field is declared locally or inherited, with the superclass
# constructor call removed from each: HotSpot rejects the inherited one at
# the putfield and gets as far as the return on the local one.  The letter of
# JVMS 4.10.1.9 allows both.  The check is that classv says what HotSpot
# says, instruction included, rather than what the spec says.
javac -d "$work/uninit" "$here/fixtures/Uninit.java" 2>&1 || { echo "cannot build Uninit"; exit 2; }
for pair in "Sub putfield" "Own return"; do
	cls=${pair% *}; want=${pair#* }
	python3 "$here/mutate.py" noinit "$work/uninit/$cls.class" "$work/u.class" >/dev/null 		|| { bad "noinit does not apply to $cls"; continue; }
	jvm=$(probe_one "$work/u.class" "$work/uninit")
	bci=$(printf '%s' "$jvm" | sed -n 's/.*@\([0-9]*\): *'"$want"'.*/\1/p')
	if [ -z "$bci" ]; then
		bad "the JVM did not blame a $want in $cls: $(printf '%s' "$jvm" | cut -c1-160)"
		continue
	fi
	ours=$(java -cp "$root/build" classv.Main --cp "$work/uninit" --verify "$work/u.class" 2>&1)
	case $ours in
	VERIFY*"@$bci: $want"*)
		ok "classv and the JVM both blame the $want at @$bci of $cls.<init>" ;;
	*)
		bad "the JVM blames the $want at @$bci of $cls.<init>, classv says $(printf '%s' "$ours" | cut -c1-160)" ;;
	esac
done

# JVMS 5.3.5: the superclass may be neither an interface nor final.  Both
# need the superclass itself and not just its name, and both are built the
# way they happen in life rather than by patching bytes: compile Sub against
# an ordinary Base, then put a differently declared Base beside it.
mkdir -p "$work/sup/a" "$work/sup/b" "$work/sup/c" "$work/sup/iface" "$work/sup/final"
printf 'class Base { }\nclass Sub extends Base { }\n' > "$work/sup/A.java"
printf 'public interface Base { }\n' > "$work/sup/b/Base.java"
printf 'public final class Base { }\n' > "$work/sup/c/Base.java"
javac -d "$work/sup/a" "$work/sup/A.java" 2>/dev/null
javac -d "$work/sup/b" "$work/sup/b/Base.java" 2>/dev/null
javac -d "$work/sup/c" "$work/sup/c/Base.java" 2>/dev/null
cp "$work/sup/a/Sub.class" "$work/sup/b/Base.class" "$work/sup/iface/"
cp "$work/sup/a/Sub.class" "$work/sup/c/Base.class" "$work/sup/final/"
for kind in iface final; do
	jvm=$(probe_one "$work/sup/$kind/Sub.class" "$work/sup/$kind" | cut -f1)
	ours=$(java -cp "$root/build" classv.Main --cp "$work/sup/$kind" --verify \
		"$work/sup/$kind/Sub.class" | cut -d' ' -f1)
	if [ "$jvm" = OTHER ] && [ "$ours" = FORMAT ]; then
		ok "both reject a superclass that is $kind"
	else
		bad "a superclass that is $kind: the JVM says $jvm and classv says $ours"
	fi
done

# Access flags, JVMS 4.1, 4.5 and 4.6.  Each rule there is one sentence and
# the set of them is easy to half implement, so they are not checked against
# a table written here: the flags of a class, a field and a method, in both
# a class fixture and an interface fixture, are set to 35 values in turn and
# the JVM says which are legal.
javac -d "$work/flg" "$here/fixtures/Flg.java" "$here/fixtures/Ifc.java" 2>/dev/null
python3 "$here/flag_cases.py" "$work/flags" "$work/flg/Flg.class" "$work/flg/Ifc.class" \
	|| { echo "cannot build the flag cases"; exit 2; }
out=$(python3 "$here/jvm_cmp.py" "$work/flags/list.txt" define \
	"java -cp $root/build classv.Main --cp $work/flags" \
	"java -cp $work/probe Probe" verdict "$work/flags")
printf '%s\n' "$out" | grep -v '^JVM_' | sed "s/^--- the JVM vs classv/--- flag sweep/"
p=$(printf '%s\n' "$out" | sed -n 's/^JVM_PASS //p')
f=$(printf '%s\n' "$out" | sed -n 's/^JVM_FAIL //p')
[ -n "$p" ] || { echo "the flag sweep produced no count"; exit 2; }
[ "$p" -ge 150 ] || { echo "the flag sweep answered about only $p variants"; exit 2; }
pass=$((pass + p))
fail=$((fail + f))

# Sealing, JVMS 5.3.5 again: a sealed supertype permits a fixed list of
# subclasses.  Built the same way as the two above, by compiling Sub against
# an unsealed I and then putting a sealed I beside it.
mkdir -p "$work/seal/a" "$work/seal/b" "$work/seal/mix"
printf 'interface I { }\nclass Sub implements I { }\n' > "$work/seal/A.java"
printf 'sealed interface I permits Other { }\nfinal class Other implements I { }\n' \
	> "$work/seal/B.java"
javac -d "$work/seal/a" "$work/seal/A.java" 2>/dev/null
javac -d "$work/seal/b" "$work/seal/B.java" 2>/dev/null
cp "$work/seal/a/Sub.class" "$work/seal/mix/"
cp "$work/seal/b/I.class" "$work/seal/b/Other.class" "$work/seal/mix/"
jvm=$(probe_one "$work/seal/mix/Sub.class" "$work/seal/mix" | cut -f1)
ours=$(java -cp "$root/build" classv.Main --cp "$work/seal/mix" --verify \
	"$work/seal/mix/Sub.class" | cut -d' ' -f1)
if [ "$jvm" = OTHER ] && [ "$ours" = FORMAT ]; then
	ok "both reject a class that is not a permitted subclass of a sealed interface"
else
	bad "a class outside a sealed interface's permits list: the JVM says $jvm and classv says $ours"
fi

# Attribute lengths.  Every distinct attribute in a fixture gets the first
# two bytes of its body bumped by one, which is its entry count where it has
# one, and the JVM decides which of them matter.  Code, Signature and
# SourceFile come back accepted because those two bytes are not a count,
# and that half of the measurement is as useful as the rejections.
javac -g -d "$work/shapes" "$here/fixtures/Shapes.java" "$here/fixtures/Rec.java" 2>/dev/null
python3 "$here/attr_cases.py" "$work/shapes/Shapes.class" "$work/attrs" > "$work/attrs.cases" \
	|| { echo "cannot build the attribute cases"; exit 2; }
python3 "$here/attr_cases.py" "$work/shapes/Rec.class" "$work/attrs" >> "$work/attrs.cases" \
	|| { echo "cannot build the record attribute cases"; exit 2; }
nattr=0
while read -r name path; do
	jvm=$(probe_one "$path" "$work/attrs:$work/shapes" | cut -f1)
	ours=$(java -cp "$root/build" classv.Main --cp "$work/attrs" --cp "$work/shapes" \
		--verify "$path" | cut -d' ' -f1)
	nattr=$((nattr + 1))
	case "$jvm:$ours" in
	ACCEPT:OK)     ok "both accept a bumped count in $name" ;;
	FORMAT:FORMAT) ok "both reject a bumped count in $name" ;;
	*)             bad "a bumped count in $name: the JVM says $jvm and classv says $ours" ;;
	esac
done < "$work/attrs.cases"
[ "$nattr" -ge 16 ] || { echo "only $nattr attribute cases were run"; exit 2; }

# Class file versions, JVMS 4.1.  The minor version is the part nobody
# remembers: it must be 0 or 65535 from major 56 on, it may be anything at
# all below that, and 65535 means preview and needs a flag the oracle is not
# given.  A refusal is a legal answer here and is printed as one.
python3 "$here/version_cases.py" "$work/uninit/Own.class" "$work/vers" > "$work/vers.cases" \
	|| { echo "cannot build the version cases"; exit 2; }
nver=0
while read -r name major minor path; do
	jvm=$(probe_one "$path" "$work/vers" | cut -f1)
	ours=$(java -cp "$root/build" classv.Main --cp "$work/vers" --verify "$path" \
		| cut -d' ' -f1)
	nver=$((nver + 1))
	case "$jvm:$ours" in
	ACCEPT:OK)      ok "both accept version $major.$minor ($name)" ;;
	FORMAT:FORMAT)  ok "both reject version $major.$minor ($name)" ;;
	ACCEPT:REFUSE)  ok "classv refuses version $major.$minor ($name), which the JVM accepts" ;;
	*)              bad "version $major.$minor ($name): the JVM says $jvm and classv says $ours" ;;
	esac
done < "$work/vers.cases"
[ "$nver" -ge 10 ] || { echo "only $nver version cases were run"; exit 2; }

# Names, JVMS 4.2.1 for a class and 4.2.2 for a field or a method, in both
# directions: HotSpot rejects a '.' and accepts a space, and a reader who
# assumed Java identifiers would get both wrong.  The same eight byte
# sequences go into the class's own name, a field's and a method's, because
# the three rules differ: a '/' is a separator in a class name and illegal
# in the other two, and a '<' is legal in a field name and not in a method
# name.
python3 "$here/name_cases.py" "$work/flg/Flg.class" "$work/names" > "$work/names.cases" \
	|| { echo "cannot build the class name cases"; exit 2; }
nname=0
while read -r name path; do
	jvm=$(probe_one "$path" "$work/names" | cut -f1)
	ours=$(java -cp "$root/build" classv.Main --cp "$work/names" --verify "$path" \
		| cut -d' ' -f1)
	nname=$((nname + 1))
	case "$jvm:$ours" in
	ACCEPT:OK)     ok "both accept the name case $name" ;;
	FORMAT:FORMAT) ok "both reject the name case $name" ;;
	*)             bad "name case $name: the JVM says $jvm and classv says $ours" ;;
	esac
done < "$work/names.cases"
[ "$nname" -ge 24 ] || { echo "only $nname name cases were run"; exit 2; }

# Modified UTF-8, JVMS 4.4.7.  Which of these HotSpot rejects is measured
# here every run rather than written down once: the cases where it differs
# from real UTF-8 are exactly the ones a reader gets wrong, and 45 of 51
# false accepts in the first random corruption sweep were this.
python3 "$here/utf8_cases.py" "$work/uninit/Own.class" "$work/utf8" > "$work/utf8.txt" \
	|| { echo "cannot build the UTF-8 cases"; exit 2; }
nutf=0
while read -r name path; do
	jvm=$(probe_one "$path" "$work/utf8" | cut -f1)
	ours=$(java -cp "$root/build" classv.Main --verify "$path" 2>&1 | cut -d' ' -f1)
	nutf=$((nutf + 1))
	case "$jvm:$ours" in
	ACCEPT:OK)     ok "both accept the Utf8 case $name" ;;
	FORMAT:FORMAT) ok "both reject the Utf8 case $name" ;;
	*)             bad "Utf8 case $name: the JVM says $jvm and classv says $ours" ;;
	esac
done < "$work/utf8.txt"
[ "$nutf" -ge 10 ] || { echo "only $nutf UTF-8 cases were run"; exit 2; }

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

# ------------------------------- oracle 3: Class.isAssignableFrom, the lattice
echo
echo "--- oracle 3: Class.isAssignableFrom"
javac -d "$work/probe" -cp "$root/build" "$here/AssignCmp.java" 2>&1 	|| { echo "cannot build AssignCmp"; exit 2; }
sed -n "1,${ASSIGN_N:-3000}p" "$work/corpus.txt" 	| sed "s|$work/jdk/[a-z.]*/||;s|\.class$||;s|/|.|g" > "$work/names.txt"
out=$(java --add-modules ALL-SYSTEM -cp "$root/build:$work/probe" AssignCmp \
	"$work/names.txt" 2>"$work/assign.err")
grep -v 'WARNING: Using incubator modules' "$work/assign.err" | grep . && \
	bad "AssignCmp wrote to stderr"
echo "$out" | grep -v '^ASSIGN_'
p=$(printf '%s\n' "$out" | sed -n 's/^ASSIGN_PASS //p')
f=$(printf '%s\n' "$out" | sed -n 's/^ASSIGN_FAIL //p')
[ -n "$p" ] || { echo "AssignCmp produced no count"; exit 2; }
pass=$((pass + p))
fail=$((fail + f))

# -------------------------- oracle 4: the JVM's verdict against classv's own
#
# The headline comparison.  The corpus is asked about through the real system
# class loader, so there is no loader split and nothing has to be patched in.
echo
echo "--- oracle 4: the JVM's verdict against classv's"
CP=""
ROOTS=""
for d in "$work"/jdk/*/; do
	CP="$CP --cp $d"
	ROOTS="$ROOTS:${d%/}"
done
ROOTS=${ROOTS#:}
out=$(python3 "$here/jvm_cmp.py" "$work/corpus.txt" system \
	"java -cp $root/build classv.Main $CP" \
	"java -Xverify:all --add-modules ALL-SYSTEM -cp $work/probe Probe")
echo "$out" | grep -v '^JVM_'
p=$(printf '%s\n' "$out" | sed -n 's/^JVM_PASS //p')
f=$(printf '%s\n' "$out" | sed -n 's/^JVM_FAIL //p')
[ -n "$p" ] || { echo "jvm_cmp.py produced no count"; exit 2; }
pass=$((pass + p))
fail=$((fail + f))

# ------------- oracle 4b: javac at four releases, with and without debug info
#
# The module image is one compiler at one release, which reaches a narrow set
# of the legal shapes.  The same source compiled at --release 8 and at 21 is
# two different class files: string concatenation alone moves from
# StringBuilder to an invokedynamic against StringConcatFactory.  Both the
# parse and the verdict are compared on each.
echo
echo "--- oracle 4b: javac --release 8, 11, 17, 21, with -g and -g:none"
for rel in 8 11 17 21; do
	for g in -g -g:none; do
		tag="$rel$(printf '%s' "$g" | tr -d ':-')"
		dir="$work/javac/$tag"
		mkdir -p "$dir"
		if ! javac --release "$rel" "$g" -d "$dir" "$here/fixtures/Shapes.java" \
				2>"$work/javac.err"; then
			bad "javac --release $rel $g cannot compile the Shapes fixture: $(head -2 "$work/javac.err")"
			continue
		fi
		find "$dir" -name '*.class' | sort > "$work/javac/$tag.txt"
		n=$(wc -l < "$work/javac/$tag.txt")
		[ "$n" -ge 5 ] || { bad "release $rel $g produced $n class files"; continue; }
		out=$(python3 "$here/javap_cmp.py" "$CLASSV" "$work/javac/$tag.txt" 100)
		printf '%s\n' "$out" | grep -v '^JAVAP_' | sed "s/^--- /--- release $rel $g: /"
		p=$(printf '%s\n' "$out" | sed -n 's/^JAVAP_PASS //p')
		f=$(printf '%s\n' "$out" | sed -n 's/^JAVAP_FAIL //p')
		[ -n "$p" ] || { bad "javap_cmp.py produced no count for release $rel $g"; continue; }
		pass=$((pass + p))
		fail=$((fail + f))
		out=$(python3 "$here/jvm_cmp.py" "$work/javac/$tag.txt" define \
			"java -cp $root/build classv.Main --cp $dir" \
			"java -cp $work/probe Probe")
		printf '%s\n' "$out" | grep -v '^JVM_' | sed "s/^--- the JVM vs classv/--- release $rel $g/"
		p=$(printf '%s\n' "$out" | sed -n 's/^JVM_PASS //p')
		f=$(printf '%s\n' "$out" | sed -n 's/^JVM_FAIL //p')
		[ -n "$p" ] || { bad "jvm_cmp.py produced no count for release $rel $g"; continue; }
		pass=$((pass + p))
		fail=$((fail + f))
	done
done

# ------------- oracle 4c: real class files from jars this JDK did not build
#
# Everything above came out of the javac on this machine.  An old jar is a
# class file from a compiler nobody here has, and the ones below major 50
# have no StackMapTable at all, which is the path classv refuses by name.
# A refusal needs an outside authority too: the JVM must accept these files
# happily, or the refusal would be hiding a rejection.
echo
echo "--- oracle 4c: jars"
JARS=${CLASSV_JARS:-$(find "$HOME/.cache" /usr/share/java /run/host/usr/share/java \
	-name '*.jar' 2>/dev/null | grep -v sources | head -40)}
if [ -z "$JARS" ]; then
	echo "SKIP no jars on this machine; the jar corpus is one oracle among several"
else
	mkdir -p "$work/jars"
	# shellcheck disable=SC2086
	python3 "$here/jars.py" "$work/jars" $JARS
	JCP=""
	JROOTS=""
	for jd in "$work"/jars/j*/; do
		JCP="$JCP --cp $jd"
		JROOTS="$JROOTS:${jd%/}"
	done
	JROOTS=${JROOTS#:}
	for part in old new; do
		n=$(wc -l < "$work/jars/$part.txt")
		if [ "$n" -lt 50 ]; then
			echo "SKIP only $n $part class files in the jar corpus"
			continue
		fi
		echo "--- $n $part class files"
		out=$(python3 "$here/jvm_cmp.py" "$work/jars/$part.txt" define \
			"java -cp $root/build classv.Main $JCP" \
			"java -cp $work/probe Probe" \
			"$([ "$part" = old ] && echo refuse || echo verdict)" "$JROOTS")
		printf '%s\n' "$out" | grep -v '^JVM_' | sed "s/^--- the JVM vs classv/--- jars, $part/"
		p=$(printf '%s\n' "$out" | sed -n 's/^JVM_PASS //p')
		f=$(printf '%s\n' "$out" | sed -n 's/^JVM_FAIL //p')
		[ -n "$p" ] || { bad "jvm_cmp.py produced no count for the $part jar corpus"; continue; }
		pass=$((pass + p))
		fail=$((fail + f))
	done
fi

# ------------------------------------------- oracle 5: the mutation sweep
#
# A corpus of valid classes can only catch classv rejecting something the JVM
# accepts.  This is the other direction: corrupt real classes in ways aimed
# at the rules of JVMS 4.10.1 and require both sides to reject them.
echo
echo "--- oracle 5: the mutation sweep"
# Every (module, package) pair exported to the unnamed module.  Without this
# a mutant whose superinterface lives in a module that exports nothing comes
# back IllegalAccessError, which is the harness and not a verdict.
cat > "$work/Exp.java" <<'JEOF'
public class Exp { public static void main(String[] a) {
  for (Module m : ModuleLayer.boot().modules())
    for (String p : m.getPackages())
      System.out.println("--add-exports=" + m.getName() + "/" + p + "=ALL-UNNAMED");
} }
JEOF
javac -d "$work/probe" "$work/Exp.java" 2>/dev/null
java --add-modules ALL-SYSTEM -cp "$work/probe" Exp 2>/dev/null > "$work/exports.args"
# java.* classes cannot be defined by a user class loader at all, so they
# cannot be mutation sources; the sample is drawn from the rest.
grep -v '/jdk/[a-z.]*/java/' "$work/corpus.txt" | awk 'NR % 7 == 1' \
	| head -"${MUT_N:-150}" > "$work/mutsrc.txt"
msrc=$(wc -l < "$work/mutsrc.txt")
echo "--- $msrc mutation sources"
[ "$msrc" -ge 50 ] || { echo "only $msrc mutation sources; too few to mean anything"; exit 2; }
out=$(CLASSV_ROOTS="$ROOTS" python3 "$here/mutation_sweep.py" "$work/mutsrc.txt" \
	"$work/mut" "java -cp $root/build classv.Main $CP" \
	"java @$work/exports.args -cp $work/probe Probe")
echo "$out" | grep -v '^MUT_'
p=$(printf '%s\n' "$out" | sed -n 's/^MUT_PASS //p')
f=$(printf '%s\n' "$out" | sed -n 's/^MUT_FAIL //p')
[ -n "$p" ] || { echo "mutation_sweep.py produced no count"; exit 2; }
pass=$((pass + p))
fail=$((fail + f))

# The error message is the point of the program, so the suite asserts the
# frames are actually printed rather than trusting that the code that makes
# them is reached.  A branch moved three bytes gives a frame that does not
# match the one the table declares at the target, which is the case that has
# two frames to print.
echo
echo "--- the frames under a failure"
nframe=0
nmerge=0
# java.* classes cannot be defined by a user class loader, so the sources
# are drawn from the rest: the JVM has to be able to answer about each one.
for src in $(grep -v '/jdk/[a-z.]*/java/' "$work/corpus.txt" | awk 'NR % 23 == 1' | head -60); do
  for recipe in branch:3 branch:-1; do
	python3 "$here/mutate.py" "$recipe" "$src" "$work/fr.class" >/dev/null 2>&1 || continue
	msg=$(java -cp "$root/build" classv.Main --cp "$work/jdk/java.base" --verify \
		"$work/fr.class")
	case $msg in
	VERIFY*) ;;
	*) continue ;;
	esac
	printf '%s' "$msg" | grep -q 'frame here:' && nframe=$((nframe + 1))
	if printf '%s' "$msg" | grep -q 'frame declared:'; then
		nmerge=$((nmerge + 1))
		[ "$nmerge" -eq 1 ] && printf '%s\n' "$msg" | sed 's/^/      /'
		jvm=$(probe_one "$work/fr.class" "$work/jdk/java.base" | cut -f1)
		[ "$jvm" = VERIFY ] || bad "the JVM does not reject the frame mismatch fixture: $jvm"
	fi
  done
done
if [ "$nframe" -ge 5 ] && [ "$nmerge" -ge 1 ]; then
	ok "$nframe rejections printed the frame, $nmerge of them printed both frames"
else
	bad "only $nframe rejections printed a frame and $nmerge printed both"
fi

# ------------------------------------- oracle 6: random corruption, no oracle
#
# There is no outside authority for this one and it does not need one: the
# property is about classv's own behaviour, not about the verdict.  A class
# file is attacker controlled input in any tool that reads one from a
# stranger, and the claim is that a scrambled one produces a status word and
# not a stack trace.  The seed is fixed so that a failure can be reproduced;
# the digest printed is of the mutant bytes, so a seed that stopped being
# used would show.
echo
echo "--- oracle 6: random corruption"
awk 'NR % 19 == 1' "$work/corpus.txt" | head -400 > "$work/fuzzsrc.txt"
# Those mutants also go to the JVM, in chunks of one per source class so
# that two files never claim the same name in one loader.  The remaining
# false accepts are structural rules HotSpot checks while parsing and
# classv does not yet; the number is a ratchet, named here, and the README
# lists what is still in it.
out=$(python3 "$here/fuzz.py" "java -cp $root/build classv.Main --cp $work/jdk/java.base" \
	"$work/fuzzsrc.txt" "${CLASSV_FUZZ_N:-2000}" "${CLASSV_FUZZ_SEED:-1}" "$work/fuzz" \
	"java -cp $work/probe Probe" "$work/jdk/java.base" "${CLASSV_FUZZ_MAXFA:-1}")
echo "$out" | grep -v '^FUZZ_'
p=$(printf '%s\n' "$out" | sed -n 's/^FUZZ_PASS //p')
f=$(printf '%s\n' "$out" | sed -n 's/^FUZZ_FAIL //p')
[ -n "$p" ] || { echo "fuzz.py produced no count"; exit 2; }
pass=$((pass + p))
fail=$((fail + f))

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
