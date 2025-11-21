#include "mmio.h"

int main() {
  // suppose that the 4nd signal generator is connected to the readout resonator of the 2nd qubit

  // start sending the readout pulse at 200ns
  SET_PULSE_FREQ(0, FREQ_GHZ(0.1)); 
  SET_PULSE_FREQ(1, FREQ_GHZ(0.1)); 

  SET_START_TIME(TIME_NS(200));
  SET_PULSE_PHASE(1, PHASE_PI(0.5));
  SET_PULSE_AMP(1, 0x7ff0);
  SET_PULSE_ADDR(1, 0);
  SET_PULSE_DUR(1, TIME_NS(1000));

  // start readout at 100ns
  SET_START_TIME(TIME_NS(200));
  // set the readout accumulate duration to 900 ns
  SET_RD_DUR(0, TIME_NS(600));



  SET_START_TIME(TIME_NS(900));

  WRITE_INT32_HIGH(PULSE_ID_BASE(0) + 20, 0);
  WRITE_INT32_HIGH(PULSE_ID_BASE(0) + 20, 0);
  SET_PULSE_PHASE(0, PHASE_PI(0.5));
  SET_PULSE_AMP(0, 0x7ff0);
  SET_PULSE_ADDR(0, 0);
  SET_PULSE_DUR(0, TIME_NS(1000));

  WRITE_INT32_HIGH(PULSE_ID_BASE(0) + 20, 1);
  WRITE_INT32_HIGH(PULSE_ID_BASE(0) + 20, 1);
  SET_PULSE_PHASE(0, PHASE_PI(0.5));
  SET_PULSE_AMP(0, 0x4ff0);
  SET_PULSE_ADDR(0, 0);
  SET_PULSE_DUR(0, TIME_NS(1000));

  int x = GET_RD_RES(0);

  WRITE_INT32(PULSE_ID_BASE(0) + 24, x);

  return 0;
}