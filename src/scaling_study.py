#!/usr/bin/env python3
"""Milestone 3: strong/weak scaling of a 5-point Jacobi stencil + roofline.

Kernel: dst[i,j] = 0.25*(src[i-1,j]+src[i+1,j]+src[i,j-1]+src[i,j+1]) on interior
points, boundary fixed, `iters` sweeps with ping-pong buffers. Rows are split into
P contiguous chunks, one process each (spawn), sharing the grids via SharedMemory
and synchronizing on a barrier after every sweep. Uses Numba if installed
(pip install numba); without it a slower NumPy fallback is used.

Outputs: results/scaling_metrics.tsv, results/roofline_summary.json,
         results/scaling_summary.json (Amdahl fit details)
"""
import argparse, json, math, multiprocessing as mp, os, statistics, sys, time, ctypes
from multiprocessing import shared_memory
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "results")

FLOPS_PER_POINT = 4          # 3 adds + 1 multiply
BYTES_MIN = 16               # read src once (rows reused in cache) + write dst
BYTES_WA = 24                # + write-allocate read of dst line (regular x86 stores)

try:
    from numba import njit
    HAVE_NUMBA = True
except ImportError:
    HAVE_NUMBA = False

if HAVE_NUMBA:
    @njit(cache=False)
    def sweep(src, dst, n, r0, r1):
        for i in range(r0, r1):
            for j in range(1, n - 1):
                dst[i, j] = 0.25 * (src[i - 1, j] + src[i + 1, j]
                                    + src[i, j - 1] + src[i, j + 1])
else:
    def sweep(src, dst, n, r0, r1):
        dst[r0:r1, 1:n - 1] = 0.25 * (src[r0 - 1:r1 - 1, 1:n - 1] + src[r0 + 1:r1 + 1, 1:n - 1]
                                      + src[r0:r1, 0:n - 2] + src[r0:r1, 2:n])


def stencil_worker(nameA, nameB, n, r0, r1, iters, barrier, times, slot):
    shmA = shared_memory.SharedMemory(name=nameA)
    shmB = shared_memory.SharedMemory(name=nameB)
    A = np.ndarray((n, n), dtype=np.float64, buffer=shmA.buf)
    B = np.ndarray((n, n), dtype=np.float64, buffer=shmB.buf)
    wa, wb = np.zeros((4, 4)), np.zeros((4, 4))
    sweep(wa, wb, 4, 1, 3)                      # JIT warm-up, not timed
    src, dst = A, B
    barrier.wait()
    t0 = time.perf_counter_ns()
    for _ in range(iters):
        sweep(src, dst, n, r0, r1)
        barrier.wait()                          # neighbours' rows must be complete
        src, dst = dst, src
    times[slot] = time.perf_counter_ns() - t0
    del A, B, src, dst, wa, wb
    shmA.close()
    shmB.close()


def reference(u0, n, iters):
    src, dst = u0.copy(), u0.copy()
    for _ in range(iters):
        sweep(src, dst, n, 1, n - 1)
        src, dst = dst, src
    return src


def run_once(ctx, n, P, iters, u0, ref):
    nbytes = n * n * 8
    shmA = shared_memory.SharedMemory(create=True, size=nbytes)
    shmB = shared_memory.SharedMemory(create=True, size=nbytes)
    try:
        A = np.ndarray((n, n), dtype=np.float64, buffer=shmA.buf)
        B = np.ndarray((n, n), dtype=np.float64, buffer=shmB.buf)
        A[:] = u0
        B[:] = u0
        rows = n - 2
        base, rem = divmod(rows, P)
        bounds, r = [], 1
        for p in range(P):
            cnt = base + (1 if p < rem else 0)
            bounds.append((r, r + cnt))
            r += cnt
        barrier = ctx.Barrier(P)
        times = ctx.RawArray(ctypes.c_longlong, P)
        procs = [ctx.Process(target=stencil_worker,
                             args=(shmA.name, shmB.name, n, b[0], b[1], iters, barrier, times, p))
                 for p, b in enumerate(bounds)]
        for pr in procs:
            pr.start()
        for pr in procs:
            pr.join()
        if any(pr.exitcode != 0 for pr in procs):
            raise RuntimeError("a worker failed")
        final = A if iters % 2 == 0 else B
        checksum = float(np.sum(final))
        match = bool(np.array_equal(final, ref))
        del A, B, final
    finally:
        shmA.close(); shmB.close()
        shmA.unlink(); shmB.unlink()
    return max(times), checksum, match


