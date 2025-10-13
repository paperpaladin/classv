# classv

A JVM class file reader and bytecode verifier model, in plain Java 21 with no
library help: it decodes the constant pool, the members, the attributes and
the full bytecode of a `.class` file, and then runs the JVM's own type
checking verification algorithm from JVMS 4.10.1 against it.

```
$ classv --disasm Hello.class
code main ([Ljava/lang/String;)V
  max_stack 2 max_locals 1 code_length 9
  0 getstatic #7
  3 ldc #13
  5 invokevirtual #15
  8 return
```

```
usage: classv [--info] [--pool] [--disasm] [--smt] [--verify] [--all] FILE.class...
              @LISTFILE   one path per line
```

## Why this exists

`java.lang.VerifyError: Bad type on operand stack`, with nothing after it, is
one of the worst error messages in wide use. The information needed to explain
it is all in the class file and all in the spec; the JVM simply does not print
it. A tool that reads the same bytes and says which instruction, which frame
and which rule is a real thing to have.

It is also a format worth having read. Everything about the JVM that people
argue about -- erasure, `invokedynamic`, the `ACC_SUPER` flag, why interfaces
are not checked at verification time -- is visible in the bytes and is
invisible from the language.

## The oracles

Ranked by how little of them this repository controls.

**The JVM itself.** Not written yet; see PLAN.md. The verifier is the next
milestone and the agreement number is the headline.

**`javap -v -p -c`.** The JDK's own disassembler. It cannot verify anything,
but it pins the parse, and everything later reads the parse. The comparison is
on extracted facts, never on text: the two output formats share nothing and
diffing them would measure formatting.

| fact | from javap | from classv |
|---|---|---|
| class file major and minor version | `minor/major version:` | `--info` |
| every constant pool entry as (index, tag, raw operand) | `Constant pool:` | `--pool` |
| every field and method descriptor, in order | `descriptor:` | `--info` |
| `max_stack`, `max_locals` per method | `stack=, locals=` | `--disasm` |
| every instruction as (pc, mnemonic, canonical operands) | `-c` | `--disasm` |
| every exception table row | `Exception table:` | `--disasm` |
| every stack map frame: frame type, offset delta, locals, stack | `StackMapTable:` | `--smt` |

Measured over `java.base` from this machine's JDK 21 module image:

```
--- javap comparison: 7574 classes, 453680 pool operands, 604799 Utf8,
    94072 members, 62791 code bodies, 1874309 instructions, 10975 handlers,
    104155 stack map frames, 125930 verification types, 104155 frame offsets
30296 passed, 0 failed
```

`CLASSV_FULL=1 make test` runs the same comparison over all 27846 classes in
the image rather than the 7574 of `java.base`.

### Where that comparison cannot see

javap's constant pool dump is lossy in three ways, and the comparison folds
classv's output the same way rather than pretending otherwise. All three are
in `t/javap_cmp.py` with the reason beside them.

- **Trailing whitespace is stripped.** The Utf8 constant `"\t- "` in
  `java/io/ObjectOutputStream$DebugTraceInfoStack` prints as `\t-`. A reader
  that dropped trailing spaces would agree with javap about every string in
  the JDK.
- **Unpaired surrogates become `?`.** javap writes UTF-8, and Java's encoder
  cannot represent a lone surrogate. The `sun/nio/cs/*Mapping` tables store
  thousands of them inside ordinary string constants, so the oracle folds
  every unpaired surrogate to `?` and so do we. The code units themselves are
  therefore unchecked.
- **A stack map frame's offset delta is not always printed.** For frame
  types 0..63 and 64..127 the delta is the frame type byte itself and javap
  prints only the byte, so a reader that forgot to subtract 64 from a
  `same_locals_1_stack_item` frame agrees with javap about every frame in the
  JDK. That mutation survived until the suite also asserted that every
  expanded absolute offset lands on an instruction boundary and strictly
  increases; the boundaries come from the instruction list javap has just
  agreed with, so it is a property check standing on an oracle rather than a
  second oracle. With it, the mutant fails 82 of 300 classes.
- **Escapes are ambiguous in one direction.** javap escapes `\t \n \r \b \f '
  " \` and anything `Character.isISOControl` accepts; a string that literally
  contained the two characters `\` and `t` renders identically.

classv's own `--pool` output does not have these holes: every character
outside printable ASCII is written as `\uXXXX`. The folding happens only in
the comparison script.

## What does not work

Everything after the parse. The type checker is the next milestone and
`--verify` currently prints `REFUSE` and exits 3, which is a deliberate
distinct status: "classv has not looked at this" and "the JVM would reject
this" must never be counted as the same answer.

Also not done, and listed in PLAN.md: the `Exceptions`, `BootstrapMethods`,
`Record` and annotation attributes are not decoded at all, and there is no truncation or corruption
sweep yet, so "the parser never throws anything but ClassFormatException" is
currently an intention rather than a measurement.

## License

GPL-3.0. See `LICENSE`.
