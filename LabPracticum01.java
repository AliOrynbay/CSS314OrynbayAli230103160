import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ZEBA ACADEMY — Lab Practicum 01: Empirical Parallel Computing
 *
 * Один файл, два режима запуска (Task 2 и Task 3).
 *
 * СБОРКА:
 *   javac LabPracticum01.java
 *
 * ЗАПУСК TASK 2 (масштабирование по потокам, Prime Sieve до 5,000,000):
 *   java LabPracticum01 task2 1
 *   java LabPracticum01 task2 2
 *   java LabPracticum01 task2 4
 *   java LabPracticum01 task2 8
 *   java LabPracticum01 task2 16
 *   java LabPracticum01 task2 32
 *   (Каждую команду запустить 3 раза — вручную, чтобы получить Run1/Run2/Run3
 *    для таблицы Task 2. Программа сама делает 1 "прогрев" + печатает итог.)
 *
 * ЗАПУСК TASK 3 (race condition без синхронизации + с Mutex):
 *   java LabPracticum01 task3
 *   (Печатает 10 запусков без блокировки + сравнение unlocked/locked времени.)
 *
 * ВАЖНО: все цифры, которые выведет эта программа на ВАШЕЙ машине,
 * реальны и годятся для отчёта. Не переносите чужие числа — препод
 * попросил именно физически замеренные значения.
 */
public class LabPracticum01 {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Использование:");
            System.out.println("  java LabPracticum01 task2 <N_ПОТОКОВ>");
            System.out.println("  java LabPracticum01 task3");
            return;
        }

        switch (args[0]) {
            case "task2":
                if (args.length < 2) {
                    System.out.println("Укажите число потоков, например: java LabPracticum01 task2 4");
                    return;
                }
                int threads = Integer.parseInt(args[1]);
                runTask2(threads);
                break;
            case "task3":
                runTask3();
                break;
            default:
                System.out.println("Неизвестный режим: " + args[0]);
        }
    }

    // =====================================================================
    // TASK 2: Multi-Thread Scaling Benchmark — Prime Sieve до LIMIT
    // Компьют-бound задача: делим диапазон [2, LIMIT] на N кусков,
    // каждый поток считает простые числа в своём куске (пробное деление).
    // =====================================================================

    static final long LIMIT = 5_000_000L;

    static void runTask2(int numThreads) throws InterruptedException, ExecutionException {
        System.out.println("=== TASK 2: Prime Sieve до " + LIMIT + " | Потоков: " + numThreads + " ===");

        // Один "прогревочный" проход, чтобы JIT скомпилировал горячий код
        // и не искажал первое реальное измерение.
        countPrimesParallel(1);

        long start = System.nanoTime();
        long primeCount = countPrimesParallel(numThreads);
        long elapsedNanos = System.nanoTime() - start;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

        System.out.printf("Найдено простых чисел: %d%n", primeCount);
        System.out.printf("Время выполнения (Avg Time TN): %.4f s%n", elapsedSeconds);
        System.out.println("-> Запишите это значение в таблицу Task 2 для N = " + numThreads);
    }

    static long countPrimesParallel(int numThreads) throws InterruptedException, ExecutionException {
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        long chunkSize = LIMIT / numThreads;
        var futures = new java.util.ArrayList<Future<Long>>();

        for (int i = 0; i < numThreads; i++) {
            long from = i * chunkSize + 2; // числа начинаются с 2
            long to = (i == numThreads - 1) ? LIMIT : (i + 1) * chunkSize + 1;
            futures.add(pool.submit(() -> countPrimesInRange(from, to)));
        }

        long total = 0;
        for (Future<Long> f : futures) {
            total += f.get();
        }
        pool.shutdown();
        return total;
    }

    static long countPrimesInRange(long from, long to) {
        long count = 0;
        for (long n = from; n <= to; n++) {
            if (isPrime(n)) count++;
        }
        return count;
    }

    static boolean isPrime(long n) {
        if (n < 2) return false;
        if (n == 2) return true;
        if (n % 2 == 0) return false;
        for (long i = 3; i * i <= n; i += 2) {
            if (n % i == 0) return false;
        }
        return true;
    }

    // =====================================================================
    // TASK 3: Unsynchronized Shared Counter — Race Condition Trap
    // 10 потоков, каждый инкрементирует общий int 1,000,000 раз БЕЗ lock.
    // Ожидаемое значение: 10,000,000. Из-за гонки данных фактическое
    // значение будет меньше и будет отличаться от запуска к запуску.
    // =====================================================================

    static final int NUM_THREADS_T3 = 10;
    static final int INCREMENTS_PER_THREAD = 1_000_000;
    static final long EXPECTED_TOTAL = (long) NUM_THREADS_T3 * INCREMENTS_PER_THREAD;

    // Общий счётчик БЕЗ синхронизации — намеренно обычный int, не Atomic.
    static int unsafeCounter = 0;

    // Для версии с блокировкой
    static int lockedCounter = 0;
    static final ReentrantLock lock = new ReentrantLock();

    static void runTask3() throws InterruptedException {
        System.out.println("=== TASK 3: Race Condition Trap (10 запусков без синхронизации) ===");
        System.out.println("Ожидаемое значение каждый раз: " + EXPECTED_TOTAL);
        System.out.println();

        for (int run = 1; run <= 10; run++) {
            unsafeCounter = 0; // сброс перед каждым запуском
            runUnsafeIncrements();
            long error = EXPECTED_TOTAL - unsafeCounter;
            System.out.printf("Run #%-2d | Измеренный результат: %-10d | Ошибка (10^7 - Act): %d%n",
                    run, unsafeCounter, error);
        }

        System.out.println();
        System.out.println("=== TASK 3.2: Синхронизация через ReentrantLock (Mutex) ===");

        // --- Замер времени БЕЗ блокировки ---
        unsafeCounter = 0;
        long startUnlocked = System.nanoTime();
        runUnsafeIncrements();
        long elapsedUnlockedMs = (System.nanoTime() - startUnlocked) / 1_000_000;

        // --- Замер времени С блокировкой ---
        lockedCounter = 0;
        long startLocked = System.nanoTime();
        runLockedIncrements();
        long elapsedLockedMs = (System.nanoTime() - startLocked) / 1_000_000;

        System.out.printf("Unlocked = %d ms (результат: %d, некорректен)%n", elapsedUnlockedMs, unsafeCounter);
        System.out.printf("Locked   = %d ms (результат: %d, должен быть точно %d)%n",
                elapsedLockedMs, lockedCounter, EXPECTED_TOTAL);
        System.out.println("-> Впишите эти два значения в поля 'Unlocked = ___ ms vs. Locked = ___ ms'");
    }

    static void runUnsafeIncrements() throws InterruptedException {
        Thread[] threads = new Thread[NUM_THREADS_T3];
        for (int i = 0; i < NUM_THREADS_T3; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < INCREMENTS_PER_THREAD; j++) {
                    unsafeCounter++; // НЕ атомарно: Load -> Add -> Store, гонка данных
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
    }

    static void runLockedIncrements() throws InterruptedException {
        Thread[] threads = new Thread[NUM_THREADS_T3];
        for (int i = 0; i < NUM_THREADS_T3; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < INCREMENTS_PER_THREAD; j++) {
                    lock.lock();
                    try {
                        lockedCounter++;
                    } finally {
                        lock.unlock();
                    }
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
    }
}
