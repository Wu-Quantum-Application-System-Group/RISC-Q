#include"mmio.h"

int main(){
  set_readout_demod_freq(DEMOD_FREQ_GHZ(0.1));
  set_readout_demod_phase(PHASE_PI(1.5));
  set_pulse_freq(1, FREQ_GHZ(0.1));

  time_t time = get_time();
  set_start_time(TIME_NS(300));

  set_pulse_phase(1, 1, PHASE_PI(0.5));
  set_pulse_amplitude(1, 1, 0x7fff0000);
  set_pulse_envelope(1, 1, 0);
  set_pulse_duration(1, 1, TIME_NS(4));
  play_pulse(1, 0);

  set_readout_decoder_duration(TIME_NS(100));
  int res = get_readout_decoder_result();
  (*(volatile int*)(0x80000400)) = res;
  return 0;
}
