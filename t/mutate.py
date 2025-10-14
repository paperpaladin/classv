#!/usr/bin/env python3
"""Build corrupted class files, to be fed to the JVM and to classv.

A corpus of valid classes can only ever catch classv rejecting something the
JVM accepts.  The other direction -- classv accepting something the JVM
rejects -- needs invalid classes, and there is nowhere to get thousands of
those except by making them.

Instruction offsets come from `javap -c`, not from classv's own decoder.
Using the tool under test to place its own test input would not bias the
comparison (the JVM still gives the verdict), but javap is free and removes
the question.

usage: mutate.py RECIPE IN.class OUT.class [ARG]
       mutate.py --list
"""
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cf        # noqa: E402

# Opcode numbers for the substitution recipes.  Same length, different type.
OPC = {
    'nop': 0, 'aconst_null': 1, 'iconst_0': 3, 'lconst_0': 9, 'fconst_0': 11,
    'dconst_0': 14, 'iload_0': 26, 'lload_0': 30, 'fload_0': 34, 'dload_0': 38,
    'aload_0': 42, 'istore_0': 59, 'lstore_0': 63, 'astore_0': 75,
    'iadd': 96, 'ladd': 97, 'fadd': 98, 'dadd': 99,
    'ireturn': 172, 'lreturn': 173, 'freturn': 174, 'dreturn': 175,
    'areturn': 176, 'return': 177, 'pop': 87, 'pop2': 88, 'dup': 89, 'dup2': 92,
    'swap': 95, 'athrow': 191, 'arraylength': 190, 'monitorenter': 194,
    'iaload': 46, 'aaload': 50, 'iastore': 79, 'aastore': 83,
    'i2l': 133, 'i2f': 134, 'l2i': 136,
}

INSN_RE = re.compile(r'^\s+(\d+): (\S+)')


def javap_insns(path):
    """-> list of (method_index, pc, mnemonic), method_index counting methods
    with a Code attribute in file order."""
    r = subprocess.run(['javap', '-c', '-p', path], capture_output=True,
                       text=True, errors='replace')
    out = []
    mi = -1
    in_code = False
    for ln in r.stdout.split('\n'):
        if re.match(r'^\s+Code:\s*$', ln):
            mi += 1
            in_code = True
            continue
        if not in_code:
            continue
        m = INSN_RE.match(ln)
        if m:
            out.append((mi, int(m.group(1)), m.group(2)))
        elif ln.strip() and not ln.startswith(' ' * 10):
            in_code = False
    return out


def code_methods(c):
    return [m for m in c.methods if m.code is not None]


# ------------------------------------------------------------------ recipes

def r_subst(c, path, arg):
    """subst:OLD:NEW -- the first OLD instruction becomes NEW, same length."""
    old, new = arg.split(':')
    ms = code_methods(c)
    for mi, pc, mnem in javap_insns(path):
        if mnem == old and mi < len(ms):
            c.set_u1(ms[mi].code.code_off + pc, OPC[new])
            return '%s at %s+%d -> %s' % (old, ms[mi].name, pc, new)
    return None


def r_maxstack(c, path, arg):
    """maxstack:DELTA -- add DELTA to every method's max_stack."""
    d = int(arg)
    ms = code_methods(c)
    if not ms:
        return None
    n = 0
    for m in ms:
        v = c.u2(m.code.max_stack_off)
        if v + d >= 0:
            c.set_u2(m.code.max_stack_off, v + d)
            n += 1
    return 'max_stack %+d on %d methods' % (d, n) if n else None


def r_maxlocals(c, path, arg):
    d = int(arg)
    ms = code_methods(c)
    n = 0
    for m in ms:
        v = c.u2(m.code.max_locals_off)
        if v + d >= 0:
            c.set_u2(m.code.max_locals_off, v + d)
            n += 1
    return 'max_locals %+d on %d methods' % (d, n) if n else None


def r_truncate(c, path, arg):
    """truncate:N -- drop the last N bytes of the file."""
    n = int(arg)
    if n >= len(c.data):
        return None
    del c.data[len(c.data) - n:]
    return 'dropped the last %d bytes' % n


def r_smt_delta(c, path, arg):
    """smtdelta:D -- add D to the first explicit offset_delta of each
    StackMapTable, which moves a frame off its instruction."""
    n = 0
    for m in code_methods(c):
        a = None
        for x in m.code.attrs:
            if x.name == 'StackMapTable':
                a = x
        if a is None or a.length < 4:
            continue
        ft = c.u1(a.off + 2)
        if 247 <= ft <= 255:
            v = c.u2(a.off + 3)
            c.set_u2(a.off + 3, max(0, v + int(arg)))
            n += 1
        elif ft <= 63:
            c.set_u1(a.off + 2, max(0, min(63, ft + int(arg))))
            n += 1
    return 'moved the first stack map frame of %d methods' % n if n else None


def r_drop_smt(c, path, arg):
    """dropsmt -- rename StackMapTable so the verifier cannot find it."""
    n = 0
    for i, e in enumerate(c.pool):
        if e and e[0] == 1 and c.utf8.get(i) == b'StackMapTable':
            c.data[e[2] + 2] = ord('X')
            n += 1
    return 'renamed the StackMapTable constant' if n else None


def r_cpindex(c, path, arg):
    """cpindex:DELTA -- add DELTA to the operand of the first getstatic,
    invokevirtual or invokespecial."""
    d = int(arg)
    ms = code_methods(c)
    for mi, pc, mnem in javap_insns(path):
        if mnem in ('getstatic', 'getfield', 'invokevirtual', 'invokespecial',
                    'invokestatic', 'new', 'checkcast') and mi < len(ms):
            off = ms[mi].code.code_off + pc + 1
            v = c.u2(off)
            if 1 <= v + d < c.pool_count:
                c.set_u2(off, v + d)
                return '%s operand #%d -> #%d' % (mnem, v, v + d)
    return None


def r_superfinal(c, path, arg):
    """superself -- make the class its own superclass."""
    this = c.u2(c.this_off)
    if c.u2(c.super_off) == 0:
        return None
    c.set_u2(c.super_off, this)
    return 'super_class = this_class'


def r_major(c, path, arg):
    """major:N -- set the class file major version."""
    c.set_u2(c.major_off, int(arg))
    return 'major version %s' % arg


RECIPES = {
    'subst': r_subst,
    'maxstack': r_maxstack,
    'maxlocals': r_maxlocals,
    'truncate': r_truncate,
    'smtdelta': r_smt_delta,
    'dropsmt': r_drop_smt,
    'cpindex': r_cpindex,
    'superself': r_superfinal,
    'major': r_major,
}


def apply(path, recipe, arg):
    """-> (bytes, description) or (None, None) if the recipe does not apply."""
    c = cf.load(path)
    desc = RECIPES[recipe](c, path, arg)
    if desc is None:
        return None, None
    return c.bytes(), desc


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == '--list':
        for k, f in sorted(RECIPES.items()):
            print('%-12s %s' % (k, (f.__doc__ or '').strip().split('\n')[0]))
        return
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    spec = sys.argv[1]
    recipe, _, arg = spec.partition(':')
    data, desc = apply(sys.argv[2], recipe, arg)
    if data is None:
        print('does not apply', file=sys.stderr)
        sys.exit(1)
    with open(sys.argv[3], 'wb') as f:
        f.write(data)
    print(desc)


if __name__ == '__main__':
    main()
