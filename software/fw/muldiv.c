/* Soft multiply/divide libcalls for rv32i codegen (__mulsi3 family). The PATH riscv clang
 * toolchain ships no rv32 runtime archive (its libgcc is rv64-only), so the libcalls the
 * compiler emits are provided here instead of -lgcc. Always linked: zmmul builds multiply in
 * hardware but still libcall the divide family (Zmmul has no divide). Unused ones cost a few
 * hundred bytes of the 16 KB RAM. Shift-add / restoring long division — pure rv32i, so the
 * compiler cannot turn these back into the libcalls they implement. */

int __mulsi3(int a, int b) {
    unsigned ua = (unsigned)a, ub = (unsigned)b, r = 0;
    while (ub) {
        if (ub & 1) r += ua;
        ua <<= 1;
        ub >>= 1;
    }
    return (int)r;
}

unsigned __udivsi3(unsigned n, unsigned d) {
    unsigned q = 0, r = 0;
    for (int i = 31; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1);
        if (r >= d) {
            r -= d;
            q |= 1u << i;
        }
    }
    return q;
}

unsigned __umodsi3(unsigned n, unsigned d) {
    unsigned r = 0;
    for (int i = 31; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1);
        if (r >= d)
            r -= d;
    }
    return r;
}

int __divsi3(int a, int b) {
    unsigned ua = a < 0 ? 0u - (unsigned)a : (unsigned)a;
    unsigned ub = b < 0 ? 0u - (unsigned)b : (unsigned)b;
    unsigned q = __udivsi3(ua, ub);
    return (a < 0) != (b < 0) ? -(int)q : (int)q;
}

int __modsi3(int a, int b) {
    unsigned ua = a < 0 ? 0u - (unsigned)a : (unsigned)a;
    unsigned ub = b < 0 ? 0u - (unsigned)b : (unsigned)b;
    unsigned r = __umodsi3(ua, ub);
    return a < 0 ? -(int)r : (int)r;
}
