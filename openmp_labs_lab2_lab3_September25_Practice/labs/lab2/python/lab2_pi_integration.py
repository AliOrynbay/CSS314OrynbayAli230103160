"""
Lab 2: Numerical Integration (Pi Approximation) & Parallel Reductions (Python)

Numba is not available in every environment, so this version demonstrates
the three OpenMP-equivalent synchronization patterns using the standard
library:
  A) Naive unsynchronized shared accumulator (threading)  -> race condition
  B) threading.Lock() critical section                    -> correct but slow
  C) multiprocessing reduction (true parallelism, no GIL)  -> fast and correct

Run:
    python3 lab2_pi_integration.py --n 2000000 --threads 4
"""
import argparse
import csv
import math
import os
import threading
import time
from concurrent.futures import ProcessPoolExecutor

DATA_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "data")
os.makedirs(DATA_DIR, exist_ok=True)


def f(x: float) -> float:
    return 4.0 / (1.0 + x * x)


def calc_pi_serial(n: int) -> float:
    step = 1.0 / n
    total = 0.0
    for i in range(n):
        x = (i + 0.5) * step
        total += f(x)
    return total * step


class SharedAccumulator:
    def __init__(self):
        self.value = 0.0


def _race_worker(acc: SharedAccumulator, start: int, end: int, step: float):
    # Unprotected read-modify-write -> classic OpenMP-critical-omitted race
    for i in range(start, end):
        x = (i + 0.5) * step
        acc.value += f(x)


def calc_pi_naive_race(n: int, num_threads: int) -> float:
    step = 1.0 / n
    acc = SharedAccumulator()
    chunk = n // num_threads
    threads = []
    for t in range(num_threads):
        start = t * chunk
        end = n if t == num_threads - 1 else start + chunk
        th = threading.Thread(target=_race_worker, args=(acc, start, end, step))
        threads.append(th)
        th.start()
    for th in threads:
        th.join()
    return acc.value * step


def _critical_worker(acc: SharedAccumulator, lock: threading.Lock, start: int, end: int, step: float):
    for i in range(start, end):
        x = (i + 0.5) * step
        term = f(x)
        with lock:  # emulates #pragma omp critical
            acc.value += term


def calc_pi_critical_section(n: int, num_threads: int) -> float:
    step = 1.0 / n
    acc = SharedAccumulator()
    lock = threading.Lock()
    chunk = n // num_threads
    threads = []
    for t in range(num_threads):
        start = t * chunk
        end = n if t == num_threads - 1 else start + chunk
        th = threading.Thread(target=_critical_worker, args=(acc, lock, start, end, step))
        threads.append(th)
        th.start()
    for th in threads:
        th.join()
    return acc.value * step


def _reduction_worker(args):
    start, end, step = args
    partial = 0.0
    for i in range(start, end):
        x = (i + 0.5) * step
        partial += f(x)
    return partial


def calc_pi_parallel_reduction(n: int, num_workers: int) -> float:
    """True parallelism via separate processes (no shared-memory GIL limit),
    each returning a private partial sum that is tree-combined -- the same
    shape as #pragma omp parallel for reduction(+:sum)."""
    step = 1.0 / n
    chunk = n // num_workers
    ranges = []
    for t in range(num_workers):
        start = t * chunk
        end = n if t == num_workers - 1 else start + chunk
        ranges.append((start, end, step))
    with ProcessPoolExecutor(max_workers=num_workers) as ex:
        partials = list(ex.map(_reduction_worker, ranges))
    return sum(partials) * step


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=2_000_000, help="number of integration steps")
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()
    n, p = args.n, args.threads

    print(f"N = {n:,} steps, P = {p}")

    t0 = time.perf_counter()
    pi_s = calc_pi_serial(n)
    t1 = time.perf_counter()
    print(f"Serial:            Pi = {pi_s:.10f} | Time = {t1 - t0:.4f}s | Error = {abs(pi_s - math.pi):.2e}")

    # Race: run several times to show non-reproducible, systematically LOW results
    race_errors = []
    for trial in range(3):
        t0 = time.perf_counter()
        pi_r = calc_pi_naive_race(n, p)
        t1 = time.perf_counter()
        err = abs(pi_r - math.pi)
        race_errors.append(err)
        print(f"Naive race (t{trial}):  Pi = {pi_r:.10f} | Time = {t1 - t0:.4f}s | Error = {err:.2e}")

    t0 = time.perf_counter()
    pi_c = calc_pi_critical_section(n, p)
    t1 = time.perf_counter()
    print(f"Critical section:  Pi = {pi_c:.10f} | Time = {t1 - t0:.4f}s | Error = {abs(pi_c - math.pi):.2e}")

    t0 = time.perf_counter()
    pi_red = calc_pi_parallel_reduction(n, p)
    t1 = time.perf_counter()
    print(f"Reduction (mp):    Pi = {pi_red:.10f} | Time = {t1 - t0:.4f}s | Error = {abs(pi_red - math.pi):.2e}")

    out = os.path.join(DATA_DIR, "lab2_variants.csv")
    with open(out, "w", newline="") as fcsv:
        w = csv.writer(fcsv)
        w.writerow(["variant", "n", "threads", "pi_estimate", "abs_error"])
        w.writerow(["serial", n, 1, pi_s, abs(pi_s - math.pi)])
        for i, e in enumerate(race_errors):
            w.writerow([f"naive_race_trial{i}", n, p, "", e])
        w.writerow(["critical_section", n, p, pi_c, abs(pi_c - math.pi)])
        w.writerow(["reduction", n, p, pi_red, abs(pi_red - math.pi)])
    print(f"Saved -> {out}")


if __name__ == "__main__":
    main()
