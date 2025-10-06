#!/bin/sh
# classv test suite.
#
# Everything here is measured against something outside classv:
#   javap   -- the JDK's own disassembler, for the parse and the bytecode
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

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
