// Lab 2: Numerical Integration (Pi) & Parallel Reductions (Java)
// Compile: javac PiIntegrationLab2.java
// Run:     java PiIntegrationLab2

import java.io.FileWriter;
import java.io.IOException;
import java.util.stream.LongStream;

public class PiIntegrationLab2 {
    static final long N = 20_000_000L;
    static final double STEP = 1.0 / N;

    static double runSerial() {
        double sum = 0.0;
        for (long i = 0; i < N; i++) {
            double x = (i + 0.5) * STEP;
            sum += 4.0 / (1.0 + x * x);
        }
        return sum * STEP;
    }

    // Variant A: Naive Unsynchronized Race Condition
    static double runNaiveRace(int threads) throws InterruptedException {
        double[] sharedSum = new double[1];
        Thread[] pool = new Thread[threads];
        long chunkSize = N / threads;
        for (int t = 0; t < threads; t++) {
            final long start = t * chunkSize;
            final long end = (t == threads - 1) ? N : start + chunkSize;
            pool[t] = new Thread(() -> {
                for (long i = start; i < end; i++) {
                    double x = (i + 0.5) * STEP;
                    sharedSum[0] += 4.0 / (1.0 + x * x); // unprotected write -> race
                }
            });
            pool[t].start();
        }
        for (Thread t : pool) t.join();
        return sharedSum[0] * STEP;
    }

    // Variant B: Synchronized Critical Section
    static double runCriticalSection(int threads) throws InterruptedException {
        final Object lock = new Object();
        double[] sharedSum = new double[1];
        Thread[] pool = new Thread[threads];
        long chunkSize = N / threads;
        for (int t = 0; t < threads; t++) {
            final long start = t * chunkSize;
            final long end = (t == threads - 1) ? N : start + chunkSize;
            pool[t] = new Thread(() -> {
                for (long i = start; i < end; i++) {
                    double x = (i + 0.5) * STEP;
                    double term = 4.0 / (1.0 + x * x);
                    synchronized (lock) { // emulates #pragma omp critical
                        sharedSum[0] += term;
                    }
                }
            });
            pool[t].start();
        }
        for (Thread t : pool) t.join();
        return sharedSum[0] * STEP;
    }

    // Variant C: Parallel Reduction (emulates #pragma omp parallel for reduction(+:sum))
    static double runParallelReduction() {
        return LongStream.range(0, N)
                .parallel()
                .mapToDouble(i -> {
                    double x = (i + 0.5) * STEP;
                    return 4.0 / (1.0 + x * x);
                })
                .sum() * STEP;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Numerical Integration Benchmark (N = " + N + ")...");

        long t0 = System.nanoTime();
        double piSerial = runSerial();
        long t1 = System.nanoTime();
        System.out.printf("Serial:     Pi = %.10f | Time = %d ms | Error = %.2e%n",
                piSerial, (t1 - t0) / 1_000_000, Math.abs(piSerial - Math.PI));

        t0 = System.nanoTime();
        double piRed = runParallelReduction();
        t1 = System.nanoTime();
        System.out.printf("Reduction:  Pi = %.10f | Time = %d ms | Error = %.2e%n",
                piRed, (t1 - t0) / 1_000_000, Math.abs(piRed - Math.PI));

        int threads = 4;
        for (int trial = 0; trial < 3; trial++) {
            t0 = System.nanoTime();
            double piRace = runNaiveRace(threads);
            t1 = System.nanoTime();
            System.out.printf("Naive race (t%d): Pi = %.10f | Time = %d ms | Error = %.2e%n",
                    trial, piRace, (t1 - t0) / 1_000_000, Math.abs(piRace - Math.PI));
        }

        t0 = System.nanoTime();
        double piCrit = runCriticalSection(threads);
        t1 = System.nanoTime();
        System.out.printf("Critical:   Pi = %.10f | Time = %d ms | Error = %.2e%n",
                piCrit, (t1 - t0) / 1_000_000, Math.abs(piCrit - Math.PI));

        try (FileWriter fw = new FileWriter("lab2_java_variants.csv")) {
            fw.write("variant,n,threads,pi_estimate,abs_error\n");
            fw.write("serial," + N + ",1," + piSerial + "," + Math.abs(piSerial - Math.PI) + "\n");
            fw.write("reduction," + N + "," + threads + "," + piRed + "," + Math.abs(piRed - Math.PI) + "\n");
            fw.write("critical_section," + N + "," + threads + "," + piCrit + "," + Math.abs(piCrit - Math.PI) + "\n");
        }
        System.out.println("Saved -> lab2_java_variants.csv");
    }
}
