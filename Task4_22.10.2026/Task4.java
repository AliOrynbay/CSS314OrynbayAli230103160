import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class Task4 {

    private static final long STUDENT_ID = 230103160L;
    private static final int ITEMS = 1000;
    private static final int WORKERS = 4;
    private static final int RUNS = 5; 
    private static void work(int i) {
        long ns = 100_000L * (i % 20);
        long end = System.nanoTime() + ns;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    record Result(double totalMs, double[] finishMs) {
        double deltaMs() {
            double min = Double.MAX_VALUE, max = 0;
            for (double f : finishMs) {
                min = Math.min(min, f);
                max = Math.max(max, f);
            }
            return max - min;
        }
    }

    interface Strategy {
        void runWorker(int workerId);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Student ID: " + STUDENT_ID);
        System.out.println("OS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Logical cores: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("Items: " + ITEMS + ", workers: " + WORKERS
                + ", cost(i) = 0.0001 * (i % 20) s");

        double totalWorkMs = 0;
        for (int i = 0; i < ITEMS; i++) {
            totalWorkMs += 0.1 * (i % 20);
        }
        System.out.printf("Total work: %.1f ms | ideal parallel time (work / %d): %.1f ms%n%n",
                totalWorkMs, WORKERS, totalWorkMs / WORKERS);

       
        int block = ITEMS / WORKERS;
        System.out.print("Static block work (ms):");
        for (int w = 0; w < WORKERS; w++) {
            double s = 0;
            for (int i = w * block; i < (w + 1) * block; i++) s += 0.1 * (i % 20);
            System.out.printf(" T%d=%.1f", w, s);
        }
        System.out.println("\n");

        report("A: Static chunks (equal blocks)", benchmark(staticFactory()));
        report("B1: Dynamic, chunk size = 1", benchmark(dynamicFactory(1)));
        report("B2: Dynamic, chunk size = 5", benchmark(dynamicFactory(5)));
    }

  
    private static java.util.function.Supplier<Strategy> staticFactory() {
        return () -> workerId -> {
            int per = ITEMS / WORKERS;
            int from = workerId * per;
            int to = (workerId == WORKERS - 1) ? ITEMS : from + per;
            for (int i = from; i < to; i++) {
                work(i);
            }
        };
    }

   
    private static java.util.function.Supplier<Strategy> dynamicFactory(int chunk) {
        return () -> {
            AtomicInteger next = new AtomicInteger(0);
            return workerId -> {
                while (true) {
                    int from = next.getAndAdd(chunk);
                    if (from >= ITEMS) break;
                    int to = Math.min(ITEMS, from + chunk);
                    for (int i = from; i < to; i++) {
                        work(i);
                    }
                }
            };
        };
    }


    private static Result benchmark(java.util.function.Supplier<Strategy> factory) throws Exception {
        List<Result> results = new ArrayList<>();
        for (int r = 0; r < RUNS; r++) {
            results.add(runOnce(factory.get()));
        }
        results.sort(Comparator.comparingDouble(Result::totalMs));
        return results.get(RUNS / 2); 
    }

    private static Result runOnce(Strategy strategy) throws Exception {
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch go = new CountDownLatch(1);
        long[] finish = new long[WORKERS];
        Thread[] ts = new Thread[WORKERS];

        for (int w = 0; w < WORKERS; w++) {
            final int id = w;
            ts[w] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                strategy.runWorker(id);
                finish[id] = System.nanoTime();
            });
            ts[w].start();
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        for (Thread t : ts) t.join();
        long end = System.nanoTime();

        double[] fin = new double[WORKERS];
        for (int w = 0; w < WORKERS; w++) {
            fin[w] = (finish[w] - start) / 1_000_000.0;
        }
        return new Result((end - start) / 1_000_000.0, fin);
    }

    private static void report(String name, Result r) {
        double min = Arrays.stream(r.finishMs()).min().getAsDouble();
        double max = Arrays.stream(r.finishMs()).max().getAsDouble();
        System.out.println(name);
        System.out.printf("  Total wall time        : %.3f ms%n", r.totalMs());
        System.out.printf("  First thread finished  : %.3f ms%n", min);
        System.out.printf("  Last thread finished   : %.3f ms%n", max);
        System.out.printf("  Completion delta (max-min): %.3f ms%n", r.deltaMs());
        StringBuilder sb = new StringBuilder("  Per-thread finish (ms) :");
        for (int w = 0; w < WORKERS; w++) {
            sb.append(String.format(" T%d=%.2f", w, r.finishMs()[w]));
        }
        System.out.println(sb);
        System.out.println();
    }
}
