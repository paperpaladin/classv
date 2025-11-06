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
import cf          # noqa: E402
import javap_cmp   # noqa: E402

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


# javap is run once for a whole batch and the result cached, because a sweep
# of twenty recipes over four hundred classes would otherwise start eight
# thousand JVMs.  The parse is javap_cmp.py's, which the suite already
# mutation tests, rather than a second one written here.
INSN_CACHE = {}


def javap_batch(paths):
    """Fill INSN_CACHE for every path, in as few javap invocations as possible."""
    todo = [p for p in paths if os.path.realpath(p) not in INSN_CACHE]
    for i in range(0, len(todo), 300):
        chunk = todo[i:i + 300]
        r = subprocess.run(['javap', '-v', '-c', '-p'] + chunk, capture_output=True,
                           text=True, errors='replace')
        for c in javap_cmp.parse_javap(r.stdout):
            out = []
            mi = -1
            for m in c['codes']:
                if m['code'] is None:
                    # An abstract or native method is in javap's member list
                    # and not in the Code list a mutation indexes into.
                    # Counting it put every mutation of a class with an
                    # abstract method at the wrong byte, which still produced
                    # a corrupt file but not the corruption the recipe names.
                    continue
                mi += 1
                for text in m['code']['insns']:
                    pc, _, rest = text.partition(' ')
                    out.append((mi, int(pc), rest.split(' ')[0]))
            INSN_CACHE[os.path.realpath(c['path'])] = out
        for p in chunk:
            INSN_CACHE.setdefault(os.path.realpath(p), [])


def javap_insns(path):
    """-> list of (method_index, pc, mnemonic), method_index counting methods
    with a Code attribute in file order."""
    rp = os.path.realpath(path)
    if rp not in INSN_CACHE:
        javap_batch([path])
    return INSN_CACHE.get(rp, [])


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


BRANCHES = set(('goto', 'goto_w', 'jsr', 'ifeq', 'ifne', 'iflt', 'ifge', 'ifgt', 'ifle',
                'if_icmpeq', 'if_icmpne', 'if_icmplt', 'if_icmpge', 'if_icmpgt',
                'if_icmple', 'if_acmpeq', 'if_acmpne', 'ifnull', 'ifnonnull'))


def r_branch(c, path, arg):
    """branch:DELTA -- add DELTA to the first two byte branch offset."""
    d = int(arg)
    ms = code_methods(c)
    for mi, pc, mnem in javap_insns(path):
        if mnem in BRANCHES and mnem != 'goto_w' and mi < len(ms):
            off = ms[mi].code.code_off + pc + 1
            v = c.u2(off)
            if v >= 32768:
                v -= 65536
            nv = v + d
            if not (0 <= pc + nv < ms[mi].code.code_len):
                continue
            c.set_u2(off, nv & 0xffff)
            return '%s at %s+%d target %+d -> %+d' % (mnem, ms[mi].name, pc, v, nv)
    return None


def r_iicount(c, path, arg):
    """iicount:DELTA -- add DELTA to the first invokeinterface count byte."""
    d = int(arg)
    ms = code_methods(c)
    for mi, pc, mnem in javap_insns(path):
        if mnem == 'invokeinterface' and mi < len(ms):
            off = ms[mi].code.code_off + pc + 3
            v = c.u1(off)
            if 0 <= v + d <= 255:
                c.set_u1(off, v + d)
                return 'invokeinterface count %d -> %d' % (v, v + d)
    return None


def smt_first_type_offset(c, a):
    """Offset of the first verification_type_info byte in a StackMapTable."""
    if a.length < 3:
        return None
    ft = c.u1(a.off + 2)
    if 64 <= ft <= 127:
        return a.off + 3
    if ft == 247:
        return a.off + 5
    if 252 <= ft <= 254:
        return a.off + 5
    if ft == 255:
        n = c.u2(a.off + 5)
        return a.off + 7 if n else None
    return None


def r_smtitem(c, path, arg):
    """smtitem:TAG -- set the first verification type in a StackMapTable to TAG."""
    tag = int(arg)
    for m in code_methods(c):
        for x in m.code.attrs:
            if x.name != 'StackMapTable':
                continue
            o = smt_first_type_offset(c, x)
            if o is None or c.u1(o) == tag:
                continue
            was = c.u1(o)
            c.set_u1(o, tag)
            return 'stack map item tag %d -> %d in %s' % (was, tag, m.name)
    return None


def r_handlertype(c, path, arg):
    """handlertype -- point the first exception handler at this_class."""
    this = c.u2(c.this_off)
    for m in code_methods(c):
        if m.code.handlers == 0:
            continue
        off = m.code.handlers_off + 6
        if c.u2(off) == this:
            continue
        was = c.u2(off)
        c.set_u2(off, this)
        return 'handler catch_type #%d -> #%d in %s' % (was, this, m.name)
    return None


def r_swapcode(c, path, arg):
    """swapcode -- exchange the first two adjacent one byte instructions."""
    ms = code_methods(c)
    ins = javap_insns(path)
    for k in range(len(ins) - 2):
        mi, pc, mn = ins[k]
        mi2, pc2, mn2 = ins[k + 1]
        mi3, pc3, _ = ins[k + 2]
        if mi != mi2 or mi != mi3 or mi >= len(ms):
            continue
        if pc2 - pc != 1 or pc3 - pc2 != 1 or mn == mn2:
            continue
        base = ms[mi].code.code_off
        a0 = c.u1(base + pc)
        b0 = c.u1(base + pc2)
        c.set_u1(base + pc, b0)
        c.set_u1(base + pc2, a0)
        return 'swapped %s and %s at %s+%d' % (mn, mn2, ms[mi].name, pc)
    return None


RECIPES = {
    'branch': r_branch,
    'iicount': r_iicount,
    'smtitem': r_smtitem,
    'handlertype': r_handlertype,
    'swapcode': r_swapcode,
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
