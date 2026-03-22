#!/usr/bin/env python3
"""Corrupt real class files at random and require classv to stay civil.

A class file is attacker controlled input in any tool that reads one from a
stranger.  The property here is not that the verdict is right -- for a
randomly scrambled file there is often no right verdict to have -- but that
classv answers in its own vocabulary: one status word per file, nothing on
stderr, and no Java exception escaping the parser.  An
ArrayIndexOutOfBoundsException out of a bounds checked cursor is a bug
report, not a diagnosis.

Every mutant is generated from a seeded Random, and the digest printed is of
the mutant bytes themselves, not of the report about them: a seed that was
ignored would show as the same digest for every seed.

usage: fuzz.py 'CLASSV CMD' SOURCELIST N SEED OUTDIR
"""
import hashlib
import os
import random
import subprocess
import sys

WORDS = ('OK', 'VERIFY', 'FORMAT', 'REFUSE')


def corrupt(rng, data):
    """One mutation, described by the name of what it did."""
    d = bytearray(data)
    kind = rng.randrange(5)
    if kind == 0:
        n = rng.randrange(1, max(2, len(d)))
        del d[len(d) - n:]
        return d, 'truncated to %d bytes' % len(d)
    if kind == 1:
        i = rng.randrange(len(d))
        d[i] ^= 1 << rng.randrange(8)
        return d, 'one bit at %d' % i
    if kind == 2:
        i = rng.randrange(len(d))
        d[i] = rng.randrange(256)
        return d, 'one byte at %d' % i
    if kind == 3:
        i = rng.randrange(len(d))
        n = min(len(d) - i, rng.randrange(2, 17))
        for k in range(n):
            d[i + k] = rng.randrange(256)
        return d, '%d bytes at %d' % (n, i)
    i = rng.randrange(len(d))
    n = min(len(d) - i, rng.randrange(1, 9))
    del d[i:i + n]
    return d, 'deleted %d bytes at %d' % (n, i)


def main():
    if len(sys.argv) < 6:
        sys.exit(__doc__)
    classv_cmd, sourcelist, n, seed, outdir = sys.argv[1:6]
    n = int(n)
    rng = random.Random(int(seed))
    sources = [p for p in open(sourcelist).read().split('\n') if p]
    digest = hashlib.sha256()
    os.makedirs(outdir, exist_ok=True)
    paths, why = [], {}
    for i in range(n):
        src = sources[i % len(sources)]
        with open(src, 'rb') as f:
            data = f.read()
        d, desc = corrupt(rng, data)
        digest.update(bytes(d))
        p = os.path.join(outdir, 'f%05d.class' % i)
        with open(p, 'wb') as f:
            f.write(bytes(d))
        paths.append(p)
        why[p] = '%s of %s' % (desc, os.path.basename(src))
    lst = os.path.join(outdir, 'list.txt')
    with open(lst, 'w') as f:
        f.write('\n'.join(paths) + '\n')
    print('--- fuzz: %d mutants from %d sources, seed %s, sha256 of the mutant bytes %s'
          % (n, len(sources), seed, digest.hexdigest()[:16]))

    r = subprocess.run(classv_cmd.split() + ['--all', '@' + lst],
                       capture_output=True, text=True, errors='replace')
    verdicts = {}
    path = None
    counts = {}
    for ln in r.stdout.split('\n'):
        if ln.startswith('== '):
            path = ln[3:]
        else:
            w = ln.split(' ')[0]
            if w in WORDS and path is not None and path not in verdicts:
                verdicts[path] = w
                counts[w] = counts.get(w, 0) + 1
    print('--- fuzz: %s' % ', '.join('%s %d' % kv for kv in sorted(counts.items())))

    bad = 0
    missing = [p for p in paths if p not in verdicts]
    if r.stderr.strip():
        print('FAIL fuzz: classv wrote to stderr: %s' % r.stderr.strip()[:300])
        bad += 1
    if r.returncode not in (0, 1, 3, 4):
        print('FAIL fuzz: classv exited %d over the batch' % r.returncode)
        bad += 1
    if missing:
        # Name the file rather than the count: one uncaught exception ends
        # the whole batch, so the first unanswered path is the culprit and
        # the rest are its casualties.
        p = missing[0]
        print('FAIL fuzz: no verdict for %d of %d files, first %s (%s)'
              % (len(missing), len(paths), p, why[p]))
        one = subprocess.run(classv_cmd.split() + ['--all', p],
                             capture_output=True, text=True, errors='replace')
        print('      alone: exit %d, stdout %s, stderr %s'
              % (one.returncode, one.stdout.strip()[:200].replace('\n', ' | '),
                 one.stderr.strip()[:400].replace('\n', ' | ')))
        bad += 1
    print('FUZZ_PASS %d' % len(verdicts))
    print('FUZZ_FAIL %d' % bad)
    sys.exit(1 if bad else 0)


main()
