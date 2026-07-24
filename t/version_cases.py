#!/usr/bin/env python3
"""Build one class file per class file version, for the JVM to judge.

JVMS 4.1 says the minor version must be 0 or 65535 from major 56 onwards,
and that 65535 means a preview class file.  Below 56 any minor at all is
accepted.  Neither half is obvious and both were measured rather than
assumed: 52.5 and 55.1 link, 56.1 and 60.1 do not, and 65.65535 links only
under --enable-preview, which is a flag the oracle is not started with.

usage: version_cases.py BASE.class OUTDIR   -> prints "NAME MAJOR MINOR PATH"
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cf        # noqa: E402


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    base, out = sys.argv[1], sys.argv[2]
    c = cf.load(base)
    here = c.u2(c.major_off)
    cases = [
        ('current', here, 0),
        ('minor_one', here, 1),
        ('preview_current', here, 65535),
        ('preview_older', here - 5, 65535),
        ('minor_one_older', here - 5, 1),
        ('minor_one_at_55', 55, 1),
        ('minor_five_at_52', 52, 5),
        ('too_new', here + 1, 0),
        ('inference_path', 49, 3),
        ('version_fifty', 50, 0),
    ]
    os.makedirs(out, exist_ok=True)
    for name, major, minor in cases:
        d = bytearray(c.data)
        d[4], d[5] = minor >> 8, minor & 0xff
        d[6], d[7] = major >> 8, major & 0xff
        p = os.path.join(out, 'ver_%s.class' % name)
        with open(p, 'wb') as f:
            f.write(bytes(d))
        print('%s %d %d %s' % (name, major, minor, p))


main()
