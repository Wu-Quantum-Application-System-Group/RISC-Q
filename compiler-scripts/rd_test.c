#include "mmio.h"

int main() {
  WRITE_INT32(MTIME, 0);

  int freq = DEMOD_FREQ_GHZ(0.1);
  CARRIER(10, freq, 0);

  WRITE_INT32(MTIMECMP, 50);
  int x = READ_INT32(MTIMEWAIT);
  x = READ_INT32(MTIMEWAIT);
  x = READ_INT32(MTIMEWAIT);

  WRITE_INT32(RD_2_REFR, 7);
  WRITE_INT32(RD_2_REFI, 7);
  WRITE_INT32(RD_2_DUR, 4);

  int res = READ_INT32(RD_2_RES);

  return 0;
}
