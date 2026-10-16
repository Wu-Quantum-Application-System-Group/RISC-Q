# Standalone firmware build (mirrors riscq.build.compile_c, the primary path).
# riscq_map.h + link.ld are GENERATED — e.g.:
#   python3 -c "from riscq.map import *; m = SocMap(SocParams.load('../configs/sim-2q.json')); \
#               open('riscq_map.h','w').write(m.gen_header()); open('link.ld','w').write(m.gen_linker())"
CROSS ?= riscv64-unknown-elf-
ARCH  ?= rv32i
CFLAGS = -march=$(ARCH) -mabi=ilp32 -O2 -fwrapv -nostdlib -ffreestanding -mno-relax \
         -Werror=implicit-function-declaration -I.

all: main.bin

main.elf: main.c start.S muldiv.c riscq.h riscq_map.h link.ld
	$(CROSS)clang $(CFLAGS) -T link.ld -o $@ start.S main.c muldiv.c

main.bin: main.elf
	$(CROSS)objcopy -O binary $< $@

clean:
	rm -f main.elf main.bin

.PHONY: all clean
