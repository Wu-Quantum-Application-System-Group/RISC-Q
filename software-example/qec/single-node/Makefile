CC=clang
CFLAGS=-march=rv32i -nostdlib -O1

# Get all .c files
SRCS := write-syndrome.c

OBJS := write-syndrome.o


# Strip .c to get the executable names
# TGTS := $(patsubst src/%.c, build/%.elf, $(SRCS))
SYND := $(foreach i,$(shell seq 0 7),build/write-syndrome-$(i).elf)
DACADC := $(foreach i,$(shell seq 0 7),build/write-dac-adc-$(i).elf)

.PHONY: all

dacadc: clean $(DACADC)
syndrome: clean $(SYND)
pulse: build/pulse-test.elf

build/pulse-test.o: pulse-test.c
	$(CC) $(CFLAGS) -c $< -o $@ 

build/write-syndrome-%.o: write-syndrome.c
	$(CC) $(CFLAGS) -c $< -DID=$* -o $@ 

build/write-dac-adc-%.o: write-dac-adc.c
	$(CC) $(CFLAGS) -c $< -DID=$* -o $@ 

build/%.elf: build/start.o build/%.o
	$(CC) $(CFLAGS) -Wl,-T,linker.ld -mno-relax $^ -o $@ 

build/start.o: start.S
	$(CC) -mno-relax -c $< -o $@

clean:
	rm -f build/*


	# ld.lld -T linker.ld -mno-relax -e _start $^ -o $@
