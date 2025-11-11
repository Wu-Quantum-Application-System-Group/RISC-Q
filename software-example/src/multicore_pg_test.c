#include "mmio.h"

int main() {
  WRITE_INT32(MTIMECMP, 0);

  int freq = FREQ_GHZ(0.1);
  int freq_step = FREQ_GHZ(0.1);
  int start_time = TIME_NS(200);
  int time_step = TIME_NS(200);

  for(int i = 0; i < 5; ++i) {
    int x = READ_INT32(MTIMEWAIT);

    SET_START_TIME(start_time);
    SET_PULSE_FREQ(0, freq);
    PULSE(0, PHASE_PI(0), 0x7f00, 0, TIME_NS(8));

    start_time += time_step;
    WRITE_INT32(MTIMECMP, start_time - TIME_NS(140));
    freq += freq_step;
  }

  return 0;
}