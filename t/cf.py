"""A minimal class file reader, used only to BUILD corrupted class files.

This is not an oracle and must never be used as one: it is a second
implementation by the same hand, which proves nothing about the first.  Its
only job is to find the byte offsets a mutation needs to reach -- the bytes of
a method's code, the max_stack field, a constant pool entry's operand, the
StackMapTable -- so that a mutation can be described as "give this method's
first aload_0 an operand type the next instruction cannot take" instead of as
"patch byte 3177".
"""
import struct


class Attr:
    def __init__(self, name, off, length, data):
        self.name = name
        self.off = off          # offset of the first info byte in the file
        self.length = length
        self.data = data


class Method:
    def __init__(self):
        self.flags = 0
        self.name = None
        self.desc = None
        self.attrs = []
        self.code = None        # Code sub-structure or None

    def attr(self, n):
        for a in self.attrs:
            if a.name == n:
                return a
        return None


class Code:
    pass


class ClassFile:
    def __init__(self, data):
        self.data = bytearray(data)
        self._parse()

    # ------------------------------------------------------------ primitives
    def u1(self, o): return self.data[o]
    def u2(self, o): return struct.unpack_from('>H', self.data, o)[0]
    def u4(self, o): return struct.unpack_from('>I', self.data, o)[0]

    def set_u1(self, o, v): self.data[o] = v & 0xff
    def set_u2(self, o, v): struct.pack_into('>H', self.data, o, v & 0xffff)
    def set_u4(self, o, v): struct.pack_into('>I', self.data, o, v & 0xffffffff)

    # ----------------------------------------------------------------- parse
    def _parse(self):
        d = self.data
        assert d[:4] == b'\xca\xfe\xba\xbe', 'not a class file'
        self.minor_off = 4
        self.major_off = 6
        p = 8
        count = self.u2(p)
        p += 2
        self.pool_count = count
        self.pool = [None] * count      # (tag, offset_of_tag, offset_of_info)
        self.utf8 = {}
        i = 1
        while i < count:
            tag = d[p]
            info = p + 1
            if tag == 1:
                n = self.u2(info)
                self.utf8[i] = bytes(d[info + 2:info + 2 + n])
                size = 3 + n
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                size = 5
            elif tag in (5, 6):
                size = 9
            elif tag == 15:
                size = 4
            elif tag in (7, 8, 16, 19, 20):
                size = 3
            else:
                raise ValueError('constant pool tag %d at index %d' % (tag, i))
            self.pool[i] = (tag, p, info)
            p += size
            if tag in (5, 6):
                i += 1
            i += 1
        self.pool_end = p
        self.access_off = p
        self.this_off = p + 2
        self.super_off = p + 4
        p += 6
        nif = self.u2(p)
        p += 2 + 2 * nif
        self.fields, p = self._members(p)
        self.methods, p = self._members(p)
        self.attrs, p = self._attrs(p)
        self.end = p

    def name_of(self, idx):
        return self.utf8[idx].decode('utf-8', 'replace')

    def class_name(self, idx):
        return self.name_of(self.u2(self.pool[idx][2]))

    def _attrs(self, p):
        n = self.u2(p)
        p += 2
        out = []
        for _ in range(n):
            ni = self.u2(p)
            ln = self.u4(p + 2)
            out.append(Attr(self.name_of(ni), p + 6, ln, bytes(self.data[p + 6:p + 6 + ln])))
            p += 6 + ln
        return out, p

    def _members(self, p):
        n = self.u2(p)
        p += 2
        out = []
        for _ in range(n):
            m = Method()
            m.flags = self.u2(p)
            m.name = self.name_of(self.u2(p + 2))
            m.desc = self.name_of(self.u2(p + 4))
            m.attrs, p = self._attrs(p + 6)
            a = m.attr('Code')
            if a is not None:
                m.code = self._code(a)
            out.append(m)
        return out, p

    def _code(self, a):
        c = Code()
        o = a.off
        c.max_stack_off = o
        c.max_locals_off = o + 2
        c.code_len = self.u4(o + 4)
        c.code_off = o + 8
        p = c.code_off + c.code_len
        nh = self.u2(p)
        c.handlers_off = p + 2
        c.handlers = nh
        p += 2 + 8 * nh
        c.attrs, p = self._attrs(p)
        c.end = p
        return c

    def bytes(self):
        return bytes(self.data)


def load(path):
    with open(path, 'rb') as f:
        return ClassFile(f.read())
