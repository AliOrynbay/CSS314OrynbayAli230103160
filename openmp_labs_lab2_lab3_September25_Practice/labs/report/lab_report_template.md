# Shared-Memory Concurrency & OpenMP Paradigms — Lab Report
## Lab 2 (Numerical Integration & Reductions) + Lab 3 (Loop Scheduling)

**Student:** _[your name]_
**Language track:** C++ / g++ / OpenMP (your actual build)

---

## Section I: System & Hardware Specifications

| Field | Value |
|---|---|
| CPU model | AMD Ryzen 5 5600H |
| Physical cores | 6 |
| Logical threads | 12 |
| L1 cache | 384 KB total (32 KB data + 32 KB instruction per core × 6 cores) |
| L2 cache | 3 MB total (512 KB per core × 6 cores) |
| L3 cache | 16 MB (shared) |
| OS | Windows + MSYS2 |
| Compiler | g++ 16.2.0 |
| Cache line size | 64 bytes |

Source: AMD/WikiChip published specs for the 5600H ("Cezanne-H", Zen 3).

## Section II: Experimental Methodology

- Timers used: `time.perf_counter()` (Python), `System.nanoTime()` /
  `System.currentTimeMillis()` (Java).
- Warm-up: none needed for the standard-library implementations here
  (no JIT warm-up required, unlike Numba's `@njit`); if you switch to
  Numba, add one untimed call before benchmarking to trigger compilation.
- Trials: 5 per configuration for oversubscription/scheduling sweeps, 3
  for the race-condition demonstration (to show non-reproducibility).

## Section III: Empirical Results & Visualizations

### Lab 2 — Numerical Integration & Reduction (REAL MEASURED DATA)

C++/g++/OpenMP build, N = 13,160,000, AMD Ryzen 5 5600H:

| Threads | Time (s) | Speedup |
|---|---|---|
| 1  | 2.2465 | 1.000 |
| 2  | 1.1495 | 1.954 |
| 4  | 0.5780 | 3.887 |
| 8  | 0.3630 | 6.189 |
| 12 | 0.2595 | 8.657 |

Amdahl's Law fit: estimated parallel fraction **p = 0.9766**. See
`plots/lab2_real_measured.png` — measured speedup tracks the Amdahl curve
closely through P=8, then falls slightly below it at P=12 (full logical
thread count reached; diminishing returns as expected).

Checksum = 80,605,186 (correctness cross-check). Still needed for full
Task 2 coverage: naive-race trials (Task 2.1) and critical-section timing
(Task 2.2) — not yet measured with this build.

### Lab 3 — Scheduling (PARTIAL real data)

P=12, static scheduling: 3 trials = 0.266 / 0.262 / 0.256 s → avg 0.2590 s.
P=12, static chunk=1000: partial series 0.261 / 0.257 / 0.253 s.
See `data_real/lab3_real_partial.csv`. **Missing**: the full P × chunk
matrix the manual asks for (Task 3.2), dynamic/guided policies, and the
load-imbalance instrumentation (Task 3.4) — none of this is available yet
and needs to be measured before the section is complete.

## Section IV: Analytical & Discussion Responses

> These are meant to test **your own understanding** — the manual's honor
> code requires original individual analysis. Use the concepts below as a
> checklist of what each answer needs to cover, and write the explanations
> in your own words based on what you observed in your own runs.

**Lab 2**
- Q2.1 — non-atomic read-modify-write at the assembly level (LOAD/ADD/STORE) and lost updates
- Q2.2 — why a binary reduction tree is O(log P) vs. a centralized critical section's O(P)
- Q2.3 — Amdahl's Law: 5% serial → max speedup = 1/0.05 = 20×; explain why measured speedup falls short (memory bandwidth, sync overhead, non-parallel setup)
- Q2.4 — hardware atomics: bus snooping, MESI locking, LL/SC

**Lab 3**
- Q3.1 — why chunk=1 dynamic scheduling is slow despite perfect balance (queue/lock contention on every claim)
- Q3.2 — which rows straggle under static scheduling on the Mandelbrot set (rows through the central/boundary region, highest iteration counts)
- Q3.3 — guided scheduling's geometric chunk shrink formula
- Q3.4 — heuristic: static for uniform cost, dynamic for unpredictable cost, guided as a middle ground

## Section V: Conclusions & Insights

_[Your summary of what you learned about shared-memory concurrency,
memory hierarchy bottlenecks, and scheduling trade-offs, grounded in your
own benchmark numbers.]_
