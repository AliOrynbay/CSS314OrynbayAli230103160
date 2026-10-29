import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public class Task3 {

    private static final long STUDENT_ID = 230103160;
    private static final long ITERATIONS = 50_000_000L;
    private static final int WARMUP_ITERATIONS = 5_000_000;
    private static final int TIMED_RUNS = 5; 

    private static final int PAD_STRIDE = 16;

    private static final VarHandle AH = MethodHandles.arrayElementVarHandle(long[].class);

    enum Mode { UNPADDED, PADDED, LOCAL }

    public static void main(String[] args) throws Exception {
        int logical = Runtime.getRuntime().availableProcessors();
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : Math.max(1, logical / 2);

        System.out.println("Student ID: " + STUDENT_ID);
        System.out.println("OS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Logical cores: " + logical);
        System.out.println("Worker threads (physical cores assumed): " + threads);
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("Increments per thread: " + ITERATIONS);
        System.out.println("Padded stride: " + PAD_STRIDE + " longs (" + (PAD_STRIDE * 8) + " bytes)");
        System.out.println();

        for (Mode m : Mode.values()) {
            run(m, threads, WARMUP_ITERATIONS);
        }

        double[] unpadded = new double[TIMED_RUNS];
        double[] padded = new double[TIMED_RUNS];
        double[] local = new double[TIMED_RUNS];
        for (int r = 0; r < TIMED_RUNS; r++) {
            unpadded[r] = run(Mode.UNPADDED, threads, ITERATIONS);
            padded[r] = run(Mode.PADDED, threads, ITERATIONS);
            local[r] = run(Mode.LOCAL, threads, ITERATIONS);
        }
        double tUnpadded = median(unpadded);
        double tPadded = median(padded);
        double tLocal = median(local);

        System.out.println("Median of " + TIMED_RUNS + " runs:");
        System.out.printf("%-28s %10.3f ms%n", "Unpadded shared (false share):", tUnpadded);
        System.out.printf("%-28s %10.3f ms%n", "Padded shared (128B stride):", tPadded);
        System.out.printf("%-28s %10.3f ms%n", "Local var, single write:", tLocal);
        System.out.println();
        System.out.printf("Ratio Unpadded / Padded : %.2fx slower%n", tUnpadded / tPadded);
        System.out.printf("Ratio Unpadded / Local  : %.2fx slower%n", tUnpadded / tLocal);
    }

    private static double run(Mode mode, int threads, long iters) throws Exception {
        final long[] counts = new long[mode == Mode.PADDED ? threads * PAD_STRIDE : threads];
        final int stride = mode == Mode.PADDED ? PAD_STRIDE : 1;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] ts = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            final int idx = t * stride;
            ts[t] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                if (mode == Mode.LOCAL) {
                    long c = 0;
                    for (long i = 0; i < iters; i++) {
                        c++;
                    }
                    AH.setOpaque(counts, idx, c); 
                } else {
                    for (long i = 0; i < iters; i++) {
                        AH.setOpaque(counts, idx, (long) AH.getOpaque(counts, idx) + 1);
                    }
                }
            });
            ts[t].start();
        }

        ready.await();
        long start = System.nanoTime();
        go.countDown();
        for (Thread th : ts) {
            th.join();
        }
        long end = System.nanoTime();

        for (int t = 0; t < threads; t++) {
            if (counts[t * stride] != iters) {
                throw new IllegalStateException("Wrong count in " + mode + " thread " + t);
            }
        }
        return (end - start) / 1_000_000.0;
    }

    private static double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }
}
