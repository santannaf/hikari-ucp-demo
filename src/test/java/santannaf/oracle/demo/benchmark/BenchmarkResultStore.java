package santannaf.oracle.demo.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Persists benchmark results as JSON in build/benchmark-results/.
 * No external library — hand-written JSON for this simple flat structure.
 */
public final class BenchmarkResultStore {

    private static final Path RESULTS_DIR = Path.of("build", "benchmark-results");

    private BenchmarkResultStore() {}

    public record StoredResult(
            String scenario,
            String pool,
            int totalRequests,
            int errors,
            double throughput,
            double avgMs,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double wallClockSeconds,
            // Scenario D extras (optional)
            Double recoveryTimeMs,
            Double baselineP50Ms,
            Double baselineP99Ms,
            Double postRecoveryP50Ms,
            Double postRecoveryP99Ms
    ) {}

    public static StoredResult fromRunnerResult(BenchmarkRunner.Result result) {
        return new StoredResult(
                result.scenario(),
                result.poolType(),
                result.latenciesNanos().length,
                result.errors(),
                result.throughput(),
                result.avgMs(),
                result.percentileMs(50),
                result.percentileMs(95),
                result.percentileMs(99),
                result.wallClockNanos() / 1_000_000_000.0,
                null, null, null, null, null
        );
    }

    public static void save(StoredResult result, String fileKey) {
        try {
            Files.createDirectories(RESULTS_DIR);
            String json = toJson(result);
            Files.writeString(RESULTS_DIR.resolve(fileKey + ".json"), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<StoredResult> loadAll() {
        if (!Files.isDirectory(RESULTS_DIR)) {
            return List.of();
        }
        var results = new ArrayList<StoredResult>();
        try (Stream<Path> files = Files.list(RESULTS_DIR)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            results.add(fromJson(Files.readString(p)));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return results;
    }

    private static String toJson(StoredResult r) {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"scenario\": ").append(jsonString(r.scenario())).append(",\n");
        sb.append("  \"pool\": ").append(jsonString(r.pool())).append(",\n");
        sb.append("  \"totalRequests\": ").append(r.totalRequests()).append(",\n");
        sb.append("  \"errors\": ").append(r.errors()).append(",\n");
        sb.append("  \"throughput\": ").append(r.throughput()).append(",\n");
        sb.append("  \"avgMs\": ").append(r.avgMs()).append(",\n");
        sb.append("  \"p50Ms\": ").append(r.p50Ms()).append(",\n");
        sb.append("  \"p95Ms\": ").append(r.p95Ms()).append(",\n");
        sb.append("  \"p99Ms\": ").append(r.p99Ms()).append(",\n");
        sb.append("  \"wallClockSeconds\": ").append(r.wallClockSeconds());
        if (r.recoveryTimeMs() != null) {
            sb.append(",\n  \"recoveryTimeMs\": ").append(r.recoveryTimeMs());
            sb.append(",\n  \"baselineP50Ms\": ").append(r.baselineP50Ms());
            sb.append(",\n  \"baselineP99Ms\": ").append(r.baselineP99Ms());
            sb.append(",\n  \"postRecoveryP50Ms\": ").append(r.postRecoveryP50Ms());
            sb.append(",\n  \"postRecoveryP99Ms\": ").append(r.postRecoveryP99Ms());
        }
        sb.append("\n}");
        return sb.toString();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static StoredResult fromJson(String json) {
        String scenario = extractString(json, "scenario");
        String pool = extractString(json, "pool");
        int totalRequests = (int) extractNumber(json, "totalRequests");
        int errors = (int) extractNumber(json, "errors");
        double throughput = extractNumber(json, "throughput");
        double avgMs = extractNumber(json, "avgMs");
        double p50Ms = extractNumber(json, "p50Ms");
        double p95Ms = extractNumber(json, "p95Ms");
        double p99Ms = extractNumber(json, "p99Ms");
        double wallClockSeconds = extractNumber(json, "wallClockSeconds");
        Double recoveryTimeMs = extractNullableNumber(json, "recoveryTimeMs");
        Double baselineP50Ms = extractNullableNumber(json, "baselineP50Ms");
        Double baselineP99Ms = extractNullableNumber(json, "baselineP99Ms");
        Double postRecoveryP50Ms = extractNullableNumber(json, "postRecoveryP50Ms");
        Double postRecoveryP99Ms = extractNullableNumber(json, "postRecoveryP99Ms");

        return new StoredResult(scenario, pool, totalRequests, errors, throughput,
                avgMs, p50Ms, p95Ms, p99Ms, wallClockSeconds,
                recoveryTimeMs, baselineP50Ms, baselineP99Ms, postRecoveryP50Ms, postRecoveryP99Ms);
    }

    private static String extractString(String json, String key) {
        String prefix = "\"" + key + "\": \"";
        int start = json.indexOf(prefix);
        if (start < 0) return "";
        start += prefix.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private static double extractNumber(String json, String key) {
        String prefix = "\"" + key + "\": ";
        int start = json.indexOf(prefix);
        if (start < 0) return 0;
        start += prefix.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == '-' || json.charAt(end) == 'E')) {
            end++;
        }
        return Double.parseDouble(json.substring(start, end));
    }

    private static Double extractNullableNumber(String json, String key) {
        if (!json.contains("\"" + key + "\"")) return null;
        return extractNumber(json, key);
    }
}
