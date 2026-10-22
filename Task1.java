import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class Task1 {

    private static final long STUDENT_ID = 230103160;
    private static final int N = 20_000_000;

    public static void main(String[] args) {
        System.out.println("Student ID: " + STUDENT_ID);
        System.out.println("OS: " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("CPU: " + detectCpuModel());
        System.out.println("Logical cores (availableProcessors): "
                + Runtime.getRuntime().availableProcessors());
        System.out.println("Java: " + System.getProperty("java.version"));

        Random rng = new Random(STUDENT_ID);
        double[] data = new double[N];
        for (int i = 0; i < N; i++) {
            data[i] = rng.nextDouble();
        }
        System.out.println("Array size: " + N + " (double / float64)");

            long start = System.nanoTime();

        double sum = 0.0;
        for (int i = 0; i < N; i++) {
            sum += data[i];
        }
        double mean = sum / N;

        double sqDiff = 0.0;
        for (int i = 0; i < N; i++) {
            double d = data[i] - mean;
            sqDiff += d * d;
        }
        double stdDev = Math.sqrt(sqDiff / N); 

        long end = System.nanoTime();
        double elapsedMs = (end - start) / 1_000_000.0;

        System.out.printf("Sum: %.6f%n", sum);
        System.out.printf("Mean: %.10f%n", mean);
        System.out.printf("Standard deviation: %.10f%n", stdDev);
        System.out.printf("Serial execution time: %.3f ms%n", elapsedMs);
    }

    private static String detectCpuModel() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                String id = System.getenv("PROCESSOR_IDENTIFIER");
                if (id != null) return id;
            } else if (os.contains("linux")) {
                Path p = Path.of("/proc/cpuinfo");
                if (Files.exists(p)) {
                    for (String line : Files.readAllLines(p)) {
                        if (line.startsWith("model name")) {
                            return line.substring(line.indexOf(':') + 1).trim();
                        }
                    }
                }
            } else if (os.contains("mac")) {
                Process proc = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line = r.readLine();
                    if (line != null && !line.isBlank()) return line.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "Unknown (arch: " + System.getProperty("os.arch") + ")";
    }
}
