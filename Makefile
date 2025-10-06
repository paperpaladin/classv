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

clean:
	rm -rf build scratch

.PHONY: all test clean
