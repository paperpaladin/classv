#!/usr/bin/env python3
"""Vary the access flags of a class, a field and a method, and ask the JVM.

JVMS 4.1, 4.5 and 4.6 each have a paragraph of rules about which access
flags may appear together, and they are the sort of rules that are easy to
half implement.  Rather than write the table out and hope, this sets the
flags to each of a curated list of values and lets the JVM say which are
legal.

Every variant is renamed so that one class loader can hold the whole batch:
the fixture's name is three characters and so is each new one.

Both a class and an interface are swept, because JVMS 4.6 gives a method of
an interface different rules from a method of a class and sweeping only one
would measure half of them.

usage: flag_cases.py OUTDIR BASE.class...   -> writes OUTDIR/list.txt
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cf        # noqa: E402

# Single bits, every pair of access modifiers, and the combinations the spec
# calls out: abstract with each of the flags it excludes, interface without
# abstract, final and abstract together, and an undefined bit.
VALUES = [
    0x0000, 0x0001, 0x0002, 0x0004, 0x0008, 0x0010, 0x0020, 0x0040, 0x0080,
    0x0100, 0x0200, 0x0400, 0x0800, 0x1000, 0x2000, 0x4000, 0x8000,
    0x0003, 0x0005, 0x0006, 0x0007,
    0x0401, 0x0402, 0x0408, 0x0410, 0x0420, 0x0500, 0x0c00,
    0x0011, 0x0051, 0x0601, 0x0621, 0x8101, 0x1041, 0x0141,
]


D36 = '0123456789abcdefghijklmnopqrstuvwxyz'


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    paths = []
    for base in sys.argv[2:]:
        c = cf.load(base)
        name = c.class_name(c.u2(c.this_off))
        if len(name) != 3:
            sys.exit('the fixture class name must be three characters, not %r' % name)
        idx = [i for i, v in c.utf8.items() if v == name.encode()]
        if not idx:
            sys.exit('cannot find the name Utf8 of %s' % name)
        nameoff = c.pool[idx[0]][2] + 2
        where = {'class': c.access_off}
        for m in c.methods:
            if m.name == 'm':
                where['method'] = m.flags_off
        for f in c.fields:
            if f.name in ('f', 'F'):
                where['field'] = f.flags_off
        n = 0
        for kind, off in sorted(where.items()):
            for v in VALUES:
                d = bytearray(c.data)
                d[off] = (v >> 8) & 0xff
                d[off + 1] = v & 0xff
                nm = name[0] + D36[n // 36] + D36[n % 36]
                d[nameoff:nameoff + 3] = nm.encode()
                p = os.path.join(out, '%s.class' % nm)
                with open(p, 'wb') as fh:
                    fh.write(bytes(d))
                paths.append(p)
                n += 1
    with open(os.path.join(out, 'list.txt'), 'w') as fh:
        fh.write('\n'.join(paths) + '\n')
    print('--- flag sweep: %d variants over %d values' % (len(paths), len(VALUES)))


main()
