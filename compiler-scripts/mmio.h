#include<stdint.h>
#define WRITE_INT32(ADDR, DATA) *(volatile int32_t *)ADDR = DATA
#define WRITE_INT32_HIGH(ADDR, DATA) *(volatile int32_t *)ADDR = (DATA << 16)
#define READ_INT32(ADDR) *(volatile int32_t *)ADDR

// const volatile int32_t * MTIME = (volatile int32_t *) 0xBFF8;
// const volatile int32_t * MTIMECMP = (volatile int32_t *) 0x4000;
// const volatile int32_t * MTIMEWAIT = (volatile int32_t *) 0x4008;
#define MTIME 0xBFF8
#define MTIMECMP 0x4000
#define MTIMEWAIT 0x4008

#include<stdint.h>
#define WRITE_INT32(ADDR, DATA) *(volatile int32_t *)ADDR = DATA
#define WRITE_INT32_HIGH(ADDR, DATA) *(volatile int32_t *)ADDR = (DATA << 16)
#define READ_INT32(ADDR) *(volatile int32_t *)ADDR

// const volatile int32_t * MTIME = (volatile int32_t *) 0xBFF8;
// const volatile int32_t * MTIMECMP = (volatile int32_t *) 0x4000;
// const volatile int32_t * MTIMEWAIT = (volatile int32_t *) 0x4008;
#define MTIME 0xBFF8
#define MTIMECMP 0x4000
#define MTIMEWAIT 0x4008

#define FREQ_GHZ(x) (int)(x * (1 << 13))
#define DEMOD_FREQ_GHZ(x) (int)(x * (1 << 15))
#define TIME_NS(x) (int)(x / 2)
#define PHASE_PI(x) (int)(x * (1 << 15))

