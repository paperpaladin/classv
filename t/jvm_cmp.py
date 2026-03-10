#!/usr/bin/env python3
"""Compare classv's verdict with the JVM's, class file by class file.

This is the headline comparison.  Three outcomes are kept apart and never
added together:

  AGREE       both accept, or both reject
  DISAGREE    one accepts and the other rejects.  Split into false reject
              (the JVM accepts, classv rejects) and false accept (the JVM
              rejects, classv accepts), because they are not equally bad
  REFUSE      classv declined to analyse the class, or the JVM could not be
              asked about it

A verifier that refuses most of its corpus and agrees about the rest has not
agreed about anything much, so the refusal counts are printed on their own
line and never folded into the agreement.

usage: jvm_cmp.py LISTFILE MODE 'CLASSV CMD' 'PROBE CMD'
"""
import re
import subprocess
import sys

REJECTS = ('VERIFY', 'FORMAT')


def run_probe(probe_cmd, mode, listfile, roots=''):
    r = subprocess.run(probe_cmd.split() + [mode, listfile, roots],
                       capture_output=True, text=True, errors='replace')
    out = {}
    for ln in r.stdout.split('\n'):
        if not ln:
            continue
        parts = ln.split('\t')
        out[parts[1]] = (parts[0], parts[2] if len(parts) > 2 else '')
    return out, r


def run_classv(classv_cmd, listfile):
    r = subprocess.run(classv_cmd.split() + ['--verify', '@' + listfile],
                       capture_output=True, text=True, errors='replace')
    out = {}
    path = None
    for ln in r.stdout.split('\n'):
        if ln.startswith('== '):
            path = ln[3:]
        elif ln[:2] in ('OK', 'VE', 'FO', 'RE') and ' ' in ln and path is not None:
            word, _, rest = ln.partition(' ')
            if word in ('OK', 'VERIFY', 'FORMAT', 'REFUSE'):
                out[path] = (word, rest)
    return out, r


class Tally:
    def __init__(self):
        self.agree_accept = 0
        self.agree_reject = 0
        self.agree_reject_samecat = 0
        self.false_reject = []
        self.false_accept = []
        self.classv_refuse = {}
        self.refused_jvm = {}
        self.oracle_na = {}
        self.jvm_other = 0
        self.missing = 0
        # Agreeing that a class is bad while pointing at a different
        # instruction is a much weaker claim than the verdict reads as, so
        # the bci is compared whenever both sides name one.
        self.bci_same = 0
        self.bci_target = 0
        self.bci_none = 0
        self.bci_method = 0
        self.bci_frame = 0
        self.bci_diff = []

    def add(self, path, jvm, cv):
        jk, jm = jvm
        ck, cm = cv
        if jk == 'ORACLE_NA':
            self.oracle_na[first(jm)] = self.oracle_na.get(first(jm), 0) + 1
            return
        if ck == 'REFUSE':
            self.classv_refuse[first(cm)] = self.classv_refuse.get(first(cm), 0) + 1
            # What the JVM thought of the files classv declined to look at.
            # A refusal over files the JVM also cannot accept would be a much
            # smaller claim than a refusal over files it accepts happily.
            self.refused_jvm[jk] = self.refused_jvm.get(jk, 0) + 1
            return
        if jk == 'OTHER':
            # A LinkageError about this class that is neither a format nor a
            # verification error, ClassCircularityError being the one that
            # turns up.  It is still a rejection: the JVM refused to have the
            # class.  Counting it as "no opinion" would have let 973 mutants
            # of one recipe pass unexamined.
            self.jvm_other += 1
        if jk == 'ACCEPT' and ck == 'OK':
            self.agree_accept += 1
        elif jk == 'ACCEPT':
            self.false_reject.append('%s: the JVM accepts it, classv says %s %s'
                                     % (path, ck, cm[:200]))
        elif ck == 'OK':
            self.false_accept.append('%s: the JVM says %s %s, classv accepts it'
                                     % (path, jk, jm[:160]))
        else:
            self.agree_reject += 1
            if jk == ck:
                self.agree_reject_samecat += 1
            if jk == 'VERIFY' and ck == 'VERIFY':
                self.bci(path, jm, cm)

    def bci(self, path, jm, cm):
        """Do the two sides name the same instruction of the same method?

        HotSpot prints a Location line, "X.f()V @11: goto"; classv prints the
        same shape.  The method is compared first, because a mutant with more
        than one broken method is not a disagreement about an instruction: see
        bci_method below.
        """
        j = WHERE.search(jm)
        c = WHERE.search(cm)
        if not j or not c:
            self.bci_none += 1
        elif j.group(1) != c.group(1):
            # Not a disagreement.  HotSpot verifies the methods of a class in
            # the order InstanceKlass holds them, which is sorted by name and
            # signature, not the order they appear in the file.  A mutation
            # that breaks every method of a class, max_stack minus one being
            # the obvious one, therefore gets a different first victim from
            # each side.  126 of 1698 looked like bci disagreements until the
            # method was compared, and every one of them was this.
            self.bci_method += 1
        elif j.group(2) == c.group(2):
            self.bci_same += 1
        elif curframe(jm) == c.group(2):
            # A bad stack map table is diagnosed before the walk starts, so
            # HotSpot's Location line says @0 and the instruction it was
            # really complaining about is in the Current Frame block.  classv
            # names that one.  Matched against HotSpot's own second field
            # rather than excused: 54 of 1698 are this, and the bci is exact
            # in every one of them.
            self.bci_frame += 1
        elif j.group(2) in NUM.findall(cm):
            # A frame merge failure is two instructions: the branch and its
            # target.  HotSpot names the target, classv names the branch and
            # says where it was going.  Counted apart rather than folded in,
            # because "the number appears somewhere in our message" is a
            # weaker claim than "we named the same instruction".
            self.bci_target += 1
        else:
            # A failure, not a note.  The three this found when it was first
            # written are all fixed, so the next one is news.
            self.bci_diff.append('%s: the JVM says @%s, classv says @%s | %s'
                                 % (path, j.group(2), c.group(2), cm[:150]))

    def report(self, label):
        for m in self.false_accept[:10]:
            print('FAIL false accept %s' % m)
        for m in self.false_reject[:10]:
            print('FAIL false reject %s' % m)
        print('--- %s: %d agree accept, %d agree reject (%d in the same category), '
              '%d false accept, %d false reject'
              % (label, self.agree_accept, self.agree_reject,
                 self.agree_reject_samecat, len(self.false_accept),
                 len(self.false_reject)))
        if self.classv_refuse:
            tot = sum(self.classv_refuse.values())
            print('--- %s: classv refused %d' % (label, tot))
            for k, v in sorted(self.classv_refuse.items(), key=lambda x: -x[1])[:6]:
                print('      %6d  %s' % (v, k))
        if self.classv_refuse:
            print('--- %s: of the refused, the JVM said %s'
                  % (label, ', '.join('%s %d' % (k, v)
                                      for k, v in sorted(self.refused_jvm.items()))))
        if self.oracle_na:
            tot = sum(self.oracle_na.values())
            print('--- %s: the JVM had no opinion on %d' % (label, tot))
            for k, v in sorted(self.oracle_na.items(), key=lambda x: -x[1])[:6]:
                print('      %6d  %s' % (v, k))
        if self.jvm_other:
            print('--- %s: of the rejections, %d were a LinkageError that is neither a '
                  'format nor a verification error' % (label, self.jvm_other))
        n = (self.bci_same + self.bci_target + self.bci_none + self.bci_method
             + self.bci_frame + len(self.bci_diff))
        if n:
            for m in self.bci_diff[:8]:
                print('FAIL bci %s' % m)
            print('--- %s: of %d where both report a verification error, %d name the '
                  'same instruction of the same method, %d match the bci in the JVM\'s '
                  'Current Frame rather than its Location, %d name the branch whose '
                  'target the JVM named, %d name a different instruction of that '
                  'method, %d are a class with more than one broken method where the '
                  'two sides got to a different one first, %d where one side named '
                  'no instruction'
                  % (label, n, self.bci_same, self.bci_frame, self.bci_target,
                     len(self.bci_diff), self.bci_method, self.bci_none))

    @property
    def failures(self):
        return len(self.false_accept) + len(self.false_reject) + len(self.bci_diff)


