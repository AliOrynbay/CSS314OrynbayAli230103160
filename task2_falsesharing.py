import threading
import time
import statistics

ITERATIONS = 5_000_000
TRIALS = 3

def worker_adjacent(shared_list, index):
    for _ in range(ITERATIONS):
        shared_list[index] += 1

def worker_padded(shared_list, index):
    padded_idx = index * 16
    for _ in range(ITERATIONS):
        shared_list[padded_idx] += 1

def run_test(target_fn, size):
    arr = [0] * size
    threads = [
        threading.Thread(target=target_fn, args=(arr, i))
        for i in range(4)
    ]

    start = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    return time.perf_counter() - start

if __name__ == "__main__":
    adjacent = []
    padded = []

    for trial in range(1, TRIALS + 1):
        t_adjacent = run_test(worker_adjacent, size=4)
        t_padded = run_test(worker_padded, size=64)

        adjacent.append(t_adjacent)
        padded.append(t_padded)

        print(
            f"Trial {trial}: "
            f"Adjacent={t_adjacent:.4f}s | "
            f"Padded={t_padded:.4f}s | "
            f"Slowdown={t_adjacent / t_padded:.2f}x"
        )

    med_adjacent = statistics.median(adjacent)
    med_padded = statistics.median(padded)

    print("\nMedian results:")
    print(f"AdjacentIndices(FalseSharing):{med_adjacent:.4f}s")
    print(f"PaddedIndices(Cache-Aligned):{med_padded:.4f}s")
    print(f"SlowdownFactor:{med_adjacent / med_padded:.2f}x")