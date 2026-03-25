package santannaf.oracle.demo.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cenario F - Queda total e recuperacao parcial.
 * Ambos os nos sao parados (docker stop — fecha TCP),
 * depois apenas node2 e reiniciado. Mede o tempo de recuperacao.
 *
 * Usa docker stop/start (nao pause/unpause) para fechar conexoes TCP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("benchmark")
class ScenarioFTotalOutageTest {

    private static final int OUTAGE_SECONDS = 5;
    private static final int PROBE_INTERVAL_MS = 300;
    private static final int MAX_RECOVERY_PROBES = 60;
    private static final int NODE_RESTART_WAIT_SECONDS = 30;

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    String baseUrl;
    String poolType;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        poolType = PoolDetector.detect(dataSource);
    }

    @Test
    void totalOutageAndPartialRecovery() throws Exception {
        System.out.println("\n=== Cenario F: Queda Total + Recuperacao Parcial ===");
        System.out.println("Pool: " + poolType);

        // 1) Baseline
        System.out.println("\n[1/5] Medindo baseline...");
        var baselineResult = BenchmarkRunner.run(
                "Baseline",
                poolType,
                new BenchmarkRunner.Config(1_000, 20, 200),
                baseUrl,
                (base, i) -> HttpRequest.newBuilder()
                        .uri(URI.create(base + "/posts/count"))
                        .GET()
                        .build()
        );
        System.out.printf("  Baseline throughput: %.1f req/s%n", baselineResult.throughput());

        // 2) Parar AMBOS os nos (docker stop — fecha TCP)
        System.out.println("\n[2/5] Parando ambos os nos (docker stop)...");
        DockerControl.stop(DockerControl.NODE1);
        DockerControl.stop(DockerControl.NODE2);

        int failedDuringOutage = 0;
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            for (int i = 0; i < OUTAGE_SECONDS * 2; i++) {
                try {
                    var req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/posts/count"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build();
                    var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() >= 500) failedDuringOutage++;
                } catch (Exception e) {
                    failedDuringOutage++;
                }
                TimeUnit.MILLISECONDS.sleep(PROBE_INTERVAL_MS);
            }
        }
        System.out.println("  Falhas durante queda total: " + failedDuringOutage);

        // 3) Reiniciar apenas node2
        System.out.println("\n[3/5] Reiniciando apenas node2 (docker start)...");
        DockerControl.start(DockerControl.NODE2);
        System.out.printf("  Aguardando %ds para Oracle reiniciar...%n", NODE_RESTART_WAIT_SECONDS);
        TimeUnit.SECONDS.sleep(NODE_RESTART_WAIT_SECONDS);

        long recoveryStart = System.nanoTime();
        int probes = 0;
        boolean recovered = false;

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (int i = 0; i < MAX_RECOVERY_PROBES; i++) {
                probes++;
                try {
                    var req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/posts/count"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build();
                    var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        recovered = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
                TimeUnit.MILLISECONDS.sleep(PROBE_INTERVAL_MS);
            }
        }

        double recoveryTimeMs = (System.nanoTime() - recoveryStart) / 1_000_000.0;

        // 4) Benchmark pos-recuperacao (apenas node2 ativo)
        System.out.println("\n[4/5] Benchmark pos-recuperacao (apenas node2)...");
        var postRecoveryResult = BenchmarkRunner.run(
                "Pos-Recuperacao",
                poolType,
                new BenchmarkRunner.Config(1_000, 20, 100),
                baseUrl,
                (base, i) -> HttpRequest.newBuilder()
                        .uri(URI.create(base + "/posts/count"))
                        .GET()
                        .build()
        );

        // 5) Restaurar node1 (cleanup)
        System.out.println("\n[5/5] Restaurando node1...");
        DockerControl.start(DockerControl.NODE1);
        TimeUnit.SECONDS.sleep(NODE_RESTART_WAIT_SECONDS);

        // Report
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Cenario F: Queda Total + Recuperacao Parcial                ║");
        System.out.printf("║  Pool: %-50s    ║%n", poolType);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Recuperou?...........: %-27s         ║%n", recovered ? "SIM" : "NAO");
        System.out.printf("║  Tempo recuperacao....: %-27.0f ms      ║%n", recoveryTimeMs);
        System.out.printf("║  Probes ate OK........: %-33d    ║%n", probes);
        System.out.printf("║  Falhas durante queda.: %-33d    ║%n", failedDuringOutage);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Baseline throughput..: %-25.1f req/s    ║%n", baselineResult.throughput());
        System.out.printf("║  Pos-recup throughput.: %-25.1f req/s    ║%n", postRecoveryResult.throughput());
        System.out.printf("║  Baseline p99.........: %-27.2f ms       ║%n", baselineResult.percentileMs(99));
        System.out.printf("║  Pos-recup p99........: %-27.2f ms       ║%n", postRecoveryResult.percentileMs(99));
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // Persist
        var stored = new BenchmarkResultStore.StoredResult(
                "Cenario F: Queda Total + Recuperacao",
                poolType,
                1000,
                failedDuringOutage,
                baselineResult.throughput(),
                baselineResult.avgMs(),
                baselineResult.percentileMs(50),
                baselineResult.percentileMs(95),
                baselineResult.percentileMs(99),
                baselineResult.wallClockNanos() / 1_000_000_000.0,
                recoveryTimeMs,
                baselineResult.percentileMs(50),
                baselineResult.percentileMs(99),
                postRecoveryResult.percentileMs(50),
                postRecoveryResult.percentileMs(99)
        );
        BenchmarkResultStore.save(stored,
                "F_total_outage_" + poolType.toLowerCase().replaceAll("[^a-z]", ""));

        assertTrue(recovered, "Pool deveria se recuperar apos trazer node2 de volta");
    }
}
