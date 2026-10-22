import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Task5 {

    private static final long STUDENT_ID = 230103160;
    private static final int[] SIZES = {100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000};
    private static final int BATCHES = 7;           
    private static final long MIN_BATCH_NS = 20_000_000L; 
    private static final int REFINE_POINTS = 14;    

    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static ExecutorService pool;
    private static double sink; 

    record Point(int n, double serialUs, double parallelUs) {
        boolean parallelFaster() {
            return parallelUs < serialUs;
        }
        double speedup() {
            return serialUs / parallelUs;
        }
    }

    public static void main(String[] args) throws Exception {
        pool = Executors.newFixedThreadPool(CORES);

        System.out.println("Student ID: " + STUDENT_ID);
        System.out.println("OS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Logical cores used by parallel version: " + CORES);
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("Operation: y[i] = a * x[i] + b");
        System.out.println("Timing: per-call time, median of " + BATCHES + " batches (each >= 20 ms)");
        System.out.println("Parallel: fixed thread pool (reused), tasks pre-built, invokeAll() + wait");
        System.out.println();

        List<Point> coarse = new ArrayList<>();
        for (int n : SIZES) {
            coarse.add(measure(n));
        }
        printTable("Coarse sweep", coarse);

        int first = firstStableFaster(coarse);
        System.out.println();
        if (first == -1) {
            System.out.println("RESULT: multi-threading was NEVER faster than serial for N in "
                    + SIZES[0] + ".." + SIZES[SIZES.length - 1] + ".");
        } else if (first == 0) {
            System.out.println("RESULT: multi-threading was ALREADY faster at N = " + SIZES[0]
                    + " (no crossover inside the tested range).");
        } else {
            int lo = coarse.get(first - 1).n(); 
            int hi = coarse.get(first).n();     
            System.out.println("Coarse crossover bracket: slower at N=" + lo + ", faster at N=" + hi);

            List<Point> fine = new ArrayList<>();
            fine.add(coarse.get(first - 1));
            double ratio = Math.pow((double) hi / lo, 1.0 / (REFINE_POINTS + 1));
            int prev = lo;
            for (int k = 1; k <= REFINE_POINTS; k++) {
                int n = (int) Math.round(lo * Math.pow(ratio, k));
                if (n <= prev) continue;
                fine.add(measure(n));
                prev = n;
            }
            fine.add(coarse.get(first));
            System.out.println();
            printTable("Refined sweep inside bracket", fine);

            int f = firstStableFaster(fine);
            int slowerN = fine.get(Math.max(0, f - 1)).n();
            int fasterN = fine.get(f).n();
            System.out.println();
            System.out.println("RESULT: multi-threading shifts from SLOWER to FASTER between N = "
                    + String.format("%,d", slowerN) + " and N = " + String.format("%,d", fasterN)
                    + " on this hardware (" + CORES + " logical cores).");
            System.out.println("        Estimated crossover point: N ~ "
                    + String.format("%,d", Math.round(Math.sqrt((double) slowerN * fasterN))));
        }

        pool.shutdown();
        if (sink == 42.4242) System.out.println(); 
    }

    private static int firstStableFaster(List<Point> pts) {
        int idx = -1;
        for (int i = pts.size() - 1; i >= 0; i--) {
            if (pts.get(i).parallelFaster()) idx = i;
            else break;
        }
        return idx;
    }

    private static void printTable(String title, List<Point> pts) {
        System.out.println(title + ":");
        System.out.printf("%-12s | %-14s | %-16s | %-8s | %-8s%n",
                "N", "Serial (us)", "Parallel (us)", "Speedup", "Winner");
        System.out.println("-".repeat(12) + "-+-" + "-".repeat(14) + "-+-" + "-".repeat(16)
                + "-+-" + "-".repeat(8) + "-+-" + "-".repeat(8));
        for (Point p : pts) {
            System.out.printf("%-12s | %-14.3f | %-16.3f | %-8.3f | %-8s%n",
                    String.format("%,d", p.n()), p.serialUs(), p.parallelUs(), p.speedup(),
                    p.parallelFaster() ? "PARALLEL" : "SERIAL");
        }
    }


    private static Point measure(int n) throws Exception {
        Random rng = new Random(STUDENT_ID);
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) x[i] = rng.nextDouble();
        final double a = 2.5, b = 1.25;

        Runnable serial = () -> {
            for (int i = 0; i < n; i++) {
                y[i] = a * x[i] + b;
            }
        };

        int chunk = (n + CORES - 1) / CORES;
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < CORES; t++) {
            final int from = t * chunk;
            final int to = Math.min(n, from + chunk);
            if (from >= to) break;
            tasks.add(() -> {
                for (int i = from; i < to; i++) {
                    y[i] = a * x[i] + b;
                }
                return null;
            });
        }
        Runnable parallel = () -> {
            try {
                pool.invokeAll(tasks);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        double s = timeUs(serial, y);
        double p = timeUs(parallel, y);

        parallel.run();
        for (int i = 0; i < n; i++) {
            if (y[i] != a * x[i] + b) throw new IllegalStateException("Wrong result at N=" + n);
        }
        return new Point(n, s, p);
    }

    private static double timeUs(Runnable op, double[] y) {
        int iters = 1;
        while (batchNs(op, iters) < MIN_BATCH_NS) {
            iters *= 2;
        }
        double[] per = new double[BATCHES];
        for (int b = 0; b < BATCHES; b++) {
            per[b] = batchNs(op, iters) / (double) iters / 1000.0;
        }
        sink += y[y.length / 2];
        Arrays.sort(per);
        return per[BATCHES / 2];
    }

    private static long batchNs(Runnable op, int iters) {
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            op.run();
        }
        return System.nanoTime() - start;
    }
}
