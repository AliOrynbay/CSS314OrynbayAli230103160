// Lab 3: Work-Sharing & Loop Scheduling Policies (Mandelbrot) - Java
// Compile: javac MandelbrotSchedulerLab3.java
// Run:     java MandelbrotSchedulerLab3
//
// NOTE: the lab manual's printed Java template contains a syntax bug --
// `while (... and iter < MAX_ITER)` uses the Python keyword `and` instead
// of Java's `&&`. That is fixed below.

import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MandelbrotSchedulerLab3 {
    static final int WIDTH = 960, HEIGHT = 540, MAX_ITER = 1000;
    static final int[][] IMAGE = new int[HEIGHT][WIDTH];

    static int computePixel(int px, int py) {
        double x0 = (px - WIDTH / 2.0) * 4.0 / WIDTH;
        double y0 = (py - HEIGHT / 2.0) * 4.0 / HEIGHT;
        double x = 0.0, y = 0.0;
        int iter = 0;
        while (x * x + y * y <= 4.0 && iter < MAX_ITER) { // fixed: && not `and`
            double temp = x * x - y * y + x0;
            y = 2.0 * x * y + y0;
            x = temp;
            iter++;
        }
        return iter;
    }

    // Static scheduling: fixed contiguous row blocks assigned up front
    static long runStatic(int numThreads) throws InterruptedException {
        Thread[] threads = new Thread[numThreads];
        int rowsPerThread = (HEIGHT + numThreads - 1) / numThreads;
        long start = System.currentTimeMillis();
        for (int t = 0; t < numThreads; t++) {
            final int startRow = t * rowsPerThread;
            final int endRow = Math.min(startRow + rowsPerThread, HEIGHT);
            threads[t] = new Thread(() -> {
                for (int y = startRow; y < endRow; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        IMAGE[y][x] = computePixel(x, y);
                    }
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        return System.currentTimeMillis() - start;
    }

    // Dynamic scheduling with configurable chunk size (shared work-queue via AtomicInteger)
    static long runDynamic(int numThreads, int chunkSize) throws InterruptedException {
        AtomicInteger workQueue = new AtomicInteger(0);
        Thread[] threads = new Thread[numThreads];
        long startTime = System.currentTimeMillis();
        for (int t = 0; t < numThreads; t++) {
            threads[t] = new Thread(() -> {
                int startRow;
                while ((startRow = workQueue.getAndAdd(chunkSize)) < HEIGHT) {
                    int endRow = Math.min(startRow + chunkSize, HEIGHT);
                    for (int y = startRow; y < endRow; y++) {
                        for (int x = 0; x < WIDTH; x++) {
                            IMAGE[y][x] = computePixel(x, y);
                        }
                    }
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        return System.currentTimeMillis() - startTime;
    }

    // Load-imbalance instrumentation: total iterations executed per thread
    static long[] runDynamicWithLoadTracking(int numThreads, int chunkSize) throws InterruptedException {
        AtomicInteger workQueue = new AtomicInteger(0);
        AtomicLong[] workDone = new AtomicLong[numThreads];
        for (int i = 0; i < numThreads; i++) workDone[i] = new AtomicLong(0);
        Thread[] threads = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            final int tid = t;
            threads[t] = new Thread(() -> {
                int startRow;
                while ((startRow = workQueue.getAndAdd(chunkSize)) < HEIGHT) {
                    int endRow = Math.min(startRow + chunkSize, HEIGHT);
                    long localWork = 0;
                    for (int y = startRow; y < endRow; y++) {
                        for (int x = 0; x < WIDTH; x++) {
                            localWork += computePixel(x, y);
                        }
                    }
                    workDone[tid].addAndGet(localWork);
                }
            });
            threads[t].start();
        }
        for (Thread t : threads) t.join();
        long[] result = new long[numThreads];
        for (int i = 0; i < numThreads; i++) result[i] = workDone[i].get();
        return result;
    }

    public static void main(String[] args) throws Exception {
        int threads = 4;
        int[] chunkSizes = {1, 16, 64, 256};

        System.out.println("Static scheduling:");
        long staticTime = runStatic(threads);
        System.out.printf("  Threads: %d | Time: %d ms%n", threads, staticTime);

        System.out.println("Dynamic scheduling across chunk sizes:");
        try (FileWriter fw = new FileWriter("lab3_java_scheduling.csv")) {
            fw.write("policy,threads,chunk_size,time_ms\n");
            fw.write("static," + threads + ",-1," + staticTime + "\n");
            for (int chunk : chunkSizes) {
                long duration = runDynamic(threads, chunk);
                System.out.printf("  Threads: %d | Chunk Size: %3d | Time: %d ms%n", threads, chunk, duration);
                fw.write("dynamic," + threads + "," + chunk + "," + duration + "\n");
            }
        }

        long[] loadPerThread = runDynamicWithLoadTracking(threads, 16);
        long maxWork = Long.MIN_VALUE, minWork = Long.MAX_VALUE, totalWork = 0;
        for (long w : loadPerThread) { maxWork = Math.max(maxWork, w); minWork = Math.min(minWork, w); totalWork += w; }
        double avgWork = totalWork / (double) threads;
        double imbalance = (maxWork - minWork) / avgWork;
        System.out.printf("Load imbalance (dynamic, chunk=16): %.4f%n", imbalance);
        System.out.println("Saved -> lab3_java_scheduling.csv");
    }
}
