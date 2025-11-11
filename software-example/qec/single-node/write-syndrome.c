#include "mmio.h"
#include "syndromes.h"

#define CONCAT(a, b) a##b
#define SYNDROME_NAME(id) CONCAT(SYNDROME_, id)
#define SYNDROME SYNDROME_NAME(ID)

int main() {
  int time0 = READ_INT32(MTIME);
  int start_time = time0 + 20;
  WRITE_INT32(MTIMECMP, start_time);

  for(int i = 0; i < 3; i++) {
    int x = READ_INT32(MTIMEWAIT);
    WRITE_INT32(0x800000, SYNDROME);
    start_time = start_time + 20;
    WRITE_INT32(MTIMECMP, start_time);
  }
}