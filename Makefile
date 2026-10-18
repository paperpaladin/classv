# classv -- plain javac, no build system, no third party anything.
JAVAC  ?= javac
JFLAGS ?= -Xlint:all -encoding US-ASCII

SRC := $(wildcard src/classv/*.java)

all: build/.stamp

build/.stamp: $(SRC)
	@mkdir -p build
	$(JAVAC) $(JFLAGS) -d build $(SRC)
	@touch $@

test: all
	./t/run.sh

# Not part of `make test`: every mutant recompiles and reruns the whole
# suite, so a full sweep is hours. The target had never existed at all,
# which is why t/mutants.py was reachable from nothing: it was named once,
# in a comment, for a caller that did not exist. `make test` runs its
# --check mode instead, which asks only whether the patterns still apply.
mutants: all
	python3 t/mutants.py

clean:
	rm -rf build scratch

.PHONY: all test clean mutants