# ----------------------------------------------------------------- bandwidth / roofline
def measure_bandwidth(threads_list, n, reps=5):
    """STREAM-'add' style: a = b + c, counting 3 streams x 8 B (24 B/element)."""
    from concurrent.futures import ThreadPoolExecutor
    a, b, c = np.zeros(n), np.ones(n), np.ones(n)
    out = {}
    for T in threads_list:
        edges = np.linspace(0, n, T + 1, dtype=np.int64)
        slices = [slice(int(edges[i]), int(edges[i + 1])) for i in range(T)]

        def work(sl):
            np.add(b[sl], c[sl], out=a[sl])

        ts = []
        with ThreadPoolExecutor(T) as ex:
            for r in range(reps + 1):           # first iteration = warm-up
                t0 = time.perf_counter_ns()
                list(ex.map(work, slices))
                ts.append(time.perf_counter_ns() - t0)
        out[T] = 24 * n / min(ts[1:])           # bytes/ns == GB/s
    return out


def detect_theoretical_bw():
    if sys.platform != "win32":
        return None
    import subprocess
    try:
        o = subprocess.check_output(
            ["powershell", "-NoProfile", "-Command",
             "Get-CimInstance Win32_PhysicalMemory | "
             "Select-Object ConfiguredClockSpeed,Speed | ConvertTo-Json"],
            text=True, stderr=subprocess.DEVNULL)
        d = json.loads(o)
        mods = d if isinstance(d, list) else [d]
        mts = max(int(m.get("ConfiguredClockSpeed") or m.get("Speed") or 0) for m in mods)
        ch = min(len(mods), 2)                   # assumes <= 2 channels (laptop APU)
        if mts <= 0:
            return None
        return {"gbs": mts * 8 * ch / 1000, "mts": mts, "modules": len(mods),
                "channels_assumed": ch, "source": "auto: Win32_PhysicalMemory (MT/s x 8 B x channels)"}
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--iters", type=int, default=100, help="stencil sweeps per run")
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--n", type=int, default=None, help="override salted N (testing only)")
    ap.add_argument("--maxp", type=int, default=None, help="override max worker count")
    ap.add_argument("--peak-bw-gbs", type=float, default=None,
                    help="theoretical DRAM bandwidth from your RAM spec (overrides auto-detect)")
    ap.add_argument("--flops-per-cycle", type=float, default=16.0,
                    help="FP64 FLOPs/cycle/core (16 = AVX2, 2 FMA pipes, Zen 3)")
    ap.add_argument("--bw-n", type=int, default=1 << 24, help="STREAM array length")
    ap.add_argument("--results-dir", default=RESULTS)
    args = ap.parse_args()
    R = args.results_dir
    os.makedirs(R, exist_ok=True)

    params = json.load(open(os.path.join(R, "salted_params.json")))
    host = json.load(open(os.path.join(R, "host_profile.json")))
    N, seed = args.n or params["N"], params["seed"]
    phys = host.get("physical_cores") or os.cpu_count()
    logi = host.get("logical_threads") or os.cpu_count()
    maxp = args.maxp or phys
    plist = sorted({p for p in (1, 2, 4, 8, 16) if p <= maxp} | {maxp})
    iters, reps = args.iters, args.reps
    ctx = mp.get_context("spawn")
    if not HAVE_NUMBA:
        print("[WARN] Numba not found: NumPy fallback (temporaries inflate memory traffic).",
              file=sys.stderr)
    print(f"N={N} iters={iters} reps={reps} P={plist} numba={HAVE_NUMBA}", flush=True)

    def make_u0(n):
        return np.random.default_rng(seed).random((n, n))

    rows = []

    # ---------------- strong scaling: fixed N
    u0 = make_u0(N)
    ref = reference(u0, N, iters)
    ref_sum = float(np.sum(ref))
    strong = {}
    for P in plist:
        runs, ok, cks = [], True, None
        for _ in range(reps):
            t, cks, match = run_once(ctx, N, P, iters, u0, ref)
            runs.append(t); ok &= match
        strong[P] = {"med": statistics.median(runs), "runs": runs, "ok": ok, "sum": cks}
        print(f"strong P={P}: {strong[P]['med']/1e9:7.3f} s  checksum_match={ok}", flush=True)
    del u0, ref

    T1 = strong[1]["med"]
    xs, ys = [], []
    for P in plist:
        d = strong[P]
        S = T1 / d["med"]
        E = S / P * 100
        if P > 1:
            sP = (1 / S - 1 / P) / (1 - 1 / P)
            xs.append(1 - 1 / P); ys.append(1 / S - 1 / P)
        else:
            sP = None
        d.update(S=S, E=E, sP=sP, gflops=FLOPS_PER_POINT * (N - 2) ** 2 * iters / d["med"])
    s_fit = sum(x * y for x, y in zip(xs, ys)) / sum(x * x for x in xs) if xs else None
    s_max = (1 / s_fit) if (s_fit and s_fit > 0) else None

    # ---------------- weak scaling: N_P = ceil(N*sqrt(P))
    W1 = (N - 2) ** 2
    weak = {}
    for P in plist:
        nP = math.ceil(N * math.sqrt(P))
        u0 = make_u0(nP)
        ref = reference(u0, nP, iters)
        refsum = float(np.sum(ref))
        runs, ok, cks = [], True, None
        for _ in range(reps):
            t, cks, match = run_once(ctx, nP, P, iters, u0, ref)
            runs.append(t); ok &= match
        weak[P] = {"n": nP, "med": statistics.median(runs), "runs": runs, "ok": ok,
                   "sum": cks, "ref": refsum, "work": (nP - 2) ** 2}
        print(f"weak   P={P}: n={nP} {weak[P]['med']/1e9:7.3f} s  checksum_match={ok}", flush=True)
        del u0, ref
    Tw1 = weak[1]["med"]
    for P in plist:
        d = weak[P]
        ratio = d["work"] / W1
        d["S_handout"] = P * W1 * Tw1 / (d["work"] * d["med"])
        d["S_gust"] = ratio * Tw1 / d["med"]
        d["eff"] = Tw1 / d["med"] * 100
        d["sg"] = (P - d["S_gust"]) / (P - 1) if P > 1 else None
        d["gflops"] = FLOPS_PER_POINT * d["work"] * iters / d["med"]

    # ---------------- write TSV
    f4 = lambda v: "NA" if v is None else f"{v:.4f}"
    with open(os.path.join(R, "scaling_metrics.tsv"), "w") as f:
        f.write("study\tP\tn\titers\twork_points\tmedian_time_ns\trun_times_ns\tspeedup"
                "\tefficiency_pct\tamdahl_serial_frac\tscaled_speedup_handout"
                "\tscaled_speedup_gustafson\tweak_efficiency_pct\tgustafson_serial_frac"
                "\tgflops\tchecksum\tref_checksum\tchecksum_match\n")
        for P in plist:
            d = strong[P]
            f.write(f"strong\t{P}\t{N}\t{iters}\t{(N-2)**2}\t{int(d['med'])}\t"
                    f"{','.join(str(int(x)) for x in d['runs'])}\t{d['S']:.4f}\t{d['E']:.2f}\t"
                    f"{f4(d['sP'])}\tNA\tNA\tNA\tNA\t{d['gflops']:.4f}\t"
                    f"{d['sum']!r}\t{ref_sum!r}\t{d['ok']}\n")
        for P in plist:
            d = weak[P]
            f.write(f"weak\t{P}\t{d['n']}\t{iters}\t{d['work']}\t{int(d['med'])}\t"
                    f"{','.join(str(int(x)) for x in d['runs'])}\tNA\tNA\tNA\t"
                    f"{d['S_handout']:.4f}\t{d['S_gust']:.4f}\t{d['eff']:.2f}\t{f4(d['sg'])}\t"
                    f"{d['gflops']:.4f}\t{d['sum']!r}\t{d['ref']!r}\t{d['ok']}\n")

    with open(os.path.join(R, "scaling_summary.json"), "w") as f:
        json.dump({"N": N, "iters": iters, "P_values": plist,
                   "amdahl_serial_fraction_fit": s_fit,
                   "amdahl_max_speedup": s_max if s_max else "unbounded (fit s <= 0)",
                   "per_P_serial_fraction": {P: strong[P]["sP"] for P in plist},
                   "predicted_speedup": {P: (1 / (s_fit + (1 - s_fit) / P) if s_fit is not None else None)
                                         for P in plist},
                   "measured_speedup": {P: strong[P]["S"] for P in plist}}, f, indent=2)

    # ---------------- roofline
    thr = sorted({1, phys, logi})
    print(f"measuring memory bandwidth, threads={thr} ...", flush=True)
    bw = measure_bandwidth(thr, args.bw_n)
    bw_meas = max(bw.values())
    theo = detect_theoretical_bw()
    if args.peak_bw_gbs:
        theo = {"gbs": args.peak_bw_gbs, "source": "command line --peak-bw-gbs"}
    base_ghz = (host.get("base_frequency_mhz") or 0) / 1000
    peak_gflops = phys * base_ghz * args.flops_per_cycle if base_ghz else None

    ai = {"minimum_16B_per_point": FLOPS_PER_POINT / BYTES_MIN,
          "with_write_allocate_24B_per_point": FLOPS_PER_POINT / BYTES_WA}
    ai_main = ai["with_write_allocate_24B_per_point"]
    ridge_meas = peak_gflops / bw_meas if peak_gflops else None
    ridge_theo = peak_gflops / theo["gbs"] if (peak_gflops and theo) else None
    attain = lambda gbs: min(peak_gflops, ai_main * gbs) if peak_gflops else ai_main * gbs
    regime = ("memory_bandwidth_bound" if (ridge_meas is None or ai_main < ridge_meas)
              else "compute_bound")
    cache = host.get("cache", {})
    ws = 2 * N * N * 8
    l3 = cache.get("l3_bytes")
    Pmax = plist[-1]
    summary = {
        "kernel": "5-point Jacobi stencil, float64, ping-pong buffers",
        "flops_per_point": FLOPS_PER_POINT,
        "dram_bytes_per_point": {"minimum": BYTES_MIN, "with_write_allocate": BYTES_WA,
                                 "assumption": "src rows reused from L1/L2 (3 rows x N x 8 B fits "
                                               "in cache); dst is written once"},
        "arithmetic_intensity_flop_per_byte": ai,
        "working_set": {"N": N, "two_grids_bytes": ws, "l1d_bytes": cache.get("l1d_bytes"),
                        "l2_bytes_per_core": cache.get("l2_bytes"), "l3_bytes": l3,
                        "exceeds_l3": (ws > l3) if l3 else None,
                        "row_triplet_bytes": 3 * N * 8},
        "peak_compute_gflops": peak_gflops,
        "peak_compute_assumptions": {"physical_cores": phys, "base_ghz": base_ghz,
                                     "flops_per_cycle_per_core": args.flops_per_cycle},
        "memory_bandwidth_gbs": {"measured_by_threads_STREAM_add": {str(k): v for k, v in bw.items()},
                                 "measured_sustained": bw_meas, "theoretical": theo},
        "ridge_point_flop_per_byte": {"using_measured_bw": ridge_meas, "using_theoretical_bw": ridge_theo},
        "attainable_gflops_at_stencil_AI": {
            "using_measured_bw": attain(bw_meas),
            "using_theoretical_bw": attain(theo["gbs"]) if theo else None},
        "achieved": {"gflops_P1": strong[1]["gflops"], f"gflops_P{Pmax}": strong[Pmax]["gflops"],
                     f"effective_dram_gbs_P{Pmax}_at_24B": strong[Pmax]["gflops"] / FLOPS_PER_POINT * BYTES_WA},
        "regime": regime,
        "conclusion": (f"AI = {ai_main:.3f} FLOP/B (0.250 with no write-allocate) is far below the "
                       f"ridge point; the kernel is memory-bandwidth bound."
                       if regime == "memory_bandwidth_bound" else "AI exceeds ridge: compute bound."),
    }
    with open(os.path.join(R, "roofline_summary.json"), "w") as f:
        json.dump(summary, f, indent=2)

    print("\n=== summary ===")
    print(f"Amdahl serial fraction s = {s_fit}   S_max = {s_max}")
    print(f"AI = {ai_main:.3f} FLOP/B   measured BW = {bw_meas:.1f} GB/s   "
          f"peak = {peak_gflops} GFLOP/s   regime = {regime}")
    print("wrote scaling_metrics.tsv, scaling_summary.json, roofline_summary.json")


if __name__ == "__main__":
    main()
