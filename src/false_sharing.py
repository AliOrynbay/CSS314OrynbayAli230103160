#!/usr/bin/env python3
"""Milestone 2: false sharing reproduction, remediation, Karp-Flatt analysis.

Three variants, P in {1,2,4,8}, K increments per worker:
  unpadded   : worker p increments slot p       (all slots share ONE 64-byte line)
  padded     : worker p increments slot p*8     (each counter on its own line)
  privatized : worker p counts in a local variable, one write at the end

Shared memory is a SharedMemory block (page-aligned), so slots 0..7 are
guaranteed to sit on exactly one 64-byte cache line. Workers synchronize on a
barrier and time only their own loop, so process-spawn cost is excluded.
T(P) = slowest worker's loop time. Median of --reps runs.

Output: results/false_sharing_karp_flatt.tsv
"""
import argparse, multiprocessing as mp, os, statistics, sys, time
from multiprocessing import shared_memory
import ctypes

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "results")
K_DEFAULT = 5_000_000
PROCS = [1, 2, 4, 8]
SHM_BYTES = 512  # 64 int64 slots


def worker_counter(shm_name, idx, iterations, barrier, times, slot):
    """Used for 'unpadded' (idx = p) and 'padded' (idx = p*8)."""
    shm = shared_memory.SharedMemory(name=shm_name)
    arr = shm.buf.cast("q")
    barrier.wait()
    t0 = time.perf_counter_ns()
    for _ in range(iterations):
        arr[idx] += 1
    times[slot] = time.perf_counter_ns() - t0
    arr.release()
    shm.close()


def worker_privatized(shm_name, idx, iterations, barrier, times, slot):
    shm = shared_memory.SharedMemory(name=shm_name)
    arr = shm.buf.cast("q")
    barrier.wait()
    t0 = time.perf_counter_ns()
    local = 0
    for _ in range(iterations):
        local += 1
    arr[idx] = local                      # single write at the end
    times[slot] = time.perf_counter_ns() - t0
    arr.release()
    shm.close()


def run_once(ctx, kind, P, K):
    shm = shared_memory.SharedMemory(create=True, size=SHM_BYTES)
    try:
        view = shm.buf.cast("q")
        for i in range(SHM_BYTES // 8):
            view[i] = 0
        view.release()

        barrier = ctx.Barrier(P)
        times = ctx.RawArray(ctypes.c_longlong, P)
        stride = 1 if kind == "unpadded" else 8
        target = worker_privatized if kind == "privatized" else worker_counter
        procs = [ctx.Process(target=target,
                             args=(shm.name, p * stride, K, barrier, times, p))
                 for p in range(P)]
        for pr in procs:
            pr.start()
        for pr in procs:
            pr.join()
        if any(pr.exitcode != 0 for pr in procs):
            raise RuntimeError("a worker failed")

        view = shm.buf.cast("q")
        total = sum(view[i] for i in range(SHM_BYTES // 8))
        view.release()
    finally:
        shm.close()
        shm.unlink()
    return max(times), total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--iters", type=int, default=K_DEFAULT)
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--out", default=os.path.join(RESULTS, "false_sharing_karp_flatt.tsv"))
    args = ap.parse_args()
    K = args.iters
    ctx = mp.get_context("spawn")

    data = {}   # (kind, P) -> dict
    for kind in ("unpadded", "padded", "privatized"):
        for P in PROCS:
            runs, ok = [], True
            for _ in range(args.reps):
                t, total = run_once(ctx, kind, P, K)
                runs.append(t)
                ok &= (total == P * K)
            data[(kind, P)] = {"med": statistics.median(runs), "runs": runs,
                               "ok": ok, "total": P * K}
            print(f"{kind:<11} P={P}: {data[(kind, P)]['med']/1e9:7.3f} s  "
                  f"counts_ok={ok}", flush=True)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as f:
        f.write("variant\tP\tK\tmedian_time_ns\trun_times_ns\tcounts_ok\texpected_total"
                "\tslowdown_vs_P1\tscaled_speedup\te_P\te_P_literal\n")
        for kind in ("unpadded", "padded", "privatized"):
            T1 = data[(kind, 1)]["med"]
            for P in PROCS:
                d = data[(kind, P)]
                slow = d["med"] / T1
                # S_P compares P*K increments done serially (P*T1) against T_P.
                S = P * T1 / d["med"]
                if P == 1:
                    e = e_lit = "NA"
                else:
                    e = f"{(1/S - 1/P) / (1 - 1/P):.4f}"          # = (T_P/T1 - 1)/(P-1)
                    S_lit = T1 / d["med"]                          # literal S = T1/T_P
                    e_lit = f"{(1/S_lit - 1/P) / (1 - 1/P):.4f}"
                f.write(f"{kind}\t{P}\t{K}\t{int(d['med'])}\t"
                        f"{','.join(str(int(x)) for x in d['runs'])}\t{d['ok']}\t"
                        f"{d['total']}\t{slow:.3f}\t{S:.3f}\t{e}\t{e_lit}\n")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
