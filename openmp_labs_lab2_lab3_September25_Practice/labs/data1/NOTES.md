# Real Measured Data — Notes & Provenance

Everything in this folder (`data_real/`) is **actual measured data reported
by the student**, from a C/C++ (g++, OpenMP) build on their own hardware —
not from the Python/Java reference implementations shipped elsewhere in
this package, and not modeled/synthetic like `data_modeled/`.

## Hardware (Section I material)

| Field | Value |
|---|---|
| CPU | AMD Ryzen 5 5600H |
| Physical cores | 6 |
| Logical threads | 12 |
| OS | Windows + MSYS2 |
| Compiler | g++ 16.2.0 |
| Cache line size | 64 bytes |

## Lab 2 — Numerical Integration / Reduction

- N = 13,160,000 integration steps
- See `lab2_real_measured.csv` for threads/time/speedup at P = 1, 2, 4, 8, 12
- Estimated parallel fraction (Amdahl's Law fit): **p = 0.9766**
- Checksum (sum of terms, for correctness cross-check): **80,605,186**
- Max stopping time observed across threads: **688** — confirm the unit
  (iterations? µs? a max-iteration cap from a different lab?) before this
  goes in the report; it wasn't specified.
- Note for the report: the manual's Task 2.3 asks for P ∈ {1,2,4,8,16}.
  You measured P ∈ {1,2,4,8,12}. 12 is this CPU's full logical thread
  count — 16 would oversubscribe by 4 threads. That's worth stating
  explicitly in your methodology section as a deliberate substitution,
  not silently swapped in.

## Lab 3 — Scheduling (partial)

- P = 12, static scheduling: 3 trials = 0.266 / 0.262 / 0.256 s → avg **0.2590 s**
- P = 12, static with chunk size 1000: partial series (only 3 of an
  unspecified number of trials given) = 0.261 / 0.257 / 0.253 s
- See `lab3_real_partial.csv`

## Still needed (not fabricated — genuinely missing)

The manual's full requirements that these measurements don't yet cover:

- **Lab 1**: oversubscription sweep P ∈ {1,2,4,8,16,32,64}
- **Lab 2**: Task 2.1 (naive race condition trials + error), Task 2.2
  (critical-section timing vs. baseline)
- **Lab 3**: full 4×4 matrix — P ∈ {2,4,8,16} × chunk ∈ {1,16,64,256} —
  for both static and dynamic, plus guided scheduling, plus the
  per-thread load-imbalance instrumentation (Task 3.4)
- **Lab 4**: false sharing (unpadded/padded/local-accumulator) across
  P ∈ {1,2,4,8,16}, plus `perf stat` cache-miss counts
- **Lab 5**: cutoff sweep K ∈ {1,10,100,1000,10000,50000,100000} on
  N=5,000,000, plus Work-Span calculation
- **Lab 6 (bonus)**: pipeline throughput/latency across filter workloads

When you get these, drop them into new CSVs in this folder following the
same column layout as the Python reference scripts in `lab*/python/` — I
can merge them into the report and regenerate the plots at any point.