#define PULSE(ID, ADDR, AMP, DUR, PHASE, START) \
  WRITE_INT32(PG_##ID##_ADDR, ADDR); \
  WRITE_INT32(PG_##ID##_AMP, AMP); \
  WRITE_INT32(PG_##ID##_DUR, DUR); \
  WRITE_INT32(PG_##ID##_PHASE, PHASE); \
  WRITE_INT32(PG_##ID##_START, START);

#define CARRIER(ID, FREQ, PHASE) \
  WRITE_INT32(CG_##ID##_FREQ, FREQ); \
  WRITE_INT32(CG_##ID##_PHASE, PHASE); \

#define PG_0_ADDR 0x10000 
#define PG_0_AMP 0x10004 
#define PG_0_DUR 0x10008 
#define PG_0_PHASE 0x1000c 
#define PG_0_START 0x10010 
#define PG_1_ADDR 0x10100 
#define PG_1_AMP 0x10104 
#define PG_1_DUR 0x10108 
#define PG_1_PHASE 0x1010c 
#define PG_1_START 0x10110 
#define PG_2_ADDR 0x10200 
#define PG_2_AMP 0x10204 
#define PG_2_DUR 0x10208 
#define PG_2_PHASE 0x1020c 
#define PG_2_START 0x10210 
#define PG_3_ADDR 0x10300 
#define PG_3_AMP 0x10304 
#define PG_3_DUR 0x10308 
#define PG_3_PHASE 0x1030c 
#define PG_3_START 0x10310 
#define PG_4_ADDR 0x10400 
#define PG_4_AMP 0x10404 
#define PG_4_DUR 0x10408 
#define PG_4_PHASE 0x1040c 
#define PG_4_START 0x10410 
#define PG_5_ADDR 0x10500 
#define PG_5_AMP 0x10504 
#define PG_5_DUR 0x10508 
#define PG_5_PHASE 0x1050c 
#define PG_5_START 0x10510 
#define PG_6_ADDR 0x10600 
#define PG_6_AMP 0x10604 
#define PG_6_DUR 0x10608 
#define PG_6_PHASE 0x1060c 
#define PG_6_START 0x10610 
#define PG_7_ADDR 0x10700 
#define PG_7_AMP 0x10704 
#define PG_7_DUR 0x10708 
#define PG_7_PHASE 0x1070c 
#define PG_7_START 0x10710 
#define PG_8_ADDR 0x10800 
#define PG_8_AMP 0x10804 
#define PG_8_DUR 0x10808 
#define PG_8_PHASE 0x1080c 
#define PG_8_START 0x10810 
#define PG_9_ADDR 0x10900 
#define PG_9_AMP 0x10904 
#define PG_9_DUR 0x10908 
#define PG_9_PHASE 0x1090c 
#define PG_9_START 0x10910 
#define PG_10_ADDR 0x10a00 
#define PG_10_AMP 0x10a04 
#define PG_10_DUR 0x10a08 
#define PG_10_PHASE 0x10a0c 
#define PG_10_START 0x10a10 
#define PG_11_ADDR 0x10b00 
#define PG_11_AMP 0x10b04 
#define PG_11_DUR 0x10b08 
#define PG_11_PHASE 0x10b0c 
#define PG_11_START 0x10b10 
#define PG_12_ADDR 0x10c00 
#define PG_12_AMP 0x10c04 
#define PG_12_DUR 0x10c08 
#define PG_12_PHASE 0x10c0c 
#define PG_12_START 0x10c10 
#define PG_13_ADDR 0x10d00 
#define PG_13_AMP 0x10d04 
#define PG_13_DUR 0x10d08 
#define PG_13_PHASE 0x10d0c 
#define PG_13_START 0x10d10 
#define PG_14_ADDR 0x10e00 
#define PG_14_AMP 0x10e04 
#define PG_14_DUR 0x10e08 
#define PG_14_PHASE 0x10e0c 
#define PG_14_START 0x10e10 
#define PG_15_ADDR 0x10f00 
#define PG_15_AMP 0x10f04 
#define PG_15_DUR 0x10f08 
#define PG_15_PHASE 0x10f0c 
#define PG_15_START 0x10f10 
#define CG_0_FREQ 0x20000 
#define CG_0_PHASE 0x20004 
#define CG_1_FREQ 0x20100 
#define CG_1_PHASE 0x20104 
#define CG_2_FREQ 0x20200 
#define CG_2_PHASE 0x20204 
#define CG_3_FREQ 0x20300 
#define CG_3_PHASE 0x20304 
#define CG_4_FREQ 0x20400 
#define CG_4_PHASE 0x20404 
#define CG_5_FREQ 0x20500 
#define CG_5_PHASE 0x20504 
#define CG_6_FREQ 0x20600 
#define CG_6_PHASE 0x20604 
#define CG_7_FREQ 0x20700 
#define CG_7_PHASE 0x20704 
#define CG_8_FREQ 0x20800 
#define CG_8_PHASE 0x20804 
#define CG_9_FREQ 0x20900 
#define CG_9_PHASE 0x20904 
#define CG_10_FREQ 0x20a00 
#define CG_10_PHASE 0x20a04 
#define CG_11_FREQ 0x20b00 
#define CG_11_PHASE 0x20b04 
#define CG_12_FREQ 0x20c00 
#define CG_12_PHASE 0x20c04 
#define CG_13_FREQ 0x20d00 
#define CG_13_PHASE 0x20d04 
#define CG_14_FREQ 0x20e00 
#define CG_14_PHASE 0x20e04 
#define CG_15_FREQ 0x20f00 
#define CG_15_PHASE 0x20f04 
#define CG_16_FREQ 0x21000 
#define CG_16_PHASE 0x21004 
#define CG_17_FREQ 0x21100 
#define CG_17_PHASE 0x21104 
#define CG_18_FREQ 0x21200 
#define CG_18_PHASE 0x21204 
#define CG_19_FREQ 0x21300 
#define CG_19_PHASE 0x21304 
#define CG_20_FREQ 0x21400 
#define CG_20_PHASE 0x21404 
#define CG_21_FREQ 0x21500 
#define CG_21_PHASE 0x21504 
#define CG_22_FREQ 0x21600 
#define CG_22_PHASE 0x21604 
#define CG_23_FREQ 0x21700 
#define CG_23_PHASE 0x21704 
#define RD_0_DUR 0x30000 
#define RD_0_REFR 0x30004 
#define RD_0_REFI 0x30008 
#define RD_0_RES 0x3000c 
#define RD_1_DUR 0x30100 
#define RD_1_REFR 0x30104 
#define RD_1_REFI 0x30108 
#define RD_1_RES 0x3010c 
#define RD_2_DUR 0x30200 
#define RD_2_REFR 0x30204 
#define RD_2_REFI 0x30208 
#define RD_2_RES 0x3020c 
#define RD_3_DUR 0x30300 
#define RD_3_REFR 0x30304 
#define RD_3_REFI 0x30308 
#define RD_3_RES 0x3030c 
#define RD_4_DUR 0x30400 
#define RD_4_REFR 0x30404 
#define RD_4_REFI 0x30408 
#define RD_4_RES 0x3040c 
#define RD_5_DUR 0x30500 
#define RD_5_REFR 0x30504 
#define RD_5_REFI 0x30508 
#define RD_5_RES 0x3050c 
#define RD_6_DUR 0x30600 
#define RD_6_REFR 0x30604 
#define RD_6_REFI 0x30608 
#define RD_6_RES 0x3060c 
#define RD_7_DUR 0x30700 
#define RD_7_REFR 0x30704 
#define RD_7_REFI 0x30708 
#define RD_7_RES 0x3070c 