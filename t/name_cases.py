#!/usr/bin/env python3
"""Build one class file per class-name edge case, for the JVM to judge.

JVMS 4.2.1 says what a class name may hold, and HotSpot is laxer than the
language: a space, a '<' and a leading digit are all fine in a class name
even though javac will never write one.  The cases a reader is likely to get
wrong are therefore in both directions, which is why they are measured here
every run rather than written down once.

The bytes go into the this_class name, which is three characters in the
fixture, so every case is three bytes and nothing else about the file moves.

usage: name_cases.py BASE.class OUTDIR   -> prints "NAME PATH" per case
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cf        # noqa: E402

CASES = {
    'ordinary': b'a/b',
    'dot': b'a.b',
    'semicolon': b'a;b',
    'bracket': b'a[b',
    'empty_component': b'a//',
    'angle': b'a<b',
    'space': b'a b',
    'leading_digit': b'1ab',
}


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    base, out = sys.argv[1], sys.argv[2]
    c = cf.load(base)
    want = c.class_name(c.u2(c.this_off)).encode()
    idx = [i for i, v in c.utf8.items() if v == want]
    if not idx:
        sys.exit('cannot find the this_class name of %s' % base)
    off = c.pool[idx[0]][2]
    n = c.u2(off)
    os.makedirs(out, exist_ok=True)
    for name, b in CASES.items():
        if len(b) != n:
            sys.exit('%s is %d bytes and the fixture name is %d' % (name, len(b), n))
        d = bytearray(c.data)
        d[off + 2:off + 2 + n] = b
        p = os.path.join(out, 'name_%s.class' % name)
        with open(p, 'wb') as f:
            f.write(bytes(d))
        print('%s %s' % (name, p))


main()
