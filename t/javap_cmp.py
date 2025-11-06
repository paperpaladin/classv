#!/usr/bin/env python3
"""Compare classv's parse against javap, fact by fact.

javap is the reference disassembler shipped with the JDK and written by other
people.  The comparison is on extracted facts, never on raw text: the two
output formats share nothing and diffing them would measure formatting.

Four fact families are compared per class:

  version    (major, minor)
  pool       every constant pool entry as (index, tag, raw operand text)
  members    every field and method as (descriptor, flags-independent order)
  code       every instruction as (pc, mnemonic, canonical operands), plus
             max_stack / max_locals, plus the exception table

usage: javap_cmp.py CLASSV_CMD LIST_FILE [BATCH]
       LIST_FILE holds one class file path per line.
"""
import os
import re
import subprocess
import sys

PASS = 0
FAIL = 0
FAILURES = []


def fail(what):
    global FAIL
    FAIL += 1
    FAILURES.append(what)


def ok():
    global PASS
    PASS += 1


# --------------------------------------------------------------- javap side

INSN_RE = re.compile(r'^\s+(\d+): (\S+)\s*(.*)$')
POOL_RE = re.compile(r'^\s*#(\d+) = (.*)$')


def unescape(s):
    """Undo classv's \\uXXXX escaping, then join surrogate pairs the way a
    UTF-8 reader would, so the result can be compared with javap's bytes."""
    out = []
    i = 0
    while i < len(s):
        if s[i] == '\\' and i + 1 < len(s):
            if s[i + 1] == '\\':
                out.append('\\')
                i += 2
                continue
            if s[i + 1] == 'u':
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
        out.append(s[i])
        i += 1
    # javap writes its output as UTF-8, and Java's UTF-8 encoder turns an
    # unpaired surrogate into '?'.  The JDK's charset mapping tables
    # (sun/nio/cs/*Mapping) are full of them: a lone U+D8F0 inside a string
    # constant is ordinary data there.  So the oracle cannot see those code
    # units at all, and the comparison folds them the same way rather than
    # pretending otherwise.
    joined = []
    i = 0
    cs = ''.join(out)
    while i < len(cs):
        c = cs[i]
        if 0xd800 <= ord(c) <= 0xdbff and i + 1 < len(cs) and 0xdc00 <= ord(cs[i + 1]) <= 0xdfff:
            joined.append(chr(0x10000 + ((ord(c) - 0xd800) << 10) + (ord(cs[i + 1]) - 0xdc00)))
            i += 2
            continue
        joined.append('?' if 0xd800 <= ord(c) <= 0xdfff else c)
        i += 1
    return ''.join(joined)


JAVAP_ESC = {'\\': '\\\\', '\t': '\\t', '\n': '\\n', '\r': '\\r',
             '\b': '\\b', '\f': '\\f', "'": "\\'", '"': '\\"'}


def javap_form(s):
    """javap's rendering of a Utf8 constant: seven escapes, and the line is
    right trimmed, so a trailing space in the constant is simply not visible
    through this oracle.  Said in the README rather than papered over."""
    out = []
    for c in s:
        if c in JAVAP_ESC:
            out.append(JAVAP_ESC[c])
        elif ord(c) <= 0x1f or 0x7f <= ord(c) <= 0x9f:
            # javap escapes anything Character.isISOControl says yes to, which
            # is why U+0080 comes out as \\u0080 and U+00A0 comes out raw.
            out.append('\\u%04x' % ord(c))
        else:
            out.append(c)
    # Trailing whitespace goes, but only the characters String.trim() calls
    # whitespace (everything <= U+0020).  Python's rstrip() also strips
    # U+2028 LINE SEPARATOR, which javap keeps, and that was the single
    # disagreement left over java.base: one string in GB18030.
    return ''.join(out).rstrip(''.join(chr(i) for i in range(0x21)))


