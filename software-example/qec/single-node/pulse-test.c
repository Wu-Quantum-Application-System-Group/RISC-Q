#include "mmio.h"

int main() {
  int time0 = READ_INT32(MTIME);

  int freq = FREQ_GHZ(0.1);
  int start_time = time0 +TIME_NS(200);

  SET_START_TIME(start_time); // setup the startTime register
  SET_PULSE_FREQ(1, FREQ_GHZ(0.1)); // push (0.1GHz, startTime) to the TimedFIFO for the freq parameter, so that the output frequency of the 2nd signal generator will be set to 0.1GHz at 200ns
  SET_PULSE_PHASE(1, PHASE_PI(0.5)); // set the phase to 0.5 pi at 200ns
  SET_PULSE_AMP(1, 0x7ff0); // set the amplitude to 0x7ff0 at 200ns
  SET_PULSE_ADDR(1, 0); // set the envelope address to 0 at 200ns
  SET_PULSE_DUR(1, TIME_NS(100)); // set the duration of the pulse to 8ns at 200ns

  return 0;
}