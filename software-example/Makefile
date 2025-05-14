CC=clang
CFLAGS=-march=rv32i -nostdlib -O3

# Get all .c files
SRCS := $(wildcard src/*.c)

OBJS := $(patsubst src/%.c, build/%.o, $(SRCS))


# Strip .c to get the executable names
TGTS := $(patsubst src/%.c, build/%.elf, $(SRCS))

.PHONY: all

all: clean $(TGTS)

build/%.o: src/%.c
	$(CC) $(CFLAGS) -c $< -o $@ 

build/%.elf: build/start.o build/%.o
	$(CC) $(CFLAGS) -Wl,-T,src/linker.ld -mno-relax $^ -o $@ 

build/start.o: src/start.S
	$(CC) -mno-relax -c $< -o $@

clean:
	rm -f build/*


	# ld.lld -T linker.ld -mno-relax -e _start $^ -o $@
