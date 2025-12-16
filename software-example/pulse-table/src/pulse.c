#include"mmio.h"

int main(){

  time_t time = get_time();
  set_start_time(TIME_NS(300));

  set_pulse_freq(0, FREQ_GHZ(0.1));
  set_pulse_phase(0, 1, PHASE_PI(0.5));
  set_pulse_amplitude(0, 1, 0x7fff0000);
  set_pulse_envelope(0, 1, 0);
  set_pulse_duration(0, 1, TIME_NS(4));
  play_pulse(0, 0);
}
