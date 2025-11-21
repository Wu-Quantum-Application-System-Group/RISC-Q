.section .text
.globl _start
.option norvc
_start:
  lui sp, 1
  call main      # Call the C main function
  jal x0, 0
