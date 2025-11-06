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
        self.oracle_na = {}
        self.jvm_other = 0
        self.missing = 0

    def add(self, path, jvm, cv):
        jk, jm = jvm
        ck, cm = cv
        if jk == 'ORACLE_NA':
            self.oracle_na[first(jm)] = self.oracle_na.get(first(jm), 0) + 1
            return
        if ck == 'REFUSE':
            self.classv_refuse[first(cm)] = self.classv_refuse.get(first(cm), 0) + 1
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
        if self.oracle_na:
            tot = sum(self.oracle_na.values())
            print('--- %s: the JVM had no opinion on %d' % (label, tot))
            for k, v in sorted(self.oracle_na.items(), key=lambda x: -x[1])[:6]:
                print('      %6d  %s' % (v, k))
        if self.jvm_other:
            print('--- %s: of the rejections, %d were a LinkageError that is neither a '
                  'format nor a verification error' % (label, self.jvm_other))

    @property
    def failures(self):
        return len(self.false_accept) + len(self.false_reject)


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
    t, jr, cr = compare(listfile, mode, classv_cmd, probe_cmd)
    t.report('the JVM vs classv')
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
