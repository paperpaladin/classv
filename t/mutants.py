#!/usr/bin/env python3
"""Plant a defect in classv, run the suite, and require it to go red.

A suite that has never been seen to fail may be checking nothing.  Each
entry below is one edit to classv's own source, aimed at a rule the suite
claims to measure, and the question is not whether the edit is realistic
but whether any assertion notices it.  The ones that survive are the
interesting output and are written down in the README rather than quietly
dropped.

The work happens in a copy of the tree, so the repository is untouched and
the run can go on in the background.  The suite runs over a trimmed corpus
(CLASSV_N), because twenty rebuilds against the whole module image would
take an hour and the question each run asks does not need it.

usage: mutants.py [NAME...]
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

V = 'src/classv/Verifier.java'
C = 'src/classv/ClassFile.java'
H = 'src/classv/Hierarchy.java'
T = 'src/classv/VType.java'
O = 'src/classv/Op.java'

# name, file, the text to replace, what to replace it with
MUTANTS = [
    ('interface-strict', H,
     'if (isInterface(to.name)) return true;',
     'if (isInterface(to.name)) return isJavaSubclassOf(from.name, to.name);'),
    ('array-not-object', H,
     'if (fromArray) return to.name.equals(OBJECT);',
     'if (fromArray) return false;'),
    ('return-uninit-this', V,
     'if (f.thisUninit)\n                    throw new Fail(-1, "return from "',
     'if (false)\n                    throw new Fail(-1, "return from "'),
    ('putfield-inherited', V,
     '&& declaresField(cf.pool.refName(in.a), cf.pool.refDesc(in.a))',
     '&& true'),
    ('utf8-accepted', C,
     'if (bad[0])\n                        // JVMS 4.4.7',
     'if (false)\n                        // JVMS 4.4.7'),
    ('class-name-any', C,
     'if (!legalClassName(n))\n                throw new ClassFormatException(0,'
     ' "constant pool entry " + i',
     'if (false)\n                throw new ClassFormatException(0,'
     ' "constant pool entry " + i'),
    ('attr-length', C,
     'if (a.data.length != want)',
     'if (false)'),
    ('pool-refs', C,
     'private static void checkPoolRefs(ConstantPool p) throws ClassFormatException {',
     'private static void checkPoolRefs(ConstantPool p) throws ClassFormatException {\n'
     '        if (true) return;'),
    ('debug-tables', C,
     'if (!lvt && !lvtt && !a.name.equals("LineNumberTable")) return;',
     'if (true) return;'),
    ('desc-return-indexof', C,
     'int i = 1;\n        int[] end = new int[1];\n'
     '        while (i < desc.length() && desc.charAt(i) != \')\') {\n'
     '            VType.fromDescriptor(desc.substring(i), end);\n'
     '            i += end[0];\n        }',
     'int i = desc.indexOf(\')\');\n        if (i < 0) i = desc.length();'),
    ('clinit-flags', V,
     "if (m.name.equals(\"<clinit>\") && cf.major >= 51)",
     "if (false)"),
    ('flag-rules', V,
     'private Result flagRules() {',
     'private Result flagRules() {\n        if (true) return null;'),
    ('sealed', V,
     'private Result sealing(String sup, String what) throws Refusal {',
     'private Result sealing(String sup, String what) throws Refusal {\n'
     '        if (true) return null;'),
    ('superclass-kind', V,
     'if (h.isInterface(cf.superClass))\n            return new Result(Kind.FORMAT,',
     'if (false)\n            return new Result(Kind.FORMAT,'),
    ('preview-allowed', V,
     'if (cf.major >= 56 && cf.minor != 0)',
     'if (false)'),
    ('member-descriptors', C,
     'private static void checkMember(Member m, boolean method)\n'
     '            throws ClassFormatException {',
     'private static void checkMember(Member m, boolean method)\n'
     '            throws ClassFormatException {\n        if (true) return;'),
    ('invokeinterface-byte', V,
     'if (in.c != 0)',
     'if (false)'),
    ('handler-bci', V,
     'throw new Fail(pc, "no stack map frame is declared at the exception"',
     'throw new Fail(hd.handler, "no stack map frame is declared at the exception"'),
    ('no-frame-detail', V,
     'if (e.detail.isEmpty()) e.at("frame here", before);\n                throw e;',
     'throw e;'),
    ('branch-base', O,
     'case BR2: in.a = in.pc + b.s2(); break;',
     'case BR2: in.a = b.pos() + b.s2(); break;'),
    ('two-word-top', V,
     'if (t.isTwoWord()) f.locals[i + 1] = VType.TOP;',
     'if (false) f.locals[i + 1] = VType.TOP;'),
    ('handler-range', V,
     'if (pc < hd.start || pc >= hd.end) continue;',
     'if (pc < hd.start || pc > hd.end) continue;'),
]

ENV = {
    'CLASSV_N': '600', 'CLASSV_MIN': '300', 'ASSIGN_N': '300',
    'MUT_N': '40', 'MUT_MIN': '30', 'CLASSV_FUZZ_N': '400',
    'CLASSV_FUZZ_MAXFA': '99',
}


def run(work, name):
    build = subprocess.run(['make'], cwd=work, capture_output=True, text=True)
    if build.returncode != 0:
        return 'DID NOT BUILD', build.stderr.strip().split('\n')[0][:120]
    env = dict(os.environ)
    env.update(ENV)
    # The staleness check in t/run.sh asks whether every pattern in this
    # table still appears in the source it names. A mutation replaces one of
    # those patterns, so during a sweep that check is certain to fail, the
    # suite goes red for that reason alone, and a red suite is what this
    # function reads as the mutant having been caught. Without this flag
    # every mutant scores as killed and the sweep establishes only that the
    # mutation was applied. extv ran two forty-minute sweeps that way.
    env['CLASSV_MUTATION_RUN'] = '1'
    r = subprocess.run(['sh', 't/run.sh'], cwd=work, capture_output=True, text=True,
                       env=env)
    first = ''
    for ln in r.stdout.split('\n'):
        if ln.startswith('FAIL') or ln.startswith('bad '):
            first = ln[:150]
            break
    tail = [ln for ln in r.stdout.split('\n') if ' passed, ' in ln]
    return ('CAUGHT' if r.returncode != 0 else 'SURVIVED',
            first or (tail[-1] if tail else 'no output'))


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def check():
    """Every entry's pattern still appears exactly once in the file it names.

    The sweep asks this one mutant at a time, in the middle of a run that
    recompiles and reruns the whole suite per mutant, so it is not in `make
    test` and nothing asks it between sweeps. An entry whose pattern has been
    refactored away is then neither killed nor surviving: it does nothing,
    and the score is over a smaller table than it names. yamlc had five in
    that state, one for over a month.

    This asks it about all of them in well under a second.
    """
    src, bad = {}, []
    for name, f, old_text, _new in MUTANTS:
        if f not in src:
            src[f] = open(os.path.join(ROOT, f)).read()
        n = src[f].count(old_text)
        if n != 1:
            bad.append((name, f, n, old_text.strip()[:58]))
    for name, f, n, snip in bad:
        print("STALE %-22s %s: appears %d times, not once: %s"
              % (name, f, n, snip))
    if bad:
        print("mutants: %d of %d entries no longer apply to the source"
              % (len(bad), len(MUTANTS)))
        return 1
    print("mutants: %d entries, every pattern applies exactly once"
          % len(MUTANTS))
    return 0


def main():
    if '--check' in sys.argv:
        return check()
    want = set(sys.argv[1:])
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    work = tempfile.mkdtemp(prefix='classv-mutants-')
    shutil.copytree(root, os.path.join(work, 'tree'),
                    ignore=shutil.ignore_patterns('.git', 'build', 'scratch', '__pycache__'))
    work = os.path.join(work, 'tree')
    originals = {f: open(os.path.join(work, f)).read() for f in (V, C, H, T, O)}
    caught = survived = stale = 0
    for name, f, old, new in MUTANTS:
        if want and name not in want:
            continue
        for g, text in originals.items():
            with open(os.path.join(work, g), 'w') as fh:
                fh.write(text)
        src = originals[f]
        if src.count(old) != 1:
            # Counted, and fatal. This used to print and `continue`, so a
            # pattern the source had moved past was neither caught nor
            # survived: it left the tally silently, the summary line just
            # read a smaller number, and the exit status stayed 0. yamlc had
            # five entries in that state, one for over a month, and its
            # published score of 73 killed out of 74 covered a table where
            # five entries tested nothing.
            #
            # An ambiguous pattern is the same defect in the other
            # direction: matching twice means the mutation lands somewhere
            # the entry did not name.
            print('%-22s NOT APPLIED  the text appears %d times'
                  % (name, src.count(old)))
            stale += 1
            continue
        with open(os.path.join(work, f), 'w') as fh:
            fh.write(src.replace(old, new))
        verdict, why = run(work, name)
        print('%-22s %-9s %s' % (name, verdict, why), flush=True)
        if verdict == 'SURVIVED':
            survived += 1
        else:
            caught += 1
    print('--- mutants: %d caught, %d survived, %d did not apply'
          % (caught, survived, stale))
    if stale:
        print('--- a mutation whose pattern no longer matches tests nothing,'
              ' so the table covers %d of %d entries' % (caught + survived,
                                                         caught + survived + stale))
    shutil.rmtree(os.path.dirname(work), ignore_errors=True)
    sys.exit(1 if (survived or stale) else 0)


# sys.exit(main()), not a bare call. main() was invoked for its side effects
# and its return value dropped, which was harmless while every exit went
# through the sys.exit inside it, and silently wrong the moment --check
# returned a status instead. A check that reports a defect and exits 0 is
# worse than no check: it prints the finding into output nobody reads the
# status of.
sys.exit(main())
