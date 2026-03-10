#!/usr/bin/env python3
"""Unpack the .class files out of jars, split by class file version.

Everything else classv is measured against came out of the javac on this
machine.  A jar from 2008 is a class file written by a compiler nobody here
has, for a JVM nobody here runs, and the ones with a major version below 50
have no StackMapTable at all, which is the path classv refuses by name.

The split is the point: below 50 the refusal is the thing being measured,
and the JVM must still accept the file, or the refusal would be hiding a
rejection.

usage: jars.py OUTDIR JAR...   -> writes OUTDIR/old.txt and OUTDIR/new.txt
"""
import collections
import os
import struct
import sys
import zipfile


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out = sys.argv[1]
    hist = collections.Counter()
    old, new = [], []
    for n, jar in enumerate(sys.argv[2:]):
        try:
            z = zipfile.ZipFile(jar)
        except (OSError, zipfile.BadZipFile):
            continue
        for i in z.infolist():
            if not i.filename.endswith('.class') or i.filename.endswith('module-info.class'):
                continue
            b = z.read(i)
            if len(b) < 10 or b[:4] != b'\xca\xfe\xba\xbe':
                continue
            major = struct.unpack('>H', b[6:8])[0]
            hist[major] += 1
            p = os.path.join(out, 'j%02d' % n, i.filename)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, 'wb') as f:
                f.write(b)
            (old if major < 50 else new).append(p)
    for lst, name in ((old, 'old.txt'), (new, 'new.txt')):
        with open(os.path.join(out, name), 'w') as f:
            f.write('\n'.join(lst) + ('\n' if lst else ''))
    print('--- jar corpus: %d class files, by major version: %s'
          % (sum(hist.values()),
             ', '.join('%d: %d' % kv for kv in sorted(hist.items()))))


main()
