#include "mmio.h"
#include "dac-adc-params.h"

#define CONCAT(a, b) a##b

#define DCG_FREQ_NAME(id) CONCAT(FREQ_, id)
#define DCG_FREQ DCG_FREQ_NAME(ID)

#define DCG_PHASE_NAME(id) CONCAT(DCG_PHASE_, id)
#define DCG_PHASE DCG_PHASE_NAME(ID)

#define PG_FREQ_NAME(id) CONCAT(FREQ_, id)
#define PG_FREQ PG_FREQ_NAME(ID)

#define PG_PHASE_NAME(id) CONCAT(PG_PHASE_, id)
#define PG_PHASE PG_PHASE_NAME(ID)

#define PG_AMP_NAME(id) CONCAT(PG_AMP_, id)
#define PG_AMP PG_AMP_NAME(ID)

#define RD_DUR 500

int main() {
  int time0 = READ_INT32(MTIME);
  int wait_time = time0 + 1000;
  WRITE_INT32(MTIMECMP, wait_time);

  WRITE_INT32_HIGH(DEMOD_FREQ_ADDR(0), DEMOD_FREQ_GHZ(DCG_FREQ));
  WRITE_INT32_HIGH(DEMOD_PHASE_ADDR(0), PHASE_PI(DCG_PHASE));

  int start_time = wait_time - 100;
  SET_START_TIME(start_time);
  WRITE_INT32_HIGH(PULSE_FREQ_ADDR(0), FREQ_GHZ(PG_FREQ));
  WRITE_INT32_HIGH(PULSE_PHASE_ADDR(0), PHASE_PI(PG_PHASE));
  WRITE_INT32_HIGH(PULSE_AMP_ADDR(0), PG_AMP);
  WRITE_INT32_HIGH(PULSE_ADDR_ADDR(0), 0);
  WRITE_INT32_HIGH(PULSE_DUR_ADDR(0), TIME_NS(60000));

  start_time = wait_time + 20;
  SET_START_TIME(start_time);

  for(int i = 0; i < 6; i++) {
    int x = READ_INT32(MTIMEWAIT);
    x = READ_INT32(MTIMEWAIT);
    WRITE_INT32_HIGH(RD_DUR_ADDR(0), RD_DUR);

    wait_time = wait_time + 1000;
    start_time = wait_time + 20;
    WRITE_INT32(MTIMECMP, wait_time);
    SET_START_TIME(start_time);

    int res = READ_INT32(RD_RES_ADDR(0));
    WRITE_INT32(0x800000, res);
  }
}