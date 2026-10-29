#!/usr/bin/env python3
"""Milestone 1, Task 1.1: stride latency cliff.

Reads a 2^24-element float64 array (~134 MB) with stride s and reports
median time per element read. Output: results/stride_results.tsv
"""
import os, statistics, sys, time
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "results")
STRIDES = [1, 2, 4, 8, 16, 32, 64, 128]
WARMUP, RUNS = 5, 5
SIZE = 16_777_216


def main():
    os.makedirs(RESULTS, exist_ok=True)
    data = np.ones(SIZE, dtype=np.float64)
    rows = []
    for s in STRIDES:
        view = data[::s]                 # strided view, no copy
        touched = len(view)
        for _ in range(WARMUP):
            view.sum()
        times = []
        for _ in range(RUNS):
            t0 = time.perf_counter_ns()
            total = view.sum()           # reduction over every s-th element
            t1 = time.perf_counter_ns()
            times.append(t1 - t0)
        assert total == touched          # all ones -> sum equals element count
        med = statistics.median(times)
        rows.append((s, touched, med, med / touched))
        print(f"stride {s:>3}: {med/1e6:9.3f} ms  {med/touched:8.3f} ns/element")

    with open(os.path.join(RESULTS, "stride_results.tsv"), "w") as f:
        f.write("stride\telements_touched\tmedian_time_ns\tlatency_ns_per_element\n")
        for s, n, t, lat in rows:
            f.write(f"{s}\t{n}\t{int(t)}\t{lat:.4f}\n")
    print("wrote results/stride_results.tsv")


if __name__ == "__main__":
    main()
