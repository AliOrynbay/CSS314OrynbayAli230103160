import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Task7 {

    private static final long STUDENT_ID = 230103160;
    private static final int N = 30_000_000;
    private static final double STALL_EFFICIENCY = 0.50;

    private static final int CORES_MAX = Runtime.getRuntime().availableProcessors();

    enum Kernel { COPY, COMPUTE }

    record Row(int cores, double ms, double speedup, double efficiency) {}

    public static void main(String[] args) throws Exception {
        System.out.println("Student ID: " + STUDENT_ID);
        System.out.println("OS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Logical cores (max): " + CORES_MAX);
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("Array size: " + String.format("%,d", N) + " doubles ("
                + (N * 8L / 1_000_000) + " MB per array, A and B)");
        System.out.println("Kernel A (memory-bound): B[i] = A[i]");
        System.out.println("Kernel B (compute-bound): B[i] = sin(A[i]) * cos(A[i]) + tan(A[i])");
        System.out.println();

        Random rng = new Random(STUDENT_ID);
        double[] a = new double[N];
        double[] b = new double[N];
        for (int i = 0; i < N; i++) a[i] = rng.nextDouble();

        Set<Integer> countSet = new LinkedHashSet<>(Arrays.asList(1, 2, CORES_MAX));
        List<Integer> counts = new ArrayList<>(countSet);

        List<Row> copyRows = benchmark(Kernel.COPY, counts, a, b, 7);
        List<Row> computeRows = benchmark(Kernel.COMPUTE, counts, a, b, 3);

        // Kernel A: copy moves 8 bytes read + 8 bytes written per element
        double bytes = 16.0 * N;
        printTable("Kernel A (memory-bound copy)", copyRows, bytes);
        System.out.println();
        printTable("Kernel B (compute-bound sin*cos+tan)", computeRows, 0);
        System.out.println();

        Row aMax = copyRows.get(copyRows.size() - 1);
        Row bMax = computeRows.get(computeRows.size() - 1);
        boolean aStalled = aMax.efficiency() < STALL_EFFICIENCY;
        boolean bStalled = bMax.efficiency() < STALL_EFFICIENCY;

        System.out.println("=== Conclusion (stall threshold: efficiency < "
                + (int) (STALL_EFFICIENCY * 100) + "% at " + CORES_MAX + " cores) ===");
        System.out.printf("Kernel A efficiency at %d cores: %.1f%% (speedup %.2fx)%n",
                aMax.cores(), 100 * aMax.efficiency(), aMax.speedup());
        System.out.printf("Kernel B efficiency at %d cores: %.1f%% (speedup %.2fx)%n",
                bMax.cores(), 100 * bMax.efficiency(), bMax.speedup());

        if (aStalled && !bStalled) {
            System.out.println("-> Kernel A (vector copy) STALLED in scaling due to memory channel/bandwidth saturation.");
            System.out.println("-> Kernel B (sin/cos/tan) kept scaling: it is bound by CPU computation, not RAM bandwidth.");
        } else if (aStalled && bStalled) {
            System.out.println("-> Both kernels fell below the threshold. Kernel A stalled due to memory bandwidth saturation;");
            System.out.println("   Kernel B's shortfall is more likely SMT/thread-sharing or clock limits, not RAM.");
            System.out.printf("   Kernel A is the more memory-limited one (%.1f%% vs %.1f%% efficiency).%n",
                    100 * aMax.efficiency(), 100 * bMax.efficiency());
        } else if (!aStalled && bStalled) {
            System.out.println("-> Unexpected: Kernel B scaled worse than Kernel A; check for background load.");
        } else {
            System.out.println("-> Neither kernel fell below the stall threshold on this run.");
            System.out.printf("   Kernel A is still the less efficient one (%.1f%% vs %.1f%%): memory bandwidth is its limit.%n",
                    100 * aMax.efficiency(), 100 * bMax.efficiency());
        }
    }


    private static List<Row> benchmark(Kernel kernel, List<Integer> counts,
                                       double[] a, double[] b, int runs) throws Exception {
        double[] medians = new double[counts.size()];
        for (int idx = 0; idx < counts.size(); idx++) {
            int p = counts.get(idx);
            ExecutorService pool = Executors.newFixedThreadPool(p);
            try {
                List<Callable<Void>> tasks = buildTasks(kernel, a, b, p);
                pool.invokeAll(tasks);
                double[] t = new double[runs];
                for (int r = 0; r < runs; r++) {
                    long start = System.nanoTime();
                    pool.invokeAll(tasks);
                    t[r] = (System.nanoTime() - start) / 1_000_000.0;
                }
                Arrays.sort(t);
                medians[idx] = t[runs / 2];
                verify(kernel, a, b);
            } finally {
                pool.shutdown();
            }
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < counts.size(); i++) {
            int p = counts.get(i);
            double speedup = medians[0] / medians[i];
            rows.add(new Row(p, medians[i], speedup, speedup / p));
        }
        return rows;
    }

    private static List<Callable<Void>> buildTasks(Kernel kernel, double[] a, double[] b, int p) {
        List<Callable<Void>> tasks = new ArrayList<>();
        int chunk = (N + p - 1) / p;
        for (int t = 0; t < p; t++) {
            final int from = t * chunk;
            final int to = Math.min(N, from + chunk);
            if (from >= to) break;
            if (kernel == Kernel.COPY) {
                tasks.add(() -> { copyRange(a, b, from, to); return null; });
            } else {
                tasks.add(() -> { computeRange(a, b, from, to); return null; });
            }
        }
        return tasks;
    }

    private static void copyRange(double[] a, double[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            b[i] = a[i];
        }
    }

    private static void computeRange(double[] a, double[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            double x = a[i];
            b[i] = Math.sin(x) * Math.cos(x) + Math.tan(x);
        }
    }

    private static void verify(Kernel kernel, double[] a, double[] b) {
        int step = N / 1000;
        for (int i = 0; i < N; i += step) {
            double expected = kernel == Kernel.COPY
                    ? a[i]
                    : Math.sin(a[i]) * Math.cos(a[i]) + Math.tan(a[i]);
            if (b[i] != expected) {
                throw new IllegalStateException("Wrong result for " + kernel + " at index " + i);
            }
        }
    }

    private static void printTable(String title, List<Row> rows, double bytesMoved) {
        System.out.println(title + " (median time):");
        boolean bw = bytesMoved > 0;
        System.out.printf("%-6s | %-12s | %-8s | %-10s%s%n", "Cores", "Time (ms)", "Speedup", "Efficiency",
                bw ? " | Bandwidth (GB/s)" : "");
        System.out.println("-".repeat(6) + "-+-" + "-".repeat(12) + "-+-" + "-".repeat(8) + "-+-"
                + "-".repeat(10) + (bw ? "-+-" + "-".repeat(16) : ""));
        for (Row r : rows) {
            String line = String.format("%-6d | %-12.3f | %-8.3f | %-10s",
                    r.cores(), r.ms(), r.speedup(), String.format("%.1f%%", 100 * r.efficiency()));
            if (bw) {
                line += String.format(" | %.2f", bytesMoved / (r.ms() / 1000.0) / 1e9);
            }
            System.out.println(line);
        }
    }
}
