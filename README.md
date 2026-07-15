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
usage: classv [--info] [--pool] [--disasm] [--smt] [--frames] [--verify]
              [--cp DIR]... [--all] FILE.class... | @LISTFILE
```

`--cp DIR` adds a directory of class files that the hierarchy resolver may
read to answer subtype questions. It matters: a class whose superclass
cannot be found, on a `--cp` root or by reflection in this JVM, is refused
rather than analysed, because two of the rules are about the superclass
itself and not its name. It never loads them; it parses them with
classv's own parser, so nothing is initialised and no code runs. `@LISTFILE`
takes one path per line, because the corpus is 27846 files and that does not
fit in argv.

Exit status: 0 accepted, 1 rejected by the type checker, 2 usage, 3 classv
refused to analyse the file, 4 the file is not a well formed class file, 5 a
defect in classv itself. Three and one are deliberately different numbers,
and so are five and one: a crash that exits 1 is a crash a harness counts as
a correct rejection.

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

**The JVM itself**, through `t/Probe.java`. This is the strongest oracle
available for this program: it is the artefact classv is a model of. The
probe prints one word per class file -- `ACCEPT`, `VERIFY`, `FORMAT`,
`OTHER` or `ORACLE_NA` -- and the last of those is never counted as
agreement with anything.

Three things about HotSpot were measured rather than assumed, and all three
changed the design of the harness.

- **`ClassLoader.defineClass` does not verify.** It returns a perfectly good
  `Class` object for a class whose bytecode is nonsense. Verification happens
  at link time, and `getDeclaredMethods()` is the cheapest way to force
  linking without also running the static initialiser.
- **A boot loaded class is not verified at all without `-Xverify:all`.**
  `BytecodeVerificationLocal` defaults to false. The suite proves this rather
  than citing it: it compiles a `java.lang.Zz`, breaks its bytecode, patches
  it into `java.base` with `--patch-module`, and requires the class to link
  cleanly without the flag and to throw `VerifyError` with it. Most of the
  module image is boot loaded, so without that flag the whole corpus would
  come back `ACCEPT` and the agreement number would be worthless.
- **One class loader per class does not work.** A runtime package is
  identified by (loader, package name), so a corpus class whose superclass is
  package private in the same package fails with `IllegalAccessError` when
  the superclass comes from the boot loader and the subclass does not. That
  was 153 of the first 400 `java.base` classes, and no `--add-exports` fixes
  it, because it is not a module access check. Defining the whole corpus in
  one child-first loader puts both halves in the same runtime package.

Even then the split loader has a residue: a corpus class whose supertype
chain crosses into a `java.*` package picks up the boot loader's copy of a
type halfway along, and the two copies are not assignable to each other. 324
of `java.desktop`'s classes failed that way, every one of them naming
`javax/swing/JPanel.getAccessibleContext` or a sibling rather than itself. A
`VerifyError` whose `Location:` line names a class other than the one being
asked about is therefore reported as `ORACLE_NA`, not as a verdict.

So the module image corpus is asked about through `Class.forName(name, false,
systemLoader)` with `-Xverify:all --add-modules ALL-SYSTEM`, which uses the
real loaders and has no split at all. Over all 27846 classes in the image
that is **27777 ACCEPT, 0 reject, 69 no opinion**; the 69 are the
`module-info.class` files, one per module, which are not classes you can ask
`Class.forName` about. The child-first loader is for files the running JDK
does not already have: the mutation corpus and the `javac` corpora.

The oracle is also shown saying no before any agreement number is believed.
Nineteen planted defects in one fixture, each with the answer measured by
hand once and written into the suite:

| planted defect | HotSpot's answer |
|---|---|
| `ireturn` -> `areturn` | VerifyError: Bad type on operand stack |
| `iload_0` -> `aload_0` | VerifyError: Bad local variable type |
| `aload_0` -> `iload_0` | VerifyError: Bad local variable type |
| `dup` -> `dup2`, `pop` -> `pop2`, `return` -> `athrow` | VerifyError: Operand stack underflow |
| `iadd` -> `ladd` | VerifyError: Bad type on operand stack |
| `max_stack` minus one | VerifyError: Operand stack overflow |
| a stack map frame moved one byte | VerifyError: StackMapTable error: bad offset |
| the StackMapTable constant renamed away | VerifyError: Expecting a stackmap frame at branch target 14 |
| a field or method operand pointing one entry away | VerifyError: Illegal type at constant pool entry N |
| `max_locals` minus one | ClassFormatError: Arguments can't fit into locals |
| the last 1 or 4 bytes dropped | ClassFormatError: Truncated class file |
| major version 99 | UnsupportedClassVersionError |
| major version 45 | ClassFormatError: Class file version does not support constant tag 18 |
| `super_class` = `this_class` | ClassCircularityError |

**`Class.isAssignableFrom`.** The JVM's own reflective subtype relation,
for the part of JVMS 4.10.1.2 that is a question about the class hierarchy.
It is deliberately *not* an authority for the whole rule: the verifier
answers "yes" for every interface target without checking anything and
defers the real check to the `invokeinterface` call site. So the comparison
runs on non-interface targets, and the interface divergence is counted
separately and asserted to be non-empty -- if it were empty the permissive
rule would be unreachable and the comparison would prove nothing about it.

Pairs are built along real superclass chains in both directions, plus arrays
of each, plus every pair of the eight primitive array types, because a random
pair of JDK classes is almost always "not assignable" and a comparison whose
answer is the same constant 99% of the time is a weak instrument however many
pairs it runs. Over the first 3000 classes of `java.base`:

```
--- isAssignable vs Class.isAssignableFrom: 43164 pairs on non-interface
    targets, 27194 assignable, 15970 not, 0 refused; interface targets: 8970
    where the verifier is more permissive than reflection, 1403 where both
    say yes
```

A second defect came out of the same comparison later, once it started
pairing every class with `java/lang/Object`. Reflection says an interface has
no superclass, so `Hierarchy` built from `getSuperclass()` recorded a null
super for every interface, and `isJavaSubclassOf` then answered no to
"is `CharSequence` assignable to `Object`". The class file says
`java/lang/Object` and so does JVMS 4.1; reflection is the odd one out.

It found a real defect on its first run. `byte`, `char`, `short`, `boolean`
and `int` are all the single verification type `int`, so comparing array
element types as verification types made `[C` assignable to `[I`. Forty pairs
failed, every one of them a primitive array. The element comparison is on
descriptors now.

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

## The verdict, against the JVM's own

Two comparisons, and refusals are counted apart from agreement in both.

Over `java.base` from this machine's JDK 21 image, every class asked about
through the real system loader under `-Xverify:all`:

```
--- the JVM vs classv: 7573 agree accept, 0 agree reject, 0 false accept,
    0 false reject
--- the JVM vs classv: the JVM had no opinion on 1
```

classv refused none of the 7574. The one the JVM has no opinion about is
`module-info.class`, which is not a class you can ask `Class.forName` for.

A corpus of valid classes can only catch classv rejecting good code, so the
other direction is a mutation sweep: 45 recipes aimed at the rules of JVMS
4.10.1 and at the structural checks around them, each applied to 150 real
classes drawn from the image.

```
--- mutation sweep: 7 agree accept, 2736 agree reject (2454 in the same
    category), 0 false accept, 0 false reject
--- mutation sweep: classv refused 150
--- mutation sweep: the JVM had no opinion on 104
--- mutation sweep: 2997 mutants built, 2743 answered about by both
```

Read the refusal line first. All 150 refusals are one recipe, `major:45`,
which rewrites the version to 45.0 and so produces a class the JVM verifies
by type *inference*; classv refuses that path by name. That recipe therefore
measures the refusal and nothing else, and the sweep says so in its own
output. The 103 the JVM has no opinion on are mutants whose `VerifyError`
names some other class than the one being asked about, which is the loader
split described above and not a verdict.

Of the 2736 agreed rejections, 2454 are in the same category: both sides call
it a verification error, or both call it a malformed file. The remaining 282
are agreed rejections where one side says `VERIFY` and the other `FORMAT`.
That is a weaker agreement than the headline, which is why the number is
printed.

### Naming the same instruction

Agreeing that a class is bad while pointing at a different instruction is a
much weaker claim than the verdict reads as, so wherever both sides report a
verification error the comparison also reads the bci out of both messages.
HotSpot prints `Location: X.f()V @11: goto`; classv prints the same shape.

```
--- mutation sweep: of 1698 where both report a verification error, 1556 name
    the same instruction of the same method, 63 match the bci in the JVM's
    Current Frame rather than its Location, 0 name the branch whose target
    the JVM named, 0 name a different instruction of that method, 79 are a
    class with more than one broken method where the two sides got to a
    different one first, 0 where one side named no instruction
```

The 79 are not a disagreement. HotSpot verifies the methods of a class in the
order `InstanceKlass` holds them, which is sorted by name and signature and
not the order they appear in the file, so a mutation that breaks every method
of a class gets a different first victim from each side. 126 of 1698 looked
like bci disagreements until the method name was compared as well.

The 63 are the stack map table errors, where HotSpot's `Location:` line says
`@0` because the table is read before the walk starts, and the instruction it
is really complaining about appears in its `Current Frame:` block instead.
classv names that one, and it is matched against HotSpot's own second field
rather than waved through: the bci is exact in all 63.

### The same source at four releases

`t/fixtures/Shapes.java` exists to make javac emit as many shapes as it can
be persuaded to: a tableswitch and a lookupswitch and a switch on a string
and a switch on an enum, lambdas and a method reference, try-with-resources,
a multi catch, nested finally, a synchronized block, two word locals, a
multianewarray, an inner class capturing its outer instance, an anonymous
class, varargs, a labelled break. It is compiled at `--release` 8, 11, 17
and 21, each with `-g` and `-g:none`, and both oracles run on all eight
corpora: javap on the parse, the JVM on the verdict.

What that is worth, measured rather than claimed: the disassembly at 11, 17
and 21 is identical for this source, so the corpus has two shapes and not
four. Release 8 is the one that differs, by 1485 lines of javap output, and
almost all of it is string concatenation moving from `StringBuilder` to an
`invokedynamic` against `StringConcatFactory`: 691 instructions at 8 against
643 at 11 and up. `-g:none` changes the Utf8 count and nothing else.

### Class files this JDK did not build

`t/jars.py` unpacks every jar it can find under `$HOME/.cache`,
`/usr/share/java` and `/run/host/usr/share/java` and splits the class files
by major version. On this machine that is 5243 files: 288 at version 48,
4315 at 49, 637 at 52 and 3 at 65. The counts depend on what is installed,
and the suite prints `SKIP` rather than inventing a corpus when there are no
jars.

Below major version 50 there is no `StackMapTable`, so the JVM type-infers
the frames and classv refuses by name. The refusal is the thing being
measured there, and a refusal needs an outside authority as much as an
answer does: the suite requires the JVM to **accept** all of them, so a
refusal that was quietly covering up a disagreement would fail.

```
--- 4603 old class files
--- jars, old: classv refused 4581
--- jars, old: of the refused, the JVM said ACCEPT 4581
--- jars, old: the JVM had no opinion on 22
```

The 22 are missing dependencies: `org/apache/commons/logging/Log` and
similar, libraries that are not installed here.

Above 50 they are ordinary class files and get the ordinary comparison: 637
of 640 accepted by both sides, 3 the JVM has no opinion on. One of those is
a class whose dependency is absent, and classv refuses that one naming the
same missing type the JVM's `NoClassDefFoundError` names.

### Corruption, where there is no oracle

`t/fuzz.py` truncates, flips a bit, replaces a byte, scribbles over a short
run of bytes, or deletes a few bytes, in real class files, and requires
classv to answer in its own vocabulary: one status word per file, nothing on
stderr, no Java exception escaping the parser. There is no outside authority
for that and it does not need one, because the property is about classv's
behaviour and not about the verdict. For a scrambled file there is often no
right verdict to have.

2000 mutants per run from a fixed seed, and 8000 across two seeds by hand:
no escape. That is a negative result and it is reported as one. The sweep
was watched going red first, with the cursor's bounds check replaced by
`if (false)`: it names the mutant, prints the stack trace, and the run ends
`FUZZ_FAIL 2`.

The digest printed is of the mutant bytes rather than of the report about
them, so a seed that had stopped being used would show as the same digest
for every seed.

### Two rules about the superclass

JVMS 5.3.5 says the superclass may be neither an interface nor final.
HotSpot raises `IncompatibleClassChangeError` for both, at load time, before
the verifier runs. Random corruption found them: 5 of 11 remaining false
accepts over 6000 mutants.

The fixtures are built the way the error happens in life rather than by
patching bytes. `Sub` is compiled against an ordinary `class Base`, and then
a `Base.class` compiled from `interface Base` or from `final class Base` is
put beside it. Both sides reject both, and the suite checks that against
the JVM's answer each run.

classv has no third word for a rejection that is neither a format error nor
a verification error, so these come out as `FORMAT`, which the comparison
counts as an agreed rejection in a different category rather than as a
match.

### Class names

HotSpot is laxer about a class name than the language is, so a reader who
assumed Java identifiers would be wrong in both directions.
`t/name_cases.py` patches the fixture's own `this_class` name, three bytes
at a time, and the suite asks the JVM about each case every run.

Rejected: a `.`, a `;`, a `[` outside an array descriptor, and an empty
component, which is what `a//`, a leading slash and a trailing slash all
produce. Accepted: a space, a `<`, a leading digit, and of course an
ordinary `a/b`.

### Modified UTF-8

JVMS 4.4.7 is not UTF-8, and the differences are exactly where a reader goes
wrong. The constant pool already decoded the encoding and flagged the
malformed cases; it did not reject them, so a corrupted string constant was
a class file classv accepted and the JVM did not. 45 of the first 51 false
accepts from the random corruption sweep were this one hole.

Which cases HotSpot rejects is measured rather than read off the page.
`t/utf8_cases.py` plants ten byte sequences in the one Utf8 constant of a
fixture that nothing structural reads, and the suite asks the JVM about each
one every run:

| bytes | HotSpot | classv |
|---|---|---|
| ordinary ASCII | accept | accept |
| `c0 80`, which is how this encoding writes NUL | accept | accept |
| `ed a0 80`, an unpaired surrogate | accept | accept |
| a bare `00` | reject | reject |
| `c0 af` and `c1 bf`, overlong two byte forms | reject | reject |
| `e0 80 af`, an overlong three byte form | reject | reject |
| `f0 90 80 80`, a real four byte UTF-8 sequence | reject | reject |
| `80` with no lead byte | reject | reject |
| a lead byte at the end of the string | reject | reject |

### Where HotSpot is stricter than the spec

The bci comparison found one place where following JVMS 4.10.1.9 to the
letter makes classv accept what HotSpot rejects. The spec's second `putfield`
rule allows the objectref to be `uninitializedThis` when the Fieldref names
the current class. HotSpot calls `find_local_field`, so it also requires the
field to be *declared* in the current class.

`t/fixtures/Uninit.java` is the difference in two classes. `Own` declares
`int v`; `Sub` inherits `int m` from `Base`; both constructors assign to
their field through a Fieldref naming their own class, which is what javac
emits. With the superclass constructor call dropped from each, HotSpot
rejects `Sub` at the `putfield` and gets as far as the `return` on `Own`.
classv matches HotSpot, bci included, and the suite checks that against
HotSpot's answer rather than against a recorded string.

Three recipes produced no mutant at all over this sample
(`subst:ladd:iadd`, `subst:pop2:pop`, `subst:nop:pop`), and the sweep names
them rather than quietly reporting a smaller corpus.

## What does not work

**The type inference verifier**, JVMS 4.10.2, the pre-Java-6 path with no
`StackMapTable`. Class file version 49 and below are refused by name. Version
50 is refused too, and for a different reason: 50 is the one version where
HotSpot retries with the inference verifier if type checking fails, so a
rejection from classv would not predict the JVM's answer even when classv is
right.

**`jsr` and `ret`** are rejected rather than analysed, which is what the type
checking path does, so that is not a gap. Classes needing them are version 49
or below and are refused before the question arises.

**The structural checks are not a complete class file validator.** The
load-time rules in `Verifier.structure()` are only the ones a mutation sweep
has shown the JVM to be reachable on, each with the HotSpot diagnosis it was
added for. A malformed class file that no recipe produces may well be
accepted.

**Attributes parsed as opaque bytes**: `Exceptions`, `BootstrapMethods`,
`Record`, and the annotation attributes. A length error inside one is
invisible.

**A crash is a result classv prints, not a path it is known not to have.**
Anything that escapes the analysis of one file is caught, printed as
`INTERNAL` with the classv source line it came through, given exit status 5,
and the run carries on to the next file. Zero over 8000 random mutants and
the whole corpus, which is a measurement of this corpus and not a proof.

**Almost one compiler.** Everything measured here came out of javac, and
nearly all of it out of the one javac that built this machine's JDK image.
Kotlin, Scala and ecj are not installed, so the variety of stack map shapes
is narrower than the format allows. `t/fixtures/Shapes.java` is compiled at
four `--release` levels to widen it, and the honest measurement of how much
that widens it is in the next section: not as much as four sounds.

## License

GPL-3.0. See `LICENSE`.
