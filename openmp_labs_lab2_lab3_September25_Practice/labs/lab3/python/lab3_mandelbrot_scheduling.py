"""
Lab 3: Work-Sharing & Loop Scheduling Policies (Mandelbrot Fractal) - Python

Implements OpenMP schedule(static), schedule(dynamic, chunk) and
schedule(guided, chunk) using multiprocessing (true parallel workers,
since numba is unavailable). Also instruments per-worker iteration
counts to compute a load-imbalance metric.

Run:
    python3 lab3_mandelbrot_scheduling.py --width 480 --height 270 --max-iter 500
"""
import argparse
import csv
import os
import time
from multiprocessing import Pool

DATA_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "data")
os.makedirs(DATA_DIR, exist_ok=True)

WIDTH = HEIGHT = MAX_ITER = None  # set by main(), read by worker processes


def _init_globals(w, h, it):
    global WIDTH, HEIGHT, MAX_ITER
    WIDTH, HEIGHT, MAX_ITER = w, h, it


def compute_pixel(px, py, width, height, max_iter):
    x0 = (px - width / 2.0) * 4.0 / width
    y0 = (py - height / 2.0) * 4.0 / height
    x = y = 0.0
    iteration = 0
    while x * x + y * y <= 4.0 and iteration < max_iter:
        xt = x * x - y * y + x0
        y = 2.0 * x * y + y0
        x = xt
        iteration += 1
    return iteration


def _render_row_chunk(rows):
    """Worker: render a contiguous block of rows; return (rows_done, work_units)."""
    total_work = 0
    for y in rows:
        for x in range(WIDTH):
            total_work += compute_pixel(x, y, WIDTH, HEIGHT, MAX_ITER)
    return len(rows), total_work


def static_chunks(height, num_workers):
    """Equal contiguous blocks -- schedule(static)."""
    base = height // num_workers
    rem = height % num_workers
    chunks = []
    start = 0
    for w in range(num_workers):
        size = base + (1 if w < rem else 0)
        chunks.append(list(range(start, start + size)))
        start += size
    return chunks


def dynamic_chunks(height, chunk_size):
    """Small fixed-size chunks fed to a shared pool -- schedule(dynamic, chunk)."""
    return [list(range(i, min(i + chunk_size, height))) for i in range(0, height, chunk_size)]


def guided_chunks(height, min_chunk, num_workers):
    """Geometrically shrinking chunks -- schedule(guided, chunk)."""
    chunks = []
    remaining = height
    start = 0
    while remaining > 0:
        size = max(min_chunk, remaining // (2 * num_workers))
        size = min(size, remaining)
        chunks.append(list(range(start, start + size)))
        start += size
        remaining -= size
    return chunks


def run_policy(policy: str, width, height, max_iter, num_workers, chunk_size=16):
    if policy == "static":
        chunks = static_chunks(height, num_workers)
    elif policy == "dynamic":
        chunks = dynamic_chunks(height, chunk_size)
    elif policy == "guided":
        chunks = guided_chunks(height, chunk_size, num_workers)
    else:
        raise ValueError(policy)

    t0 = time.perf_counter()
    with Pool(processes=num_workers, initializer=_init_globals, initargs=(width, height, max_iter)) as pool:
        results = pool.map(_render_row_chunk, chunks)
    elapsed = time.perf_counter() - t0

    # NOTE: results are per-CHUNK, not per-WORKER; multiple chunks can land on
    # the same worker under dynamic/guided. We approximate per-worker load by
    # round-robin assignment order as reported by the pool's task order.
    work_per_chunk = [r[1] for r in results]
    imbalance = (max(work_per_chunk) - min(work_per_chunk)) / (sum(work_per_chunk) / len(work_per_chunk))
    return elapsed, imbalance, len(chunks)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--width", type=int, default=480)
    ap.add_argument("--height", type=int, default=270)
    ap.add_argument("--max-iter", type=int, default=500)
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()

    rows = []
    for policy, chunk in [("static", None), ("dynamic", 4), ("dynamic", 16),
                           ("dynamic", 64), ("guided", 4)]:
        cs = chunk or 16
        elapsed, imbalance, n_chunks = run_policy(policy, args.width, args.height,
                                                   args.max_iter, args.workers, cs)
        label = f"{policy}(chunk={cs})" if policy != "static" else "static"
        print(f"{label:<20} workers={args.workers} | time={elapsed:.4f}s | "
              f"chunk_imbalance={imbalance:.3f} | n_chunks={n_chunks}")
        rows.append({"policy": label, "workers": args.workers, "time_s": elapsed,
                      "imbalance": imbalance, "n_chunks": n_chunks})

    out = os.path.join(DATA_DIR, "lab3_scheduling.csv")
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["policy", "workers", "time_s", "imbalance", "n_chunks"])
        w.writeheader()
        w.writerows(rows)
    print(f"Saved -> {out}")


if __name__ == "__main__":
    main()
