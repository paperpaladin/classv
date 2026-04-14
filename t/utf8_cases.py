#!/usr/bin/env python3
"""Build one class file per modified UTF-8 edge case, for the JVM to judge.

JVMS 4.4.7 is not UTF-8 and the differences are exactly the interesting
cases: 0xc0 0x80 encodes NUL and is legal, an unpaired surrogate is legal,
a four byte sequence is not.  Which of the overlong forms HotSpot rejects is
not something to read off the spec and hope; each of these was built and
asked about, and the suite keeps asking so that the answer cannot drift.

The bytes land in the SourceFile value, which is the one Utf8 constant in
the fixture that nothing structural reads, so a rejection is about the
encoding and not about a broken name.

usage: utf8_cases.py BASE.class OUTDIR   -> prints "NAME PATH" per case
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cf        # noqa: E402

A = b'a'


def cases(n):
    return {
        'plain': A * n,
        'c0_80_is_nul': b'\xc0\x80' + A * (n - 2),
        'unpaired_surrogate': b'\xed\xa0\x80' + A * (n - 3),
        'bare_nul': b'\x00' + A * (n - 1),
        'overlong_two_byte': b'\xc0\xaf' + A * (n - 2),
        'overlong_two_byte_7f': b'\xc1\xbf' + A * (n - 2),
        'overlong_three_byte': b'\xe0\x80\xaf' + A * (n - 3),
        'four_byte_utf8': b'\xf0\x90\x80\x80' + A * (n - 4),
        'lone_continuation': b'\x80' + A * (n - 1),
        'truncated_lead': A * (n - 1) + b'\xc2',
    }


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    base, out = sys.argv[1], sys.argv[2]
    c = cf.load(base)
    src = None
    for a in c.attrs:
        if a.name == 'SourceFile':
            src = c.u2(a.off)
    if src is None:
        sys.exit('%s has no SourceFile attribute to borrow' % base)
    off = c.pool[src][2]
    n = c.u2(off)
    if n < 5:
        sys.exit('the SourceFile value is only %d bytes; too short for these cases' % n)
    os.makedirs(out, exist_ok=True)
    for name, b in cases(n).items():
        assert len(b) == n, name
        d = bytearray(c.data)
        d[off + 2:off + 2 + n] = b
        p = os.path.join(out, 'utf8_%s.class' % name)
        with open(p, 'wb') as f:
            f.write(bytes(d))
        print('%s %s' % (name, p))


main()
