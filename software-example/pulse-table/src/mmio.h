#include<stdint.h>

/* IO definitions (access restrictions to peripheral registers) */
/**
    \defgroup CMSIS_glob_defs CMSIS Global Defines

    <strong>IO Type Qualifiers</strong> are used
    \li to specify the access to peripheral variables.
    \li for automatic generation of peripheral register debug information.
*/
#ifdef __cplusplus
  #define   __I     volatile             /*!< Defines 'read only' permissions */
#else
  #define   __I     volatile const       /*!< Defines 'read only' permissions */
#endif
#define     __O     volatile             /*!< Defines 'write only' permissions */
#define     __IO    volatile             /*!< Defines 'read / write' permissions */

/* following defines should be used for structure members */
#define     __IM     volatile const      /*! Defines 'read only' structure member permissions */
#define     __OM     volatile            /*! Defines 'write only' structure member permissions */
#define     __IOM    volatile            /*! Defines 'read / write' structure member permissions */

/*@} end of group ARMv8MML */



#define MTIME 0xBFF8
#define MTIMECMP 0x4000
#define MTIMEWAIT 0x4008

typedef uint32_t time_t;

#define write_word(addr, data) (*(volatile uint32_t *)(addr) = (data))
#define read_word(addr) (*(volatile uint32_t *)(addr))

#define FREQ_GHZ(x) ((int)(x * (1 << 13)) << 16)
#define DEMOD_FREQ_GHZ(x) ((int)(x * (1 << 15)) << 16)
#define TIME_NS(x) (int)(x/2)
#define PHASE_PI(x) ((int)(x * (1 << 15)) << 16)
#define ENV_ADDR(x) (x << 16)

static inline time_t get_time() {
  return read_word(MTIME);
}

#define START_TIME_BASE 0x4100
static inline void set_start_time(time_t time) {
  write_word(START_TIME_BASE, time);
}

typedef struct {
  __OM int32_t phase;
  __OM int32_t amplitude;
  __OM int32_t envelope;
  __OM int32_t duration;
} pulse_table_t;


#define PULSE_TABLE_BASE 0x10000
#define PULSE_TABLE_OFFSET 0x10000
// #define PULSE_TABLE(i,j) ((pulse_table_t *)(PULSE_TABLE_BASE + i * PULSE_TABLE_OFFSET + j * sizeof(pulse_table_t)))
#define PULSE_TABLE(channel_id,pulse_id) (((pulse_table_t *)(PULSE_TABLE_BASE + channel_id * PULSE_TABLE_OFFSET) + pulse_id))
#define FREQ_BASE(channel_id) (*(volatile int*)(PULSE_TABLE_BASE + channel_id * PULSE_TABLE_OFFSET + 4))
#define OUT_ID_BASE(channel_id) (*(volatile int*)(PULSE_TABLE_BASE + channel_id * PULSE_TABLE_OFFSET))

static inline void set_pulse_freq(int32_t channel_id, int32_t freq) {
  FREQ_BASE(channel_id) = freq;
}

static inline void set_pulse_phase(int32_t channel_id, int32_t pulse_id, int32_t phase) {
  PULSE_TABLE(channel_id, pulse_id)->phase = phase;
}

static inline void set_pulse_amplitude(int32_t channel_id, int32_t pulse_id, int32_t amp) {
  PULSE_TABLE(channel_id, pulse_id)->amplitude = amp;
}

static inline void set_pulse_envelope(int32_t channel_id, int32_t pulse_id, int32_t env) {
  PULSE_TABLE(channel_id, pulse_id)->envelope = env;
}

static inline void set_pulse_duration(int32_t channel_id, int32_t pulse_id, int32_t dur) {
  PULSE_TABLE(channel_id, pulse_id)->duration = (dur << 16);
}

static inline void play_pulse(int32_t channel_id, int32_t pulse_id) {
  OUT_ID_BASE(channel_id) = pulse_id;
}

typedef struct {
  __OM int32_t frequency;
  __OM int32_t phase;
} readout_demod_t;

#define READOUT_DEMOD_BASE 0x30000
#define READOUT_DEMOD (((readout_demod_t *)(READOUT_DEMOD_BASE)))

static inline void set_readout_demod_freq(int32_t freq) {
  READOUT_DEMOD->frequency = freq;
}

static inline void set_readout_demod_phase(int32_t phase) {
  READOUT_DEMOD->phase = phase;
}

typedef struct {
  __OM int32_t duration;
  __IM int32_t result;
  __IM int32_t real;
  __IM int32_t imag;
} readout_decoder_t;

#define READOUT_DECODER_BASE 0x40000
#define READOUT_DECODER (((readout_decoder_t *)(READOUT_DECODER_BASE)))

static inline void set_readout_decoder_duration(int32_t dur) {
  READOUT_DECODER->duration = (dur << 16);
}

static inline int32_t get_readout_decoder_result() {
  return READOUT_DECODER->result;
}

static inline int32_t get_readout_decoder_real() {
  return READOUT_DECODER->real;
}

static inline int32_t get_readout_decoder_imag() {
  return READOUT_DECODER->imag;
}