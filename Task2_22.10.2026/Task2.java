import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Task2 {

    private static final long STUDENT_ID = 230103160L;
    private static final int N = 20_000_000;
    private static final double P = 0.95;   
    private static final int WARMUP_RUNS = 3;
    private static final int TIMED_RUNS = 7; 

    public static void main(String[] args) throws Exception {
        int maxCores = Runtime.getRuntime().availableProcessors();

        System.out.println("Student ID: " + STUDENT_ID);
        System.out.println("OS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Logical cores (availableProcessors): " + maxCores);
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("Array size: " + N + " (double / float64), Amdahl P = " + P);
        System.out.println();

        Random rng = new Random(STUDENT_ID);
        double[] data = new double[N];
        for (int i = 0; i < N; i++) {
            data[i] = rng.nextDouble();
        }

        Set<Integer> countSet = new LinkedHashSet<>(Arrays.asList(1, 2, 4, maxCores, maxCores * 2));
        List<Integer> counts = new ArrayList<>(countSet);

        double[] times = new double[counts.size()];
        double[] sums = new double[counts.size()];

        for (int idx = 0; idx < counts.size(); idx++) {
            int p = counts.get(idx);
            ExecutorService pool = Executors.newFixedThreadPool(p);
            try {
                for (int w = 0; w < WARMUP_RUNS; w++) {
                    parallelSum(data, p, pool);
                }
                double[] runs = new double[TIMED_RUNS];
                double sum = 0;
                for (int r = 0; r < TIMED_RUNS; r++) {
                    long start = System.nanoTime();
                    sum = parallelSum(data, p, pool);
                    long end = System.nanoTime();
                    runs[r] = (end - start) / 1_000_000.0;
                }
                Arrays.sort(runs);
                times[idx] = runs[TIMED_RUNS / 2]; 
                sums[idx] = sum;
            } finally {
                pool.shutdown();
            }
        }

        double t1 = times[0];

        System.out.println("Median of " + TIMED_RUNS + " timed runs per worker count (after "
                + WARMUP_RUNS + " warm-up runs)");
        System.out.println();
        System.out.printf("%-8s | %-14s | %-10s | %-18s | %-16s%n",
                "Cores", "Wall Time (ms)", "Speedup", "Amdahl Upper Bound", "Sum");
        System.out.println("-".repeat(8) + "-+-" + "-".repeat(14) + "-+-" + "-".repeat(10)
                + "-+-" + "-".repeat(18) + "-+-" + "-".repeat(16));

        for (int i = 0; i < counts.size(); i++) {
            int p = counts.get(i);
            double speedup = t1 / times[i];
            double amdahl = 1.0 / ((1.0 - P) + P / p);
            System.out.printf("%-8d | %-14.3f | %-10.3f | %-18.3f | %-16.6f%n",
                    p, times[i], speedup, amdahl, sums[i]);
        }

        System.out.println();
        System.out.printf("Amdahl limit as p -> infinity: %.1fx%n", 1.0 / (1.0 - P));
    }

    private static double parallelSum(double[] data, int p, ExecutorService pool) throws Exception {
        int n = data.length;
        int chunk = (n + p - 1) / p;
        List<Future<Double>> futures = new ArrayList<>(p);

        for (int t = 0; t < p; t++) {
            final int from = t * chunk;
            final int to = Math.min(n, from + chunk);
            if (from >= to) break;
            Callable<Double> task = () -> {
                double s = 0.0;
                for (int i = from; i < to; i++) {
                    s += data[i];
                }
                return s;
            };
            futures.add(pool.submit(task));
        }

        double total = 0.0;
        for (Future<Double> f : futures) {
            total += f.get();
        }
        return total;
    }
}
