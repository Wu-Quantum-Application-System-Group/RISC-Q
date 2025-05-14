#include "mmio.h"

int main() {
  WRITE_INT32(MTIME, 0); // setup ref time

  // suppose that the 4nd signal generator is connected to the readout resonator of the 2nd qubit

  // start sending the readout pulse at 200ns
  SET_START_TIME(TIME_NS(200));
  SET_PULSE_FREQ(4, FREQ_GHZ(0.1)); 
  SET_PULSE_PHASE(4, PHASE_PI(0.5));
  SET_PULSE_AMP(4, 0x7ff0);
  SET_PULSE_ADDR(4, 0);
  SET_PULSE_DUR(4, TIME_NS(1000));

  // start readout at 300ns
  SET_START_TIME(TIME_NS(300));
  // set the readout accumulate duration to 900 ns
  SET_RD_DUR(2, TIME_NS(900));

  int x = GET_RD_RES(2);

  return 0;
}