#include "mmio.h"

// original
// int main() {
//   WRITE_INT32(MTIME, 0);

//   SET_START_TIME(100);

//   int freq = DEMOD_FREQ_GHZ(0.1);
//   DEMOD_CARRIER(2, freq, 0);

//   SET_START_TIME(100);
//   WRITE_INT32_HIGH(RD_2_REFR, 7);
//   WRITE_INT32_HIGH(RD_2_REFI, 7);
//   WRITE_INT32_HIGH(RD_2_DUR, 4);

//   int res = READ_INT32(RD_2_RES);

//   return 0;
// }


//loop_back
// int main() {
//   WRITE_INT32(MTIME, 0);
//   WRITE_INT32(MTIMECMP, 0);

//   int demod_freq = DEMOD_FREQ_GHZ(0);
//   WRITE_INT32_HIGH(DCG_2_FREQ, demod_freq);
//   WRITE_INT32_HIGH(DCG_2_PHASE, PHASE_PI(0));


//   int start_time = 100;
//   SET_START_TIME(start_time);

//   int freq = FREQ_GHZ(0.09);
//   SET_PULSE_FREQ(0, freq);
//   PULSE(0, PHASE_PI(0), 0x7ffc, 0, TIME_NS(4096));

//   WRITE_INT32_HIGH(RD_2_DUR, 4);

//   return 0;
// }

int main() {
  WRITE_INT32(MTIME, 0);
  WRITE_INT32(MTIMECMP, 0);

  SET_START_TIME(TIME_NS(0));


  // int demod_freq = DEMOD_FREQ_GHZ(2.7568);
  // int demod_freq = DEMOD_FREQ_GHZ(0.107);
  // int demod_freq = DEMOD_FREQ_GHZ(0.108);
  // int demod_freq = DEMOD_FREQ_GHZ(0.109);
  // int demod_freq = DEMOD_FREQ_GHZ(0.110);
  // int demod_freq = DEMOD_FREQ_GHZ(0.09);
  int demod_freq = DEMOD_FREQ_GHZ(0.1);
  DEMOD_CARRIER(2, demod_freq, 0);
  // WRITE_INT32_HIGH(DCG_4_FREQ, 0);

  // int drv_freq = FREQ_GHZ(0.1);
  // SET_PULSE_FREQ(0, FREQ_GHZ(0.1));
  // PULSE(0, PHASE_PI(0), 0x7fff, 0, TIME_NS(4096));

  WRITE_INT32(MTIMECMP, TIME_NS(200));


  int x = READ_INT32(MTIMEWAIT);
  x = READ_INT32(MTIMEWAIT);

  WRITE_INT32_HIGH(RD_DUR_ADDR(2), 4);
//WRITE_INT32_HIGH(RD_4_REFR, 7);
//WRITE_INT32_HIGH(RD_4_REFI, 7);

  int res = READ_INT32(RD_RES_ADDR(2));

  return 0;
}