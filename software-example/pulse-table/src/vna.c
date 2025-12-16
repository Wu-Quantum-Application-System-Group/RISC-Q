#include"mmio.h"

#define VNA_ROUNDS 5
#define INIT_FREQ 0.2
#define FREQ_STEP 0.01

typedef struct {
  int32_t real;
  int32_t imag;
} complex_t;

complex_t *res = (complex_t *)0x80000100;


int main(){
  int demod_freq = DEMOD_FREQ_GHZ(INIT_FREQ);
  int readout_freq = FREQ_GHZ(INIT_FREQ);
  const int readout_duration = TIME_NS(100);
  set_readout_demod_phase(PHASE_PI(0));

  set_pulse_phase(1, 1, PHASE_PI(0));
  set_pulse_amplitude(1, 1, 0x7fff0000);
  set_pulse_envelope(1, 1, 0);
  set_pulse_duration(1, 1, readout_duration);

  int start_time = get_time() + TIME_NS(200);

  for(int i = 0; i < VNA_ROUNDS; i++){
    set_start_time(start_time);

    set_readout_demod_freq(demod_freq);

    set_pulse_freq(1, readout_freq);
    play_pulse(1, 0);

    set_readout_decoder_duration(readout_duration);

    demod_freq += DEMOD_FREQ_GHZ(FREQ_STEP);
    readout_freq += FREQ_GHZ(FREQ_STEP);
    start_time += readout_duration + TIME_NS(200);

    int _x = get_readout_decoder_result();
    res[i].real = get_readout_decoder_real();
    res[i].imag = get_readout_decoder_imag();
  }
  return 0;
}