def canon_operand(mnem, rest):
    """javap's operand text -> the canonical form classv prints."""
    rest = rest.split('//')[0].strip()
    if not rest:
        return mnem
    if mnem in ('invokeinterface', 'multianewarray'):
        a, b = [x.strip() for x in rest.split(',')]
        return '%s %s %s' % (mnem, a, b)
    if mnem == 'invokedynamic':
        a = rest.split(',')[0].strip()
        return '%s %s' % (mnem, a)
    if mnem == 'iinc' or mnem == 'iinc_w':
        a, b = [x.strip() for x in rest.split(',')]
        m = 'wide iinc' if mnem.endswith('_w') else 'iinc'
        return '%s %s %s' % (m, a, b)
    if mnem.endswith('_w') and mnem not in ('ldc_w', 'ldc2_w', 'goto_w', 'jsr_w'):
        return 'wide %s %s' % (mnem[:-2], rest)
    return '%s %s' % (mnem, rest)


def smt_types(body):
    """javap's "[ class \"[B\", int ]" -> ['class [B', 'int'].  The quotes are
    javap's own: it quotes a class name that is not a plain slash separated
    identifier path, which in practice means every array type."""
    out = []
    for t in body.split(','):
        t = t.strip()
        if not t:
            continue
        if t.startswith('class "') and t.endswith('"'):
            t = 'class ' + t[7:-1]
        out.append(t)
    return out


def parse_javap(text):
    """-> list of dicts, one per class file in the output."""
    classes = []
    cur = None
    lines = text.split('\n')
    i = 0
    n = len(lines)
    state = 'head'
    member = None
    while i < n:
        ln = lines[i]
        if ln.startswith('Classfile '):
            cur = {'path': ln[len('Classfile '):].strip(), 'pool': [], 'members': [],
                   'codes': [], 'major': None, 'minor': None}
            classes.append(cur)
            state = 'head'
            member = None
            i += 1
            continue
        if cur is None:
            i += 1
            continue
        if state == 'tail':
            i += 1
            continue
        if state == 'head':
            m = re.match(r'^\s+minor version: (\d+)', ln)
            if m:
                cur['minor'] = int(m.group(1))
            m = re.match(r'^\s+major version: (\d+)', ln)
            if m:
                cur['major'] = int(m.group(1))
            if ln.startswith('Constant pool:'):
                state = 'pool'
            i += 1
            continue
        if state == 'pool':
            if ln.rstrip() == '{':
                state = 'body'
                i += 1
                continue
            m = POOL_RE.match(ln)
            if m:
                idx, field = int(m.group(1)), m.group(2)
                # javap lays the tag out in a fixed 18 column field, so the
                # operand starts at a known offset.  Splitting on whitespace
                # would eat a Utf8 constant's leading spaces.
                tag = field[:18].strip()
                operand = field[19:] if len(field) > 19 else ''
                if tag != 'Utf8':
                    operand = operand.split('//')[0].strip()
                cur['pool'].append((idx, tag, operand))
            i += 1
            continue
        # state == 'body'
        if ln == '}':
            # The class body ends here.  Everything after it (Record:,
            # BootstrapMethods:, InnerClasses:) also prints "descriptor:"
            # lines, and reading them as members made a 9 member class look
            # like an 11 member one.
            state = 'tail'
            i += 1
            continue
        m = re.match(r'^\s+StackMapTable: number_of_entries = (\d+)\s*$', ln)
        if m and member is not None and member['code'] is not None:
            smt = {'n': int(m.group(1)), 'frames': []}
            member['code']['smt'] = smt
            cur_frame = None
            i += 1
            while i < n:
                l2 = lines[i]
                mm = re.match(r'^\s+frame_type = (\d+) /\* \S+ \*/\s*$', l2)
                if mm:
                    cur_frame = {'ft': int(mm.group(1)), 'delta': None,
                                 'locals': None, 'stack': None}
                    smt['frames'].append(cur_frame)
                    i += 1
                    continue
                if cur_frame is None:
                    break
                mm = re.match(r'^\s+offset_delta = (\d+)\s*$', l2)
                if mm:
                    cur_frame['delta'] = int(mm.group(1))
                    i += 1
                    continue
                mm = re.match(r'^\s+(locals|stack) = \[(.*)\]\s*$', l2)
                if mm:
                    cur_frame[mm.group(1)] = smt_types(mm.group(2))
                    i += 1
                    continue
                break
            continue
        m = re.match(r'^\s+descriptor: (.*)$', ln)
        if m:
            member = {'desc': m.group(1).strip(), 'code': None}
            cur['members'].append(member)
            if member['desc'].startswith('('):
                cur['codes'].append(member)
            i += 1
            continue
        if re.match(r'^\s+Code:\s*$', ln) and member is not None:
            code = {'insns': [], 'handlers': [], 'stack': None, 'locals': None,
                    'smt': None}
            member['code'] = code
            i += 1
            m = re.match(r'^\s+stack=(\d+), locals=(\d+)', lines[i]) if i < n else None
            if m:
                code['stack'] = int(m.group(1))
                code['locals'] = int(m.group(2))
                i += 1
            # instructions
            while i < n:
                ln2 = lines[i]
                mm = INSN_RE.match(ln2)
                if not mm:
                    break
                pc, mnem, rest = int(mm.group(1)), mm.group(2), mm.group(3)
                if mnem in ('tableswitch', 'lookupswitch'):
                    entries = []
                    default = None
                    i += 1
                    while i < n and lines[i].strip() != '}':
                        s = lines[i].strip()
                        if s.startswith('default:'):
                            default = s.split(':')[1].strip()
                        else:
                            k, v = s.split(':')
                            entries.append('%s:%s' % (k.strip(), v.strip()))
                        i += 1
                    i += 1  # the '}'
                    code['insns'].append('%d %s default=%s%s' % (
                        pc, mnem, default, ''.join(' ' + e for e in entries)))
                    continue
                code['insns'].append('%d %s' % (pc, canon_operand(mnem, rest)))
                i += 1
            # exception table
            if i < n and lines[i].strip() == 'Exception table:':
                i += 2  # header row
                while i < n:
                    s = lines[i].strip()
                    mm = re.match(r'^(\d+)\s+(\d+)\s+(\d+)\s+(.*)$', s)
                    if not mm:
                        break
                    typ = mm.group(4).strip()
                    if typ == 'any':
                        t = 'any'
                    else:
                        t = typ.split(None, 1)[1].strip().replace('.', '/')
                    code['handlers'].append('%s %s %s %s' % (
                        mm.group(1), mm.group(2), mm.group(3), t))
                    i += 1
            continue
        i += 1
    return classes


