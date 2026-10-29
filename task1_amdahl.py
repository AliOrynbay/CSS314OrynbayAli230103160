import time
import os
import multiprocessing as mp

TOTAL_ITEMS = 40_000_000

def cpu_intensive(chunk_size):
    acc = 0
    for i in range(chunk_size):
        acc += (i % 7) * (i % 11)
    return acc

def run_bench(workers, total_items=TOTAL_ITEMS):
    chunk = total_items // workers
    start = time.perf_counter()
    with mp.Pool(processes=workers) as pool:
        pool.map(cpu_intensive, [chunk] * workers)
    return time.perf_counter() - start

if __name__ == "__main__":
    cores = [1, 2, 4, 8, 12, 16]
    base = run_bench(1)

    print(f"OSReportedLogicalCores:{os.cpu_count()}")
    print(f"SingleProcessBaselineTimeT(1):{base:.4f}s\n")
    print("Cores|Time(s)|ObservedSpeedup|TheoreticalLinear")
    print("-" * 55)

    for c in cores:
        t = base if c == 1 else run_bench(c)
        speedup = base / t
        print(f"{c:5d}|{t:7.4f}|{speedup:16.2f}x|{c:18.2f}x")