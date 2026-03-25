package santannaf.oracle.demo.benchmark;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.stream.IntStream;

/**
 * Lightweight benchmark runner using virtual threads.
 * Collects per-request latencies (nanos) and error counts.
 */
public final class BenchmarkRunner {

    public record Config(
            int totalRequests,
            int concurrency,
            int warmupRequests
    ) {
        public Config {
            if (totalRequests <= 0 || concurrency <= 0) {
                throw new IllegalArgumentException("totalRequests and concurrency must be > 0");
            }
        }
    }

    public record Result(
            String scenario,
            String poolType,
            long[] latenciesNanos,
            int errors,
            long wallClockNanos
    ) {
        public double throughput() {
            double seconds = wallClockNanos / 1_000_000_000.0;
            return latenciesNanos.length / seconds;
        }

        public double avgMs() {
            return Arrays.stream(latenciesNanos).average().orElse(0) / 1_000_000.0;
        }

        public double percentileMs(double p) {
            if (latenciesNanos.length == 0) return 0;
            long[] sorted = latenciesNanos.clone();
            Arrays.sort(sorted);
            int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, idx)] / 1_000_000.0;
        }
    }

    private BenchmarkRunner() {}

    /**
     * Runs a benchmark: first warmup, then measured requests.
     *
     * @param scenario     scenario label (for the report)
     * @param poolType     pool label (hikari/ucp)
     * @param config       benchmark parameters
     * @param baseUrl      e.g. "http://localhost:8080"
     * @param requestFn    function that builds an HttpRequest from the base URL and a request index
     */
    public static Result run(
            String scenario,
            String poolType,
            Config config,
            String baseUrl,
            RequestFactory requestFn
    ) throws InterruptedException {

        try (var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {

            // Warmup
            if (config.warmupRequests > 0) {
                executeRequests(client, baseUrl, requestFn, config.warmupRequests, config.concurrency);
            }

            // Measured run
            var latencies = new AtomicLongArray(config.totalRequests);
            var errors = new AtomicInteger(0);
            var latch = new CountDownLatch(config.totalRequests);

            long startWall = System.nanoTime();

            // Use a semaphore-like approach: launch all in virtual threads but limit concurrency
            var semaphore = new java.util.concurrent.Semaphore(config.concurrency);

            IntStream.range(0, config.totalRequests).forEach(i -> {
                Thread.startVirtualThread(() -> {
                    try {
                        semaphore.acquire();
                        var req = requestFn.create(baseUrl, i);
                        long start = System.nanoTime();
                        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        long elapsed = System.nanoTime() - start;
                        latencies.set(i, elapsed);
                        if (resp.statusCode() >= 400) {
                            errors.incrementAndGet();
                        }
                    } catch (Exception e) {
                        latencies.set(i, 0);
                        errors.incrementAndGet();
                    } finally {
                        semaphore.release();
                        latch.countDown();
                    }
                });
            });

            latch.await();
            long wallClock = System.nanoTime() - startWall;

            long[] collected = new long[config.totalRequests];
            for (int i = 0; i < config.totalRequests; i++) {
                collected[i] = latencies.get(i);
            }

            return new Result(scenario, poolType, collected, errors.get(), wallClock);
        }
    }

    public static String formatReport(Result result) {
        return "\n" +
                "╔════════════════════════════════════════════════════════╗\n" +
                String.format("║  %-50s    ║%n", result.scenario()) +
                String.format("║  Pool: %-45s   ║%n", result.poolType()) +
                "╠════════════════════════════════════════════════════════╣\n" +
                String.format("║  Requests........: %-33d   ║%n", result.latenciesNanos().length) +
                String.format("║  Errors..........: %-33d   ║%n", result.errors()) +
                String.format("║  Throughput......: %-29.1f req/s ║%n", result.throughput()) +
                String.format("║  Avg latency.....: %-31.2f ms  ║%n", result.avgMs()) +
                String.format("║  P50.............: %-31.2f ms  ║%n", result.percentileMs(50)) +
                String.format("║  P95.............: %-31.2f ms  ║%n", result.percentileMs(95)) +
                String.format("║  P99.............: %-31.2f ms  ║%n", result.percentileMs(99)) +
                String.format("║  Wall clock......: %-31.2f  s  ║%n", result.wallClockNanos() / 1_000_000_000.0) +
                "╚════════════════════════════════════════════════════════╝\n";
    }

    @FunctionalInterface
    public interface RequestFactory {
        HttpRequest create(String baseUrl, int index);
    }

    private static void executeRequests(
            HttpClient client,
            String baseUrl,
            RequestFactory requestFn,
            int count,
            int concurrency
    ) throws InterruptedException {
        var latch = new CountDownLatch(count);
        var semaphore = new java.util.concurrent.Semaphore(concurrency);

        IntStream.range(0, count).forEach(i -> {
            Thread.startVirtualThread(() -> {
                try {
                    semaphore.acquire();
                    var req = requestFn.create(baseUrl, i);
                    client.send(req, HttpResponse.BodyHandlers.ofString());
                } catch (Exception ignored) {
                } finally {
                    semaphore.release();
                    latch.countDown();
                }
            });
        });
        latch.await();
    }
}
