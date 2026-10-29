# CUDA Lab 02: Advanced Geometries & Stencils

**Student ID:** 230103160
**Allocated GPU Node:** Tesla T4 (Google Colab)
**CUDA Compute Capability:** 7.5
**Official Verification Token:** C66FBF4ED8BC8CB7998D

## Task 1: Warp Divergence Benchmarks

N = 2^20 float32 elements, 1,000 iterations per element, 256 threads/block,
kernel-only time (warm-up launch, then mean of 10 trials).

| Kernel | Avg time (ms) | Slowdown vs A |
|---|---|---|
| A (Uniform) | 25.367 | 1.00x |
| B (Full Divergence, interleaved) | 94.129 | 3.71x |
| C (Warp-Aligned) | 46.856 | 1.85x |

**Analysis:** Kernel B is 3.71x slower than the uniform baseline (A). In B,
adjacent threads in each warp take different branches, so the SIMT hardware
serializes both paths with half of the lanes masked off each time; per-warp
time is about the sum of Path 1 and Path 2 (~25 ms + ~68 ms = ~94 ms). In
Kernel C the branch condition is constant within each warp (warp_id = idx // 32),
so every warp executes only one path and there is no intra-warp serialization;
its time (46.9 ms) is about the average of the two paths. C is still slower
than A only because Path 2 (subtract-divide) is inherently more expensive than
Path 1 (multiply-add). B is ~2.0x slower than C, which isolates the cost of
warp divergence.

## Task 2: 1D Stencil
Output: `TASK 2 PASSED: MAX DELTA = 5.960464477539063e-08`

## Task 3: Grid-Stride Scaling
256 threads/block x 64 blocks = 16,384 threads for N = 16,777,216 elements.
Output: `All 16777216 elements match factor 4.25. TASK 3 PASSED`

## Task 4: Sobel-X
Output: `TASK 4 PASSED: MAX DELTA = 4.76837158203125e-07`
