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
5  a defect in classv itself                                      "INTERNAL"
```

5 exists because without it a crash exits 1, which is the status for a class
the type checker rejects. A harness that folded those two together would
count a stack trace as a correct rejection, and 59 of them did once.

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
- [x] A corpus from `javac --release 8..21`, `-g` and `-g:none`
      why: the JDK image is one compiler at one release. Say what the corpus
      actually contains.
      done: t/fixtures/Shapes.java at releases 8, 11, 17 and 21, each with
      -g and -g:none, both oracles on each. It also measures how little
      that buys: the disassembly at 11, 17 and 21 is identical, so the
      corpus has two shapes and not four.
- [x] Classes with no StackMapTable refused by name
      why: an unimplemented path must fail loudly. javac 21 cannot produce
      one (its oldest --release is 8), so the corpus had to be real files.
      done: 4603 class files of major version 48 and 49 out of jars on this
      machine, every one refused by classv and accepted by the JVM. The
      refusal is measured against the JVM's acceptance, so a refusal
      covering up a rejection would fail.
- [ ] A non-javac corpus
      why: every class in the JDK image came out of one compiler's backend,
      which reaches a narrow set of the legal shapes. Kotlin, Scala and ecj
      are not installed; say so rather than claim variety that is not there.
      The jar corpus is a different javac and a different decade, which is
      not the same thing as a different compiler.

### Milestone 3: the error message, which is the point

- [x] Blame the instruction being executed, not the handler target
      why: the bci comparison found it. For a handler whose target has no
      declared frame, classv names the target and HotSpot names the
      instruction inside the protected range, which is the convention
      everything else in both tools follows.
      done: the two divergences are gone, 1554 of 1698 now name the same
      instruction and 64 match HotSpot's Current Frame bci.
- [x] The putfield rule HotSpot actually implements
      why: also from the bci comparison. JVMS 4.10.1.9 allows putfield on
      uninitializedThis when the Fieldref names the current class; HotSpot
      additionally requires the field to be DECLARED in the current class,
      and rejects an inherited field reached through a Fieldref that names
      the subclass. Two three-line classes show the difference.
      done: t/fixtures/Uninit.java, checked against HotSpot's own answer
      including the bci. The sweep's bci divergences are now 0 of 1698.
- [x] Report the failing instruction, the frame at it, and the rule it broke
      why: "Bad type on operand stack" with no further explanation is the
      reason this program is worth writing.
      done: the frame as the instruction found it, printed under every
      rejection, asserted by the suite over 60 mutated corpus classes.
- [x] Compare the reported bci against the bci in the JVM's own message
      why: the JVM prints `Location: A.f()I @2: ireturn`, so the claim
      "we name the same instruction" has an oracle and should be measured.
      done: over the mutation sweep, 1553 of 1698 name the same instruction
      of the same method, 63 match the bci in HotSpot's Current Frame block
      instead of its Location line, 79 are classes with several broken
      methods where HotSpot's sorted method order picks a different one,
      and 3 are real divergences, which are the next two items.
- [x] Print the merge that failed, not just the frame
      why: a frame mismatch at a branch target is two frames and a target,
      and printing one of the three is the current state of the art.
      done: both frames, with the JVM asked to confirm each case is a real
      rejection.

### Milestone 4: the parts that are still soft

- [x] Put the random corruption to the JVM as well, in name-unique chunks
      why: the fuzz sweep only checked that classv stayed civil. The same
      mutants have a verdict from the JVM and it was not being asked for.
      done: 610 of 2000 answered by both, 18 false accepts left as a
      ratchet the suite fails above, 0 false rejects.
- [x] Access flag rules, JVMS 4.1, 4.5 and 4.6
      why: 62 of 210 flag variants were accepted by classv and rejected by
      the JVM, and the rules are version dependent in places.
      done: t/flag_cases.py, a sweep over 35 flag values in three places in
      two fixtures, 0 disagreements.
- [x] Unqualified names and the class names inside descriptors, JVMS 4.2.2
      why: three different rules for three kinds of name, all measured.
- [x] Close the last of the random corruption false accepts
      why: 3 of 625 at the default seed. Two were real (a NameAndType
      descriptor nothing refers to, and a truncation reported against the
      wrong file by the oracle) and the one left is the oracle too: a
      mutant in the unnamed module can never be a permitted subclass of a
      sealed java.base interface.
- [x] The fourth operand byte of invokeinterface, and the Record attribute
      why: both were unchecked.
      done: a new iizero recipe (58 mutants, 55 answered, all rejected by
      both) and the Record walk, with a record fixture in the attribute
      sweep. That sweep immediately found the opposite mistake: HotSpot
      does not check MethodParameters name indexes and classv did.
- [x] A sealed supertype permits a fixed list of subclasses, JVMS 5.3.5
      why: 6 of the 9 false accepts left after the other checks, all of
      them a corrupted class name falling out of a PermittedSubclasses list.
- [x] The superclass may be neither an interface nor final, JVMS 5.3.5
      why: random corruption found both, 5 of 11 remaining false accepts.
      done: measured against IncompatibleClassChangeError from the JVM,
      with fixtures built the way the error happens rather than by patching.
- [x] Class file versions, JVMS 4.1, including preview
      why: 65.65535 was accepted by classv and rejected by the JVM, and the
      minor version rule changes at major 56, which is easy to miss.
      done: ten versions measured against the JVM each run.
- [x] Reject illegal class names, JVMS 4.2.1
      why: random corruption, and the rule is laxer than anybody guesses.
      done: eight cases measured against the JVM, three of them accepted.
- [x] Reject malformed modified UTF-8
      why: found by random corruption, 45 of the first 51 false accepts.
      The decoder already knew; nothing acted on what it knew.
      done: ten cases measured against HotSpot one at a time, in the suite.

- [x] Truncation and corruption sweeps over the whole file
      why: a class file is attacker controlled input in any tool that reads
      one from a stranger, and "no exception escaped" is a claim with a
      cheap test.
      done: t/fuzz.py, five kinds of corruption, 2000 mutants per run and
      8000 across two seeds by hand, no escape. Watched going red with the
      cursor's bounds check disabled.
- [x] Never throw anything but our own exception types
      why: an ArrayIndexOutOfBoundsException escaping the parser is a bug
      report, not a diagnosis.
      done: anything that escapes one file is caught, named INTERNAL with
      the classv line it came through, given exit status 5, and the run
      carries on to the next file. The fuzz sweep fails on any of them.
      Zero over 8000 random mutants and the whole corpus.
- [x] Verify the `Exceptions`, `BootstrapMethods` and `Record` attributes
      why: they are parsed as opaque bytes today, so a length error in one
      is invisible until it is not.
      done for the ones whose entries are a fixed size, plus the walk for
      BootstrapMethods, measured by bumping each attribute's count and
      letting the JVM say which matter. Record, Module and the annotation
      attributes are still opaque and the README says so.
- [x] Older real classes from installed jars
      why: the inference path classes exist in the wild and the refusal
      needs to be measured against real ones, not synthesised ones.
      done: t/jars.py and oracle 4c. Also 640 class files of version 52 and
      65 from the same jars, which is real code this JDK did not build:
      637 accepted by both sides, 3 the JVM has no opinion on.
- [ ] A second JDK's module image as corpus
      why: there is a JDK 25 image on this machine with class file version
      69 in it, which is a different shape from anything javac 21 emits.