# -------------------------------------------------------------- classv side

def parse_classv(text):
    classes = []
    cur = None
    member = None
    for ln in text.split('\n'):
        if ln.startswith('== '):
            cur = {'path': ln[3:].strip(), 'pool': [], 'members': [], 'codes': [],
                   'major': None, 'minor': None}
            classes.append(cur)
            member = None
            continue
        if cur is None:
            continue
        if ln.startswith('major '):
            cur['major'] = int(ln.split()[1])
        elif ln.startswith('minor '):
            cur['minor'] = int(ln.split()[1])
        elif ln.startswith('field '):
            cur['members'].append({'desc': ln.split()[2], 'code': None})
        elif ln.startswith('method '):
            cur['members'].append({'desc': ln.split()[2], 'code': None})
        elif ln.startswith('code '):
            member = {'desc': ln.split()[2], 'code': None}
            cur['codes'].append(member)
        elif ln.startswith('  nocode'):
            pass
        elif ln.startswith('  max_stack '):
            p = ln.split()
            member['code'] = {'insns': [], 'handlers': [], 'smt': None,
                              'stack': int(p[1]), 'locals': int(p[3])}
        elif ln.startswith('  handler '):
            member['code']['handlers'].append(ln[len('  handler '):])
        elif ln.startswith('  smt '):
            member['code']['smt'] = {'n': int(ln.split()[1]), 'frames': [], 'offsets': []}
        elif ln.startswith('  frame '):
            member['code']['smt']['frames'].append(
                {'ft': int(ln.split()[1]), 'delta': None, 'locals': None, 'stack': None})
            member['code']['smt']['offsets'].append(int(ln.split()[4]))
        elif ln.startswith('    delta '):
            member['code']['smt']['frames'][-1]['delta'] = int(ln.split()[1])
        elif ln.startswith('    locals ') or ln.startswith('    stack '):
            key, rest = ln.strip().split(' ', 1)
            cnt, _, payload = rest.partition(' ')
            member['code']['smt']['frames'][-1][key] = (
                payload.split('|') if int(cnt) else [])
        elif ln.startswith('  ') and member is not None and member['code'] is not None:
            member['code']['insns'].append(ln[2:])
        elif ln.startswith('#'):
            # "#1 = Methodref #2.#3"
            head, rest = ln.split(' = ', 1)
            idx = int(head[1:])
            tag, _, operand = rest.partition(' ')
            cur['pool'].append((idx, tag, operand))
    return classes


