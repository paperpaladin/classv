#!/usr/bin/env python3
"""Bump the first count in each attribute, and let the JVM say which matter.

An attribute whose shape is a count followed by fixed size entries has a
length its count determines.  Which attributes HotSpot checks that way is
not something to list from memory: this bumps the first two bytes of every
distinct attribute in a fixture by one and lets the JVM sort them out.  Code,
Signature and SourceFile come back accepted, because their first two bytes
are not a count, and that is as much a part of the measurement as the
rejections.

usage: attr_cases.py BASE.class OUTDIR   -> prints "ATTRIBUTE PATH" per case
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cf        # noqa: E402


def every(c):
    for a in c.attrs:
        yield a
    for m in list(c.fields) + list(c.methods):
        for a in m.attrs:
            yield a
        if m.code:
            for a in m.code.attrs:
                yield a


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    base, out = sys.argv[1], sys.argv[2]
    c = cf.load(base)
    os.makedirs(out, exist_ok=True)
    seen = set()
    for a in every(c):
        if a.name in seen or a.length < 2:
            continue
        seen.add(a.name)
        d = bytearray(c.data)
        n = c.u2(a.off) + 1
        d[a.off], d[a.off + 1] = (n >> 8) & 0xff, n & 0xff
        p = os.path.join(out, 'attr_%s.class' % a.name)
        with open(p, 'wb') as f:
            f.write(bytes(d))
        print('%s %s' % (a.name, p))


main()
