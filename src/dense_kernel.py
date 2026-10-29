#!/usr/bin/env python3
"""Milestone 1, Task 1.2: loop interchange i-j-k vs i-k-j on flat 1D arrays.

Uses Numba (pip install numba) if available. Without Numba it falls back to
pure Python, which is very slow: pass --m 96 or similar in that case.
Output: results/loop_interchange.json
"""
import argparse, hashlib, json, os, statistics, sys, time
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from salt import get_student_params

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "results")

try:
    from numba import njit
    HAVE_NUMBA = True
except ImportError:
    HAVE_NUMBA = False
    def njit(*a, **k):
        return (lambda f: f) if not (a and callable(a[0])) else a[0]


@njit(cache=False)
def matmul_ijk(A, B, C, M):
    for i in range(M):
        for j in range(M):
            acc = 0.0
            for k in range(M):
                acc += A[i * M + k] * B[k * M + j]   # B strides by M
            C[i * M + j] = acc


@njit(cache=False)
def matmul_ikj(A, B, C, M):
    for i in range(M):
        for k in range(M):
            a = A[i * M + k]                          # hoisted scalar
            for j in range(M):
                C[i * M + j] += a * B[k * M + j]      # stride-1 on B and C


def digest(C):
    r = np.round(C, 6)
    return hashlib.sha256(r.tobytes()).hexdigest()


def timed(fn, A, B, M, runs):
    times, C = [], None
    for _ in range(runs):
        C = np.zeros(M * M, dtype=np.float64)
        t0 = time.perf_counter_ns()
        fn(A, B, C, M)
        times.append(time.perf_counter_ns() - t0)
    return times, C


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--m", type=int, default=None, help="override sub-block size M")
    ap.add_argument("--student-id", default=None)
    args = ap.parse_args()

    p = os.path.join(RESULTS, "salted_params.json")
    if os.path.exists(p):
        params = json.load(open(p))
    elif args.student_id:
        params = get_student_params(args.student_id)
    else:
        sys.exit("Run profile_env.py first or pass --student-id")

    N, seed = params["N"], params["seed"]
    M = args.m or N // 2
    rng = np.random.default_rng(seed)
    A_full = rng.random((N, N))
    B_full = rng.random((N, N))
    A = np.ascontiguousarray(A_full[:M, :M]).ravel()     # flat M x M sub-block
    B = np.ascontiguousarray(B_full[:M, :M]).ravel()

    # JIT warm-up on a tiny problem so compile time is not measured
    w = np.zeros(16, dtype=np.float64)
    matmul_ijk(A[:16].copy(), B[:16].copy(), w, 4)
    matmul_ikj(A[:16].copy(), B[:16].copy(), w, 4)

    print(f"N={N} M={M} numba={HAVE_NUMBA}")
    t_naive, C1 = timed(matmul_ijk, A, B, M, 3)
    t_swap, C2 = timed(matmul_ikj, A, B, M, 3)
    med_n, med_s = statistics.median(t_naive), statistics.median(t_swap)
    max_diff = float(np.max(np.abs(C1 - C2)))

    # reference check against NumPy BLAS (tolerance looser: different summation order)
    ref = (A.reshape(M, M) @ B.reshape(M, M)).ravel()
    ref_diff = float(np.max(np.abs(C1 - ref)))

    out = {
        "N": N, "M": M, "seed": seed, "numba": HAVE_NUMBA,
        "runs_ns_ijk": t_naive, "runs_ns_ikj": t_swap,
        "median_ns_ijk": med_n, "median_ns_ikj": med_s,
        "speedup_ijk_over_ikj": med_n / med_s,
        "max_abs_diff_ijk_vs_ikj": max_diff,
        "max_abs_diff_vs_numpy": ref_diff,
        "digest_ijk": digest(C1), "digest_ikj": digest(C2),
    }
    with open(os.path.join(RESULTS, "loop_interchange.json"), "w") as f:
        json.dump(out, f, indent=2)
    print(f"i-j-k: {med_n/1e9:.3f} s   i-k-j: {med_s/1e9:.3f} s   "
          f"speedup S = {out['speedup_ijk_over_ikj']:.2f}x")
    print(f"max |ijk - ikj| = {max_diff:.3e}  (must be <= 1e-12)")


if __name__ == "__main__":
    main()