# ------------------------------------------------------------------ compare

def cmp_class(jc, cc, counters):
    path = jc['path']
    if (jc['major'], jc['minor']) == (cc['major'], cc['minor']):
        ok()
        counters['version'] += 1
    else:
        fail('%s: version %s.%s vs javap %s.%s' % (path, cc['major'], cc['minor'],
                                                   jc['major'], jc['minor']))

    # javap drops a Utf8's leading/trailing space and renders some strings with
    # escapes; compare only entries where that cannot bite, and count the rest.
    jp = jc['pool']
    cp = cc['pool']
    if len(jp) != len(cp):
        fail('%s: pool has %d entries, javap %d' % (path, len(cp), len(jp)))
    else:
        bad = 0
        for a, b in zip(jp, cp):
            if a[0] != b[0] or a[1] != b[1]:
                bad += 1
                if bad == 1:
                    fail('%s: pool #%s is %s/%s, javap #%s is %s' %
                         (path, b[0], b[1], b[2], a[0], a[1]))
                continue
            if a[1] == 'Utf8':
                counters['utf8'] += 1
                if a[2] != javap_form(unescape(b[2])):
                    bad += 1
                    if bad == 1:
                        fail('%s: pool #%s Utf8 %r vs javap %r' % (path, b[0], b[2], a[2]))
                continue
            counters['pool'] += 1
            if a[2] != b[2]:
                bad += 1
                if bad == 1:
                    fail('%s: pool #%s %s operand %r vs javap %r' %
                         (path, b[0], a[1], b[2], a[2]))
        if bad == 0:
            ok()

    jm = [m['desc'] for m in jc['members']]
    cm = [m['desc'] for m in cc['members']]
    counters['member'] += len(cm)
    if jm != cm:
        k = next((i for i in range(min(len(jm), len(cm))) if jm[i] != cm[i]), min(len(jm), len(cm)))
        fail('%s: member %d of %d is %r, javap has %d and says %r'
             % (path, k, len(cm), cm[k:k + 1], len(jm), jm[k:k + 1]))
        return
    ok()

    jk, ck = jc['codes'], cc['codes']
    if len(jk) != len(ck):
        fail('%s: %d methods, javap %d' % (path, len(ck), len(jk)))
        return
    bad = 0
    for a, b in zip(jk, ck):
        if a['desc'] != b['desc']:
            bad += 1
            if bad == 1:
                fail('%s: method descriptor %r vs javap %r' % (path, b['desc'], a['desc']))
            continue
        if (a['code'] is None) != (b['code'] is None):
            bad += 1
            if bad == 1:
                fail('%s: %s code presence %s vs javap %s'
                     % (path, a['desc'], b['code'] is not None, a['code'] is not None))
            continue
        if a['code'] is None:
            continue
        counters['method_code'] += 1
        js, cs = a['code']['smt'], b['code']['smt']
        if (js is None) != (cs is None):
            bad += 1
            if bad == 1:
                fail('%s %s: StackMapTable presence %s vs javap %s'
                     % (path, a['desc'], cs is not None, js is not None))
        elif js is not None:
            counters['frame'] += len(cs['frames'])
            if js['n'] != cs['n'] or len(js['frames']) != len(cs['frames']):
                bad += 1
                if bad == 1:
                    fail('%s %s: %d frames (header %d) vs javap %d (header %d)'
                         % (path, a['desc'], len(cs['frames']), cs['n'],
                            len(js['frames']), js['n']))
            else:
                for jf, cfr in zip(js['frames'], cs['frames']):
                    counters['frame_type'] += 1
                    for t in jf['locals'] or []:
                        counters['vtype'] += 1
                    for t in jf['stack'] or []:
                        counters['vtype'] += 1
                    if jf != cfr:
                        bad += 1
                        if bad == 1:
                            fail('%s %s: frame %r vs javap %r' % (path, a['desc'], cfr, jf))
                        break
            # javap prints the deltas and not the absolute offsets, so nothing
            # above can see an error in the expansion.  The absolute offsets
            # must land on an instruction boundary and strictly increase; the
            # boundaries come from the instruction list javap has just agreed
            # with, so this is a property check standing on an oracle rather
            # than a second oracle.
            pcs = set(int(x.split(' ', 1)[0]) for x in b['code']['insns'])
            last = -1
            for off in cs['offsets']:
                counters['frame_offset'] += 1
                if off not in pcs or off <= last:
                    bad += 1
                    if bad == 1:
                        fail('%s %s: stack map frame offset %d is not an increasing '
                             'instruction boundary' % (path, a['desc'], off))
                    break
                last = off
        if (a['code']['stack'], a['code']['locals']) != (b['code']['stack'], b['code']['locals']):
            bad += 1
            if bad == 1:
                fail('%s %s: stack/locals %s/%s vs javap %s/%s' % (
                    path, a['desc'], b['code']['stack'], b['code']['locals'],
                    a['code']['stack'], a['code']['locals']))
        ja, ca = a['code']['insns'], b['code']['insns']
        counters['insn'] += len(ca)
        if ja != ca:
            bad += 1
            if bad == 1:
                for k in range(max(len(ja), len(ca))):
                    x = ja[k] if k < len(ja) else '<missing>'
                    y = ca[k] if k < len(ca) else '<missing>'
                    if x != y:
                        fail('%s %s: insn %r vs javap %r' % (path, a['desc'], y, x))
                        break
        jh, ch = a['code']['handlers'], b['code']['handlers']
        counters['handler'] += len(ch)
        if jh != ch:
            bad += 1
            if bad == 1:
                fail('%s %s: handlers %r vs javap %r' % (path, a['desc'], ch, jh))
    if bad == 0:
        ok()


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    classv_cmd = sys.argv[1].split()
    listfile = sys.argv[2]
    batch = int(sys.argv[3]) if len(sys.argv) > 3 else 200
    paths = [p for p in open(listfile).read().split('\n') if p]
    counters = {'version': 0, 'pool': 0, 'utf8': 0, 'member': 0,
                'method_code': 0, 'insn': 0, 'handler': 0, 'classes': 0,
                'javap_failed': 0, 'frame': 0, 'frame_type': 0, 'vtype': 0,
                'frame_offset': 0}
    tmp = listfile + '.batch'
    for start in range(0, len(paths), batch):
        chunk = paths[start:start + batch]
        with open(tmp, 'w') as f:
            f.write('\n'.join(chunk) + '\n')
        jr = subprocess.run(['javap', '-v', '-p', '-c'] + chunk,
                            capture_output=True, text=True, errors='replace')
        cr = subprocess.run(classv_cmd + ['--info', '--pool', '--disasm', '--smt', '@' + tmp],
                            capture_output=True, text=True, errors='replace')
        if cr.returncode != 0:
            fail('classv exited %d on batch starting %s: %s' %
                 (cr.returncode, chunk[0], cr.stderr.strip()[:200]))
            continue
        jcs = parse_javap(jr.stdout)
        ccs = parse_classv(cr.stdout)
        jby = {os.path.realpath(c['path']): c for c in jcs}
        for cc in ccs:
            jc = jby.get(os.path.realpath(cc['path']))
            if jc is None:
                counters['javap_failed'] += 1
                continue
            counters['classes'] += 1
            cmp_class(jc, cc, counters)
    os.path.exists(tmp) and os.remove(tmp)

    for f in FAILURES[:20]:
        print('FAIL %s' % f)
    print('--- javap comparison: %d classes, %d pool operands, %d Utf8, %d members, '
          '%d code bodies, %d instructions, %d handlers, %d stack map frames, '
          '%d verification types, %d frame offsets'
          % (counters['classes'], counters['pool'], counters['utf8'], counters['member'],
             counters['method_code'], counters['insn'], counters['handler'],
             counters['frame'], counters['vtype'], counters['frame_offset']))
    if counters['javap_failed']:
        print('--- javap produced no output for %d files' % counters['javap_failed'])
    print('JAVAP_PASS %d' % PASS)
    print('JAVAP_FAIL %d' % FAIL)
    # A comparison over zero classes agrees with everything.
    if counters['classes'] == 0 or counters['insn'] == 0 or counters['vtype'] == 0:
        print('FAIL javap comparison was vacuous: %d classes, %d instructions, '
              '%d verification types'
              % (counters['classes'], counters['insn'], counters['vtype']))
        sys.exit(1)
    sys.exit(1 if FAIL else 0)


if __name__ == '__main__':
    main()
