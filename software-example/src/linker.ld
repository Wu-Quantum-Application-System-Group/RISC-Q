ENTRY(_start)

SECTIONS
{
  /* Place everything starting from 0x80000000 */
  . = 0x80000000;

  .text : {
    *(.text)
  }

  .rodata : {
    *(.rodata)
  }

  .data : {
    *(.data)
  }

  .bss : {
    *(.bss)
    *(COMMON)
  }

  /DISCARD/ : {
    *(.comment)
    *(.note*)
    *(.eh_frame)
  }
}