#include "mmio.h"

int main() {
  // int time0 = READ_INT32(MTIME);
  // int start_time = 2147483647;
  int start_time = 100;
  WRITE_INT32(MTIMECMP, start_time);

  const int channel_idx = 0;
  SET_START_TIME(start_time); // setup the startTime register
  SET_PULSE_PHASE(channel_idx, PHASE_PI(0)); // set the phase to 0.5 pi at 200ns
  SET_PULSE_FREQ(channel_idx, FREQ_GHZ(0.001)); // push (0.1GHz, startTime) to the TimedFIFO for the freq parameter, so that the output frequency of the 2nd signal generator will be set to 0.1GHz at 200ns
  SET_PULSE_AMP(channel_idx, 0x7f00); // set the amplitude to 0x7ff0 at 200ns
  SET_PULSE_ADDR(channel_idx, 0); // set the envelope address to 0 at 200ns
  SET_PULSE_DUR(channel_idx, TIME_NS(8)); // set the duration of the pulse to 8ns at 200ns

  for(int i = 0; i < 10; ++i) { 
    int x = READ_INT32(MTIMEWAIT);
    x = READ_INT32(MTIMEWAIT);
    x = READ_INT32(MTIMEWAIT);
    start_time += TIME_NS(200);
    WRITE_INT32(MTIMECMP, start_time);
    SET_START_TIME(start_time); // setup the startTime register
    SET_PULSE_ADDR(channel_idx, 0); // set the envelope address to 0 at 200ns
    SET_PULSE_DUR(channel_idx, TIME_NS(8)); // set the duration of the pulse to 8ns at 200ns
  }
}
