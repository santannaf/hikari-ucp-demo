package santannaf.oracle.demo.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cenario C - Workload mista.
 * Leitura + escrita + transacoes curtas.
 * 70% leituras, 30% escritas — mais proximo do mundo real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("benchmark")
class ScenarioCMixedWorkloadTest {

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
    void mixedWorkload() throws InterruptedException {
        var config = new BenchmarkRunner.Config(8_000, 50, 500);

        var result = BenchmarkRunner.run(
                "Cenario C: Workload Mista (70% read / 30% write)",
                poolType,
                config,
                baseUrl,
                (base, i) -> {
                    if (i % 10 < 7) {
                        // 70% reads
                        return HttpRequest.newBuilder()
                                .uri(URI.create(base + "/posts/" + ((i % 1000) + 1)))
                                .GET()
                                .build();
                    } else {
                        // 30% writes
                        String json = """
                                {"title":"Bench Post %d","userId":%d,"body":"Benchmark write test %d"}"""
                                .formatted(i, (i % 50) + 1, i);
                        return HttpRequest.newBuilder()
                                .uri(URI.create(base + "/posts"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build();
                    }
                }
        );

        System.out.println(BenchmarkRunner.formatReport(result));
        BenchmarkResultStore.save(
                BenchmarkResultStore.fromRunnerResult(result),
                "C_mixed_" + poolType.toLowerCase().replaceAll("[^a-z]", ""));

        double errorRate = (double) result.errors() / config.totalRequests() * 100;
        assertTrue(errorRate < 5,
                "Taxa de erro deve ser < 5%%, foi " + String.format("%.1f%%", errorRate));
    }
}
