import threading
import time

TOTAL_OPS = 2_000_000
NUM_THREADS = 4

class UnsafeCounter:
    def __init__(self):
        self.val = 0

    def inc(self):
        self.val += 1

class LockedCounter:
    def __init__(self):
        self.val = 0
        self.lock = threading.Lock()

    def inc(self):
        with self.lock:
            self.val += 1

class ThreadLocalAccumulator:
    def __init__(self):
        self.partial = [0] * NUM_THREADS

    def bench(self):
        ops_per_thread = TOTAL_OPS // NUM_THREADS

        def work(thread_id):
            local_sum = 0
            for _ in range(ops_per_thread):
                local_sum += 1
            self.partial[thread_id] = local_sum

        threads = [
            threading.Thread(target=work, args=(i,))
            for i in range(NUM_THREADS)
        ]

        start = time.perf_counter()

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        result = sum(self.partial)
        elapsed = time.perf_counter() - start
        return result, elapsed

def bench(counter_type):
    c = counter_type()
    ops_per_thread = TOTAL_OPS // NUM_THREADS

    def work():
        for _ in range(ops_per_thread):
            c.inc()

    threads = [threading.Thread(target=work) for _ in range(NUM_THREADS)]

    start = time.perf_counter()

    for t in threads:
        t.start()
    for t in threads:
        t.join()

    return c.val, time.perf_counter() - start

if __name__ == "__main__":
    val_unsafe, t_unsafe = bench(UnsafeCounter)
    val_locked, t_locked = bench(LockedCounter)

    local = ThreadLocalAccumulator()
    val_local, t_local = local.bench()

    print(f"Unsafe:Value={val_unsafe:,}/{TOTAL_OPS:,}|Time:{t_unsafe:.4f}s")
    print(f"Locked:Value={val_locked:,}/{TOTAL_OPS:,}|Time:{t_locked:.4f}s")
    print(f"LocklessThreadLocal:Value={val_local:,}/{TOTAL_OPS:,}|Time:{t_local:.4f}s")
    print(f"LocklessSpeedupOverLocked:{t_locked / t_local:.2f}x")
    print(f"ContentionCostMultiplier:{t_locked / t_unsafe:.2f}x")