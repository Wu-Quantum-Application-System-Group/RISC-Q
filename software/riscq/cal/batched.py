"""The one grid kernel (specs/universal-cal/01 §6.1): `k_batched` walks a fixed-period grid of
`npts × shots` readouts and calls the generated sequence header (`seq_init / seq_point /
seq_shot / seq_meas / meas_tail`, riscq.cal.sequence) for every pulse. Two on-core affine axes
(`a`, `b`, from the runtime pairs x0/dx0, x1/dx1), four runtime params (`r0..r3`), and the
compile-time `mode` / `herald` folds the 14 retired per-experiment kernels carried."""

from riscq.lang import Array, Group, kernel

COUNTS = 0
RAW = 1
IQSUM = 2
NONE = 3        # a core that only carries lines: no readout, no result


@kernel
def k_batched(grp: Group, out: Array, npts: int, shots: int, period: int, mode: int, herald: int,
              hoff: int, sh: int, x0: int, dx0: int, x1: int, dx1: int, r0: int, r1: int, r2: int,
              r3: int):
    """One batched sweep: `npts` points × `shots` shots on a `period` grid whose idle head is the
    relax reset (spec 08 §2.2). COUNTS accumulates the hardware res bit per point (heralded:
    interleaved (count, kept) pairs); RAW writes per-shot IQ through a cursor; IQSUM sums the
    per-point IQ integrals (>> sh); NONE plays only (a non-reading core stays on the grid).

    The grid starts from the put network's barrier release (specs/cross-core/02 §8.1): `grp` is
    the experiment's cores, and every member reads the SAME released time, so the cores' grids are
    aligned exactly — a per-core `now() + period` carried each core's own preamble time, measured
    at up to 23 batches between the two lines of a CZ (03-plan §3.3). A one-core experiment is a
    one-member group.

    The runtime params are copied into locals ONCE: a volatile load sits in the shot path otherwise,
    and after a herald read the first drive is posted with exactly LEAD to spare (base.herald_offset),
    so four extra RAM loads push its fire late — and a late fire plays with the PREVIOUS pulse's
    parameters (docs/soc/SOC_TIPS.md §5)."""
    seq_init()  # noqa: F821
    t_ro = barrier(grp) + period  # noqa: F821
    a = x0
    b = x1
    p0 = r0
    p1 = r1
    p2 = r2
    p3 = r3
    if mode == RAW:
        k = 0
    for i in range(npts):
        seq_point(a, b, p0, p1, p2, p3)  # noqa: F821
        for s in range(shots):
            if herald == 1:
                seq_meas(t_ro - hoff, p0, p1, p2, p3)  # noqa: F821
                wait_until(t_ro - hoff + meas_tail())  # noqa: F821
                h = read_res()  # noqa: F821
            else:
                h = 0
            if h == 0:
                seq_shot(t_ro, a, b, p0, p1, p2, p3)  # noqa: F821
                seq_meas(t_ro, p0, p1, p2, p3)  # noqa: F821
                wait_until(t_ro + meas_tail())  # noqa: F821
                if mode == COUNTS:
                    if herald == 1:
                        out[2 * i] = out[2 * i] + read_res()  # noqa: F821
                        out[2 * i + 1] = out[2 * i + 1] + 1  # noqa: F821
                    else:
                        out[i] += read_res()  # noqa: F821
                elif mode == RAW:
                    read_res()  # noqa: F821
                    out[k] = read_real()  # noqa: F821
                    out[k + 1] = read_imag()  # noqa: F821
                    k = k + 2
                elif mode == IQSUM:
                    read_res()  # noqa: F821
                    out[2 * i] += read_real() >> sh  # noqa: F821
                    out[2 * i + 1] += read_imag() >> sh  # noqa: F821
            t_ro = t_ro + period  # noqa: F821
        a = a + dx0
        b = b + dx1
