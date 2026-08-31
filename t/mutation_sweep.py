#!/usr/bin/env python3
"""Run a mutation sweep: corrupt real classes, ask the JVM, ask classv.

A corpus of valid classes can only catch classv rejecting something the JVM
accepts.  This is the other direction.  Each recipe is applied to every
source class in turn, producing one batch of mutants whose class names are
all distinct -- which matters, because the JVM oracle defines a whole batch
in one class loader and two files claiming the same name would collide.

Every recipe reports how many mutants it produced and what the JVM said
about them, because a recipe the JVM accepts is not testing the verifier at
all, and a recipe that never applies is not testing anything.

usage: mutation_sweep.py SOURCELIST OUTDIR 'CLASSV CMD' 'PROBE CMD' [RECIPE...]
"""
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jvm_cmp     # noqa: E402
import mutate      # noqa: E402

DEFAULT = [
    'subst:ireturn:areturn', 'subst:areturn:ireturn', 'subst:aload_0:iload_0',
    'subst:iload_0:aload_0', 'subst:istore_0:astore_0', 'subst:astore_0:istore_0',
    'subst:iadd:ladd', 'subst:ladd:iadd', 'subst:dup:dup2', 'subst:dup2:dup',
    'subst:pop:pop2', 'subst:pop2:pop', 'subst:return:athrow',
    'subst:iaload:aaload', 'subst:aaload:iaload', 'subst:i2l:l2i',
    'subst:arraylength:athrow', 'subst:monitorenter:athrow',
    'subst:nop:pop', 'subst:iconst_0:aconst_null', 'subst:aconst_null:iconst_0',
    'subst:getfield:arraylength',
    'maxstack:-1', 'maxlocals:-1',
    'smtdelta:1', 'smtdelta:-1', 'dropsmt',
    'smtitem:0', 'smtitem:1', 'smtitem:4', 'smtitem:5',
    'cpindex:1', 'cpindex:-1',
    'branch:1', 'branch:-1', 'branch:3',
    'iicount:1', 'iicount:-1', 'iizero',
    'handlertype', 'swapcode', 'noinit',
    'truncate:1', 'truncate:4', 'truncate:40',
    'superself', 'major:99', 'major:45',
]


def main():
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    sourcelist, outdir, classv_cmd, probe_cmd = sys.argv[1:5]
    roots = os.environ.get('CLASSV_ROOTS', '')
    recipes = sys.argv[5:] or DEFAULT
    sources = [p for p in open(sourcelist).read().split('\n') if p]
    mutate.javap_batch(sources)

    total = jvm_cmp.Tally()
    made_any = 0
    dead_recipes = []
    accepted_recipes = []
    for n, spec in enumerate(recipes):
        recipe, _, arg = spec.partition(':')
        d = os.path.join(outdir, 'r%02d' % n)
        os.makedirs(d, exist_ok=True)
        paths = []
        for i, src in enumerate(sources):
            try:
                data, _desc = mutate.apply(src, recipe, arg)
            except Exception:
                data = None
            if data is None:
                continue
            out = os.path.join(d, '%05d_%s' % (i, os.path.basename(src)))
            with open(out, 'wb') as f:
                f.write(data)
            paths.append(out)
        if not paths:
            dead_recipes.append(spec)
            continue
        made_any += len(paths)
        lst = os.path.join(d, 'list.txt')
        with open(lst, 'w') as f:
            f.write('\n'.join(paths) + '\n')
        t = jvm_cmp.Tally()
        jvm_cmp.compare(lst, 'define', classv_cmd, probe_cmd, t, roots)
        answered = t.agree_accept + t.agree_reject + t.failures
        print('  %-26s %5d mutants, %5d answered, %5d rejected by the JVM, '
              '%d false accept, %d false reject'
              % (spec, len(paths), answered, t.agree_reject + len(t.false_accept),
                 len(t.false_accept), len(t.false_reject)))
        for m in t.false_accept[:3]:
            print('FAIL false accept %s' % m)
        for m in t.false_reject[:3]:
            print('FAIL false reject %s' % m)
        if answered and t.agree_reject + len(t.false_accept) == 0:
            accepted_recipes.append(spec)
        total.agree_accept += t.agree_accept
        total.agree_reject += t.agree_reject
        total.agree_reject_samecat += t.agree_reject_samecat
        total.false_accept += t.false_accept
        total.false_reject += t.false_reject
        total.missing += t.missing
        total.jvm_other += t.jvm_other
        total.bci_same += t.bci_same
        total.bci_target += t.bci_target
        total.bci_none += t.bci_none
        total.bci_method += t.bci_method
        total.bci_frame += t.bci_frame
        total.bci_diff += t.bci_diff
        for k, v in t.classv_refuse.items():
            total.classv_refuse[k] = total.classv_refuse.get(k, 0) + v
        for k, v in t.refused_jvm.items():
            total.refused_jvm[k] = total.refused_jvm.get(k, 0) + v
        for k, v in t.oracle_na.items():
            total.oracle_na[k] = total.oracle_na.get(k, 0) + v
        shutil.rmtree(d, ignore_errors=True)

    total.report('mutation sweep')
    if dead_recipes:
        print('--- recipes that produced no mutant at all: %s' % ' '.join(dead_recipes))
    if accepted_recipes:
        print('--- recipes the JVM accepted every mutant of, so they test nothing: %s'
              % ' '.join(accepted_recipes))
    n = total.agree_accept + total.agree_reject
    print('--- mutation sweep: %d mutants built, %d answered about by both'
          % (made_any, n + total.failures))
    bad = total.failures
    if total.missing:
        # A mutant that neither side answered about is not a mutant that
        # agreed with anything.
        print('FAIL %d mutants got no answer from one side or the other' % total.missing)
        bad += 1
    if total.bci_same < 100:
        # The instruction comparison is the part of this sweep that can go
        # quiet without the verdict numbers moving at all: every mutant could
        # still be rejected by both sides while the two messages stopped
        # being comparable.
        print('FAIL the instruction comparison is vacuous: only %d mutants had '
              'their bci compared' % total.bci_same)
        bad += 1
    if total.agree_reject < 100:
        print('FAIL the sweep is vacuous: the JVM rejected only %d mutants'
              % total.agree_reject)
        bad += 1
    print('MUT_PASS %d' % n)
    print('MUT_FAIL %d' % bad)
    sys.exit(1 if bad else 0)


main()
