import time
import multiprocessing as mp
import numpy as np

COMPUTE_N = 25_000_000
MEMORY_SIZE = 50_000_000

def compute_heavy(n):
    x = 1.0001
    for _ in range(n):
        x = (x * 1.000001) + 0.00001
    return x

def memory_heavy(size):
    arr = np.ones(size, dtype=np.float64)
    arr = arr * 2.0 + 1.0
    return arr[0]

def run_suite():
    print("===Compute-Bound Suite (Register Math)===")
    compute_times = {}

    for w in [1, 2, 4]:
        t0 = time.perf_counter()
        with mp.Pool(w) as p:
            p.map(compute_heavy, [COMPUTE_N] * w)
        elapsed = time.perf_counter() - t0
        compute_times[w] = elapsed
        print(f"Workers:{w}|ExecutionTime:{elapsed:.4f}s")

    print("\n===Memory-Bound Suite (DRAM Bandwidth Saturation)===")
    memory_times = {}

    for w in [1, 2, 4]:
        t0 = time.perf_counter()
        with mp.Pool(w) as p:
            p.map(memory_heavy, [MEMORY_SIZE] * w)
        elapsed = time.perf_counter() - t0
        memory_times[w] = elapsed
        print(f"Workers:{w}|ExecutionTime:{elapsed:.4f}s")

    print("\n===Scaling Summary===")
    for w in [1, 2, 4]:
        print(
            f"Workers:{w} | "
            f"ComputeScaling:{compute_times[w] / compute_times[1]:.2f}x | "
            f"MemoryDegradation:{memory_times[w] / memory_times[1]:.2f}x"
        )

if __name__ == "__main__":
    run_suite()