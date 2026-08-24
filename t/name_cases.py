#!/usr/bin/env python3
"""Build one class file per class-name edge case, for the JVM to judge.

JVMS 4.2.1 says what a class name may hold, and HotSpot is laxer than the
language: a space, a '<' and a leading digit are all fine in a class name
even though javac will never write one.  The cases a reader is likely to get
wrong are therefore in both directions, which is why they are measured here
every run rather than written down once.

The same bytes go into three places: the class's own name, a field's name
and a method's name.  JVMS 4.2.2 gives the last two a different rule from
the first, so checking one place would measure a third of it.  All three
targets are three characters long in the fixture, so every case is three
bytes and nothing else about the file moves.

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


def utf8_at(c, text):
    idx = [i for i, v in c.utf8.items() if v == text.encode()]
    if not idx:
        sys.exit('cannot find the Utf8 %r' % text)
    return c.pool[idx[0]][2]


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    base, out = sys.argv[1], sys.argv[2]
    c = cf.load(base)
    where = {
        'class': utf8_at(c, c.class_name(c.u2(c.this_off))),
        'field': utf8_at(c, 'fld'),
        'method': utf8_at(c, 'met'),
    }
    os.makedirs(out, exist_ok=True)
    for kind, off in sorted(where.items()):
        n = c.u2(off)
        for name, b in CASES.items():
            if len(b) != n:
                sys.exit('%s is %d bytes and the %s name is %d' % (name, len(b), kind, n))
            d = bytearray(c.data)
            d[off + 2:off + 2 + n] = b
            p = os.path.join(out, 'name_%s_%s.class' % (kind, name))
            with open(p, 'wb') as f:
                f.write(bytes(d))
            print('%s_%s %s' % (kind, name, p))


main()
