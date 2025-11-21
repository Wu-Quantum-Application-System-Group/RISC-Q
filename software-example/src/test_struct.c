typedef struct {
    int a;
    int b;
} TestStruct;
const TestStruct test = {.a = 0, .b = 2};

inline void write_to_memory(int address, TestStruct value) {
  *(volatile int*)address = value.a;
}

int main() {
  write_to_memory(0x10000000, test);
  return 0;
}