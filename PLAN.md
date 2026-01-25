# PLAN

## Contract

```
classv [--info] [--pool] [--disasm] [--frames] [--verify] [--all] FILE.class...
classv ... @LISTFILE          one path per line, because the corpus is 27846 files
```

`FILE.class` is a JVM class file (JVMS chapter 4) of any major version. With no
flag, `--verify` is implied. One file per invocation gives the answer in the
exit status; more than one gives it in a status word per file, because starting
a JVM for each of 27846 classes costs more than reading them.

Exit status, all distinct so that a harness can never fold two of them together:

```
0  the class parses, and with --verify the type checker accepts it   "OK"
1  the type checker rejects it                                       "VERIFY"
2  usage error
3  classv refuses to analyse it                                      "REFUSE"
4  the file is not a well formed class file                          "FORMAT"
```

Pure Java 21 built by `javac` with no library beyond `java.base`, no jar, no
build system, no third party anything. The class file parser is written from
scratch; `java.lang.classfile` and ASM are both off the table, because the
parser is the point.

What must not break: **a REFUSAL is never reported as an answer.** A verifier
that declines to look at 60% of its corpus and agrees with the JVM on the rest
has not agreed with the JVM about anything much, so every count in the README
reports refusals on their own line.

## Hard invariant

**On every class file we agree with the JVM's own verifier about accept and
reject, or we refuse and say so.**

Concretely, and checked by `make test`:

- Over the JDK's own module image, every fact classv prints about the parse is
  identical to the fact `javap -v -p -c` prints: constant pool entry by entry,
  member by member, instruction by instruction, exception handler by exception
  handler. Not similar, identical, as extracted facts and never as text.
- Over the same corpus, classv's accept/reject verdict matches the verdict of
  a real `java` process that forces each class to link. Disagreements are
  listed individually; refusals are counted separately and never counted as
  agreement.
- Over a mutation corpus built by corrupting real classes in ways a verifier
  is supposed to catch, the same comparison holds, and the mutation sweep
  asserts it is non-vacuous: a sweep in which the JVM accepted everything
  would prove nothing.
- Every comparison asserts a nonzero count. A comparison over zero classes
  agrees with everything.

## Oracle

Ranked by how little of them we control:

1. **The JVM itself.** `java` loading the class and forcing it to link. This
   is the strongest oracle available for this program, because it is the
   artefact the project is a model of. Two paths are needed and both are used:
   a custom `ClassLoader` for ordinary packages, and `-Xverify:all` with the
   boot loader for `java.*` packages, which `ClassLoader.defineClass` refuses
   by name. `-Xverify:all` is load bearing in the second: without it a boot
   class is not verified at all, which was measured by planting a broken
   `java.lang.Zz` through `--patch-module` and watching it link cleanly.
2. **`javap -v -p -c`**, the JDK's own disassembler, written by other people
   and shipped to every Java developer. It cannot verify anything, but it
   pins the parse, which is what every later stage reads.
3. **`javac` across `--release` 8 to 21, with `-g` and `-g:none`**, as a
   generator of class files with different stack map shapes. A corpus that is
   one compiler at one release is a weak instrument.
4. **The JDK module image**, 27846 class files nobody here wrote.
5. **The mutation sweep.** Corruptions of real classes aimed at each rule in
   JVMS 4.10.1, each one asserted to be rejected by the JVM before classv's
   answer is looked at.
6. **Deliberate defects in classv itself**, each expected to turn a *named*
   assertion red, with the survivors written down.

## Non-goals

- Being a class file writer, a bytecode rewriter or an agent.
- Running bytecode. classv never executes anything it reads.
- The JVMS 4.10.2 type *inference* verifier (the pre-Java-6 path with no
  StackMapTable). It is a different algorithm with a different failure mode
  and no stack maps to check against; classes that need it are REFUSED by
  name, and the count is reported.
- `jsr` and `ret`. They are legal only on the inference path and the type
  checking path forbids them outright (JVMS 4.10.1: "the subroutine
  instructions are not allowed"). classv rejects them the way the JVM does.
- Linkage checks the verifier defers to run time: whether a method actually
  exists, whether a field is accessible, loader constraints. The type checker
  deliberately does not do these and neither do we.
- Rewriting the class file to make it verify.

## Commit sequence

### Milestone 0: read the bytes

- [x] Bounds checked cursor, constant pool, members, attributes, and `--info`
      why: everything later reads untrusted bytes, so the cursor with its
      explicit ClassFormatException has to exist before anything uses it.
- [x] The opcode table and the instruction decoder, and `--disasm`
      why: the verifier is a walk over instructions, and a decoder that gets a
      length wrong turns every later claim into noise.
- [x] The javap oracle: pool, members, code and handlers, fact by fact
      why: no later number means anything if the parse is wrong, and "it
      looks like a disassembly" is otherwise the only standard available.
      done: 7574 classes of java.base, 453680 pool operands, 604799 Utf8
      constants, 1874309 instructions, 0 disagreements. Five mutations of
      the decoder each turn it red.

### Milestone 1: the type checker

- [x] The verification type representation, JVMS 4.10.1.2
      why: the stack map table holds verification types, so the type has to
      exist before the table can be read. isAssignable is the next item.
- [x] `isAssignable` and the lattice, JVMS 4.10.1.2
      why: every instruction rule is phrased in terms of it, and the
      interface rule (any reference is assignable to any interface type) is
      the one everybody gets wrong by being too strict.
      done: 40164 pairs against Class.isAssignableFrom over 3000 classes,
      0 disagreements, with the 8970 interface-target divergences counted
      apart and asserted non-empty. Six mutations all caught. One real
      defect found: [C was assignable to [I because char and int are the
      same verification type.
- [x] The class hierarchy resolver the lattice needs
      why: `isJavaAssignable(class(X), class(Y))` is a question about loaded
      classes, so it needs an answer from somewhere; the corpus's own files
      plus reflection is the honest source, and where neither can answer the
      result must be a refusal, not a guess.
- [x] StackMapTable parsing, all six frame kinds, and `--smt`
      why: the split verifier reads the frames rather than inferring them,
      so an off-by-one in the offset delta is an off-by-one in the whole
      algorithm. Compared against javap's own StackMapTable dump.
      done: 104155 frames and 125930 verification types over java.base,
      0 disagreements. Six mutations of the frame reader, five caught by
      the frame comparison; the sixth (the 64 bias on a
      same_locals_1_stack_item delta) survived it, because javap prints
      only the frame type byte for those, and is caught by the new
      assertion that every expanded offset lands on an instruction
      boundary. One mutation of the expansion is provably equivalent
      (dropping the first-frame special case, since prev starts at -1)
      and is recorded as a survivor.
- [x] The instruction transition table, JVMS 4.10.1.9
      why: this is the body of the algorithm. Roughly 200 rules.
      done: every opcode has a rule, wide included; jsr, ret and jsr_w are
      rejected the way the type checking path rejects them.
- [x] Frame merging at branch targets and the exception handler rule
      why: this is where real classes fail, and the handler rule
      (every instruction in the range must be assignable to the handler's
      frame) is the one that costs the most to get right.
      done: 7573 of 7574 java.base classes agree with the JVM, 0 refused.
- [x] Constructors: uninitialized, uninitializedThis, and flagThisUninit
      why: it is the only part of the algorithm with a flag, and the only
      place where a type changes identity mid-frame.
      done, with one hole: return from a constructor that never called
      another constructor is accepted. See the next item.
- [x] The flagThisUninit check at return, and a mutation that reaches it
      why: it is a false accept, measured: HotSpot answers "Constructor must
      call super() or this() before return" to a class classv says OK to.
      No recipe in the sweep produces that shape, which is why it survived.
      done: the noinit recipe, 73 mutants, 72 answered, all rejected by both.
      With the check disabled 31 of 37 in a smaller sample become OK.
- [x] The JVM oracle harness, both loading paths
      why: this is the headline number and it does not exist yet.
      done: t/Probe.java. 27777 of 27846 module image classes answered
      about, 0 rejected, 69 with no opinion (the module-info files). The
      oracle is shown saying no on 19 planted defects covering six distinct
      HotSpot diagnoses, and -Xverify:all is shown to be load bearing by
      planting a broken java.lang class through --patch-module and watching
      it link cleanly without the flag.

### Milestone 2: make the agreement mean something

- [x] t/mutate.py and t/cf.py: a way to build a corrupted class file
      why: a corpus of valid classes can only catch classv rejecting valid
      code. Nine recipes so far, positioned by javap's instruction offsets
      rather than by classv's own decoder.
- [x] A mutation corpus aimed at JVMS 4.10.1, one family per rule
      why: a corpus of valid classes can only catch classv rejecting valid
      code. The other direction needs invalid code the JVM rejects.
      done: t/mutation_sweep.py, 45 recipes over 150 real classes, 2997
      mutants, 2736 rejected by both sides, 0 false accept, 0 false reject,
      150 refused and counted apart. The sweep names the recipes that
      produced no mutant and the recipes the JVM accepted every mutant of,
      because either is a recipe that measures nothing.
- [ ] Compare the *reason* and not only the verdict
      why: agreeing that a class is bad while disagreeing about which
      instruction is bad is a much weaker claim than it reads as.
- [ ] A corpus from `javac --release 8..21`, `-g` and `-g:none`
      why: the JDK image is one compiler at one release. Say what the corpus
      actually contains.
- [ ] Classes with no StackMapTable (`--release 6`) refused by name
      why: an unimplemented path must fail loudly, and `javac` can still
      produce them.
- [ ] A non-javac corpus
      why: every class in the JDK image came out of one compiler's backend,
      which reaches a narrow set of the legal shapes. Kotlin, Scala and ecj
      are not installed; say so rather than claim variety that is not there.

### Milestone 3: the error message, which is the point

- [ ] Blame the instruction being executed, not the handler target
      why: the bci comparison found it. For a handler whose target has no
      declared frame, classv names the target and HotSpot names the
      instruction inside the protected range, which is the convention
      everything else in both tools follows.
- [ ] The putfield rule HotSpot actually implements
      why: also from the bci comparison. JVMS 4.10.1.9 allows putfield on
      uninitializedThis when the Fieldref names the current class; HotSpot
      additionally requires the field to be DECLARED in the current class,
      and rejects an inherited field reached through a Fieldref that names
      the subclass. Two three-line classes show the difference.
- [ ] Report the failing instruction, the frame at it, and the rule it broke
      why: "Bad type on operand stack" with no further explanation is the
      reason this program is worth writing.
- [x] Compare the reported bci against the bci in the JVM's own message
      why: the JVM prints `Location: A.f()I @2: ireturn`, so the claim
      "we name the same instruction" has an oracle and should be measured.
      done: over the mutation sweep, 1553 of 1698 name the same instruction
      of the same method, 63 match the bci in HotSpot's Current Frame block
      instead of its Location line, 79 are classes with several broken
      methods where HotSpot's sorted method order picks a different one,
      and 3 are real divergences, which are the next two items.
- [ ] Print the merge that failed, not just the frame
      why: a frame mismatch at a branch target is two frames and a target,
      and printing one of the three is the current state of the art.

### Milestone 4: the parts that are still soft

- [ ] Truncation and corruption sweeps over the whole file
      why: a class file is attacker controlled input in any tool that reads
      one from a stranger, and "no exception escaped" is a claim with a
      cheap test.
- [ ] Never throw anything but our own exception types
      why: an ArrayIndexOutOfBoundsException escaping the parser is a bug
      report, not a diagnosis.
- [ ] Verify the `Exceptions`, `BootstrapMethods` and `Record` attributes
      why: they are parsed as opaque bytes today, so a length error in one
      is invisible until it is not.
- [ ] `--release 6` and older real classes from installed jars
      why: the inference path classes exist in the wild and the refusal
      needs to be measured against real ones, not synthesised ones.
- [ ] A second JDK's module image as corpus
      why: there is a JDK 25 image on this machine with class file version
      69 in it, which is a different shape from anything javac 21 emits.
