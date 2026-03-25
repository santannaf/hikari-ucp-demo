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
 * Cenario E - Failover multi-node.
 * Node1 e parado (docker stop), mede o gap ate o trafego migrar para node2.
 * Depois reinicia node1 (docker start) e mede o failback.
 *
 * Usa docker stop (nao pause!) para que as conexoes TCP sejam fechadas (RST).
 * Com TCP fechado, o pool detecta conexao quebrada imediatamente no proximo uso.
 * UCP com validate-on-borrow descarta e reconecta no node2 via ADDRESS_LIST.
 * HikariCP tenta usar a conexao stale, recebe erro, e so entao tenta reconectar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("benchmark")
class ScenarioEMultiNodeFailoverTest {

    private static final int PROBE_INTERVAL_MS = 300;
    private static final int MAX_FAILOVER_PROBES = 60;
    private static final int NODE1_RESTART_WAIT_SECONDS = 30;

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
    void failoverToSecondNode() throws Exception {
        System.out.println("\n=== Cenario E: Failover Multi-Node (node1 -> node2) ===");
        System.out.println("Pool: " + poolType);

        // 1) Baseline com ambos os nos ativos
        System.out.println("\n[1/5] Medindo baseline (ambos nos ativos)...");
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
        System.out.printf("  Baseline p99: %.2f ms%n", baselineResult.percentileMs(99));

        // 2) Parar node1 (docker stop — fecha TCP, pool detecta imediatamente)
        System.out.println("\n[2/5] Parando node1 (docker stop) — medindo gap de failover...");
        DockerControl.stop(DockerControl.NODE1);

        long failoverStart = System.nanoTime();
        int failedDuringFailover = 0;
        boolean failedOver = false;

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (int i = 0; i < MAX_FAILOVER_PROBES; i++) {
                try {
                    var req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/posts/count"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build();
                    var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        failedOver = true;
                        break;
                    } else {
                        failedDuringFailover++;
                    }
                } catch (Exception e) {
                    failedDuringFailover++;
                }
                TimeUnit.MILLISECONDS.sleep(PROBE_INTERVAL_MS);
            }
        }

        double failoverGapMs = (System.nanoTime() - failoverStart) / 1_000_000.0;
        System.out.printf("  Failover gap: %.0f ms (%d erros durante transicao)%n", failoverGapMs, failedDuringFailover);

        // 3) Trafego sustentado no node2 (node1 ainda parado)
        System.out.println("\n[3/5] Benchmark com node1 parado (trafego no node2)...");
        var node2Result = BenchmarkRunner.run(
                "Trafego no Node2",
                poolType,
                new BenchmarkRunner.Config(1_000, 20, 100),
                baseUrl,
                (base, i) -> HttpRequest.newBuilder()
                        .uri(URI.create(base + "/posts/count"))
                        .GET()
                        .build()
        );
        System.out.printf("  Node2 throughput: %.1f req/s%n", node2Result.throughput());

        // 4) Reiniciar node1 e medir failback
        System.out.println("\n[4/5] Reiniciando node1 (docker start)...");
        DockerControl.start(DockerControl.NODE1);
        System.out.printf("  Aguardando %ds para Oracle reiniciar...%n", NODE1_RESTART_WAIT_SECONDS);
        TimeUnit.SECONDS.sleep(NODE1_RESTART_WAIT_SECONDS);

        var failbackResult = BenchmarkRunner.run(
                "Pos-Failback",
                poolType,
                new BenchmarkRunner.Config(1_000, 20, 100),
                baseUrl,
                (base, i) -> HttpRequest.newBuilder()
                        .uri(URI.create(base + "/posts/count"))
                        .GET()
                        .build()
        );

        // 5) Report
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Cenario E: Failover Multi-Node (node1 -> node2)             ║");
        System.out.printf("║  Pool: %-50s    ║%n", poolType);
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Failover gap........: %-27.0f ms       ║%n", failoverGapMs);
        System.out.printf("║  Erros na transicao..: %-33d    ║%n", failedDuringFailover);
        System.out.printf("║  Baseline throughput.: %-25.1f req/s    ║%n", baselineResult.throughput());
        System.out.printf("║  Node2 throughput....: %-25.1f req/s    ║%n", node2Result.throughput());
        System.out.printf("║  Failback throughput.: %-25.1f req/s    ║%n", failbackResult.throughput());
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Baseline p99........: %-27.2f ms       ║%n", baselineResult.percentileMs(99));
        System.out.printf("║  Node2 p99...........: %-27.2f ms       ║%n", node2Result.percentileMs(99));
        System.out.printf("║  Failback p99........: %-27.2f ms       ║%n", failbackResult.percentileMs(99));
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // Persist
        var stored = new BenchmarkResultStore.StoredResult(
                "Cenario E: Failover Multi-Node",
                poolType,
                1000,
                failedDuringFailover,
                baselineResult.throughput(),
                baselineResult.avgMs(),
                baselineResult.percentileMs(50),
                baselineResult.percentileMs(95),
                baselineResult.percentileMs(99),
                baselineResult.wallClockNanos() / 1_000_000_000.0,
                failoverGapMs,
                baselineResult.percentileMs(50),
                baselineResult.percentileMs(99),
                node2Result.percentileMs(50),
                node2Result.percentileMs(99)
        );
        BenchmarkResultStore.save(stored,
                "E_multinode_failover_" + poolType.toLowerCase().replaceAll("[^a-z]", ""));

        assertTrue(failedOver, "Pool deveria fazer failover para node2");
    }
}
