import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


public class Task6 {

    private static final long STUDENT_ID = 230103160;
    private static final int THREADS = 4;
    private static final int INCREMENTS = 2_000_000;
    private static final int EXPECTED = THREADS * INCREMENTS;
    private static final int WARMUP_RUNS = 3;
    private static final int RUNS = 7;

    private static final VarHandle CELL = MethodHandles.arrayElementVarHandle(int[].class);

    enum Mode { RACY, ATOMIC, LOCK }

    record Result(int finalValue, double ms) {}

    public static void main(String[] args) throws Exception {
        System.out.println("Student ID: " + STUDENT_ID);
        System.out.println("OS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Logical cores: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("Threads: " + THREADS + ", increments per thread: " + String.format("%,d", INCREMENTS));
        System.out.println();

        for (int w = 0; w < WARMUP_RUNS; w++) {
            for (Mode m : Mode.values()) run(m);
        }

        System.out.println("=== Unsynchronized counter (data race) ===");
        System.out.printf("Expected final value: %,d%n", EXPECTED);
        double[] racyTimes = new double[RUNS];
        long totalLost = 0;
        for (int r = 0; r < RUNS; r++) {
            Result res = run(Mode.RACY);
            racyTimes[r] = res.ms();
            int lost = EXPECTED - res.finalValue();
            totalLost += lost;
            System.out.printf("  Run %d: actual = %,d | lost = %,d (%.2f%%) | time = %.3f ms%n",
                    r + 1, res.finalValue(), lost, 100.0 * lost / EXPECTED, res.ms());
        }
        System.out.printf("Average lost updates over %d runs: %.2f%%%n%n",
                RUNS, 100.0 * totalLost / ((long) EXPECTED * RUNS));

        System.out.println("=== Correct versions ===");
        double[] atomicTimes = new double[RUNS];
        double[] lockTimes = new double[RUNS];
        int atomicFinal = 0, lockFinal = 0;
        for (int r = 0; r < RUNS; r++) {
            Result a = run(Mode.ATOMIC);
            Result l = run(Mode.LOCK);
            atomicTimes[r] = a.ms();
            lockTimes[r] = l.ms();
            atomicFinal = a.finalValue();
            lockFinal = l.finalValue();
            if (a.finalValue() != EXPECTED || l.finalValue() != EXPECTED) {
                throw new IllegalStateException("Synchronized version produced a wrong result!");
            }
        }
        System.out.printf("AtomicInteger final value : %,d (expected %,d) -> %s%n",
                atomicFinal, EXPECTED, atomicFinal == EXPECTED ? "CORRECT" : "WRONG");
        System.out.printf("ReentrantLock final value : %,d (expected %,d) -> %s%n%n",
                lockFinal, EXPECTED, lockFinal == EXPECTED ? "CORRECT" : "WRONG");

        double tRacy = median(racyTimes);
        double tAtomic = median(atomicTimes);
        double tLock = median(lockTimes);

        System.out.println("=== Wall-clock time (median of " + RUNS + " runs) ===");
        System.out.printf("%-24s %10.3f ms%n", "Unsynchronized (wrong):", tRacy);
        System.out.printf("%-24s %10.3f ms%n", "AtomicInteger (correct):", tAtomic);
        System.out.printf("%-24s %10.3f ms%n", "ReentrantLock (correct):", tLock);
        System.out.println();
        System.out.printf("Atomic penalty vs racy : %+.3f ms (%.2fx)%n", tAtomic - tRacy, tAtomic / tRacy);
        System.out.printf("Lock penalty vs racy   : %+.3f ms (%.2fx)%n", tLock - tRacy, tLock / tRacy);
    }

    private static Result run(Mode mode) throws Exception {
        final int[] cell = new int[1];
        final AtomicInteger atomic = new AtomicInteger();
        final ReentrantLock lock = new ReentrantLock();
        final int[] lockedCounter = new int[1];

        CountDownLatch ready;
        ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] ts = new Thread[THREADS];

        for (int t = 0; t < THREADS; t++) {
            ts[t] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                switch (mode) {
                    case RACY -> {
                        for (int i = 0; i < INCREMENTS; i++) {
                            CELL.setOpaque(cell, 0, (int) CELL.getOpaque(cell, 0) + 1);
                        }
                    }
                    case ATOMIC -> {
                        for (int i = 0; i < INCREMENTS; i++) {
                            atomic.incrementAndGet();
                        }
                    }
                    case LOCK -> {
                        for (int i = 0; i < INCREMENTS; i++) {
                            lock.lock();
                            try {
                                lockedCounter[0]++;
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                }
            });
            ts[t].start();
        }

        ready.await();
        long start = System.nanoTime();
        go.countDown();
        for (Thread th : ts) th.join();
        long end = System.nanoTime();

        int value = switch (mode) {
            case RACY -> cell[0];
            case ATOMIC -> atomic.get();
            case LOCK -> lockedCounter[0];
        };
        return new Result(value, (end - start) / 1_000_000.0);
    }

    private static double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }
}

