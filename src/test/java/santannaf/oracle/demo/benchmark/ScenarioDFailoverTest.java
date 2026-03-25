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
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cenario D - Falha e reconexao (ambos os nos).
 * Para AMBOS os containers Oracle (docker stop — fecha TCP),
 * envia requisicoes (que vao falhar), reinicia e mede o tempo de recuperacao.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("benchmark")
class ScenarioDFailoverTest {

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
    void failoverAndRecovery() throws Exception {
        System.out.println("\n=== Cenario D: Failover e Recuperacao (ambos nos) ===");
        System.out.println("Pool: " + poolType);

        // 1) Baseline
        System.out.println("\n[1/4] Medindo baseline...");
        var baselineResult = BenchmarkRunner.run(
                "Baseline",
                poolType,
                new BenchmarkRunner.Config(1_000, 20, 100),
                baseUrl,
                (base, i) -> HttpRequest.newBuilder()
                        .uri(URI.create(base + "/posts/count"))
                        .GET()
                        .build()
        );
        System.out.printf("  Baseline p50: %.2f ms%n", baselineResult.percentileMs(50));
        System.out.printf("  Baseline p99: %.2f ms%n", baselineResult.percentileMs(99));

        // 2) Parar AMBOS os nos (docker stop — fecha TCP)
        System.out.println("\n[2/4] Parando ambos os nos (docker stop)...");
        DockerControl.stop(DockerControl.NODE1);
        DockerControl.stop(DockerControl.NODE2);

        var failedRequests = new ArrayList<Long>();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            for (int i = 0; i < OUTAGE_SECONDS * 2; i++) {
                long start = System.nanoTime();
                try {
                    var req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/posts/count"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build();
                    var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() >= 500) {
                        failedRequests.add(System.nanoTime() - start);
                    }
                } catch (Exception e) {
                    failedRequests.add(System.nanoTime() - start);
                }
                TimeUnit.MILLISECONDS.sleep(PROBE_INTERVAL_MS);
            }
            System.out.println("  Requests durante queda: " + (OUTAGE_SECONDS * 2));
            System.out.println("  Falhas detectadas: " + failedRequests.size());
        }

        // 3) Reiniciar ambos
        System.out.println("\n[3/4] Reiniciando ambos os nos (docker start)...");
        DockerControl.start(DockerControl.NODE1);
        DockerControl.start(DockerControl.NODE2);
        System.out.printf("  Aguardando %ds para Oracle reiniciar...%n", NODE_RESTART_WAIT_SECONDS);
        TimeUnit.SECONDS.sleep(NODE_RESTART_WAIT_SECONDS);

        // 4) Medir tempo de recuperacao
        System.out.println("[4/4] Medindo tempo de recuperacao...");
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

        long recoveryTimeNanos = System.nanoTime() - recoveryStart;
        double recoveryTimeMs = recoveryTimeNanos / 1_000_000.0;

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

        System.out.println("\n╔═════════════════════════════════════════════════════════╗");
        System.out.println("║  Cenario D: Failover e Recuperacao (ambos nos)          ║");
        System.out.printf("║  Pool: %-45s    ║%n", poolType);
        System.out.println("╠═════════════════════════════════════════════════════════╣");
        System.out.printf("║  Recuperou?........: %-31s    ║%n", recovered ? "SIM" : "NAO");
        System.out.printf("║  Tempo recuperacao.: %-27.0f ms     ║%n", recoveryTimeMs);
        System.out.printf("║  Probes ate OK.....: %-33d  ║%n", probes);
        System.out.printf("║  Falhas na queda...: %-33d  ║%n", failedRequests.size());
        System.out.println("╠════════════════════════════════════════════════════════╣");
        System.out.printf("║  Baseline p50......: %-27.2f ms    ║%n", baselineResult.percentileMs(50));
        System.out.printf("║  Baseline p99......: %-27.2f ms    ║%n", baselineResult.percentileMs(99));
        System.out.printf("║  Pos-recup p50.....: %-27.2f ms    ║%n", postRecoveryResult.percentileMs(50));
        System.out.printf("║  Pos-recup p99.....: %-27.2f ms    ║%n", postRecoveryResult.percentileMs(99));
        System.out.println("╚════════════════════════════════════════════════════════╝");

        var stored = new BenchmarkResultStore.StoredResult(
                "Cenario D: Failover e Recuperacao",
                poolType,
                1000,
                failedRequests.size(),
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
                "D_failover_" + poolType.toLowerCase().replaceAll("[^a-z]", ""));

        assertTrue(recovered, "Pool deveria se recuperar apos reiniciar o banco");
    }
}