# "com/sun/crypto/provider/SunJCE.<init>()V @6" -> the method and the bci.
WHERE = re.compile(r'([\w$/]+\.[\w$<>]+\([^)]*\)\S*)\s+@(\d+)')
NUM = re.compile(r'\d+')
# HotSpot prints the frame it was holding as well as the Location, and for a
# bad stack map table the two are different instructions.
CURFRAME = re.compile(r'bci: @(\d+)')


def curframe(msg):
    m = CURFRAME.search(msg)
    return m.group(1) if m else None


def first(msg):
    """A message boiled down to something countable."""
    m = msg.split('|')[0]
    m = m.split(';')[0]
    if len(m) > 110:
        m = m[:110]
    return m


def compare(listfile, mode, classv_cmd, probe_cmd, tally=None, roots=''):
    jvm, jr = run_probe(probe_cmd, mode, listfile, roots)
    cv, cr = run_classv(classv_cmd, listfile)
    t = tally or Tally()
    paths = [p for p in open(listfile).read().split('\n') if p]
    for p in paths:
        j = jvm.get(p)
        c = cv.get(p)
        if j is None or c is None:
            t.missing += 1
            continue
        t.add(p, j, c)
    return t, jr, cr


def main():
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    listfile, mode, classv_cmd, probe_cmd = sys.argv[1:5]
    expect_refuse = len(sys.argv) > 5 and sys.argv[5] == 'refuse'
    roots = sys.argv[6] if len(sys.argv) > 6 else ''
    t, jr, cr = compare(listfile, mode, classv_cmd, probe_cmd, roots=roots)
    t.report('the JVM vs classv')
    if expect_refuse:
        # The corpus is one classv is supposed to decline, so the thing being
        # measured is that it declines every one of them AND that the JVM
        # accepts them anyway.  A missing feature does not announce itself by
        # a missing error message; it announces itself by a confident wrong
        # answer, so an answer here is the failure.
        answered = t.agree_accept + t.agree_reject + t.failures
        refused = sum(t.classv_refuse.values())
        ok_refusals = t.refused_jvm.get('ACCEPT', 0)
        bad = 0
        if answered:
            print('FAIL classv answered about %d class files it has no algorithm for'
                  % answered)
            bad += 1
        if ok_refusals < 100:
            print('FAIL only %d refusals of class files the JVM accepts; too few to '
                  'measure a refusal' % ok_refusals)
            bad += 1
        print('JVM_PASS %d' % refused)
        print('JVM_FAIL %d' % bad)
        sys.exit(1 if bad else 0)
    if t.missing:
        print('FAIL %d class files got no answer from one side or the other' % t.missing)
    n = t.agree_accept + t.agree_reject
    if n == 0:
        print('FAIL the comparison was vacuous: nothing was compared')
        print('JVM_PASS 0')
        print('JVM_FAIL 1')
        sys.exit(1)
    print('JVM_PASS %d' % n)
    print('JVM_FAIL %d' % (t.failures + (1 if t.missing else 0)))
    sys.exit(1 if t.failures or t.missing else 0)


if __name__ == '__main__':
    main()
