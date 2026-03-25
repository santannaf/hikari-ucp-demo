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
 * Cenario B - Concorrencia alta.
 * Muitas threads disputando conexao simultaneamente.
 * Pool size = 10, concorrencia = 50, 100, 200.
 * Observar fila, tempo de aquisicao e estabilidade.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("benchmark")
class ScenarioBHighConcurrencyTest {

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
    void concurrency50() throws InterruptedException {
        runWithConcurrency(50);
    }

    @Test
    void concurrency100() throws InterruptedException {
        runWithConcurrency(100);
    }

    @Test
    void concurrency200() throws InterruptedException {
        runWithConcurrency(200);
    }

    private void runWithConcurrency(int concurrency) throws InterruptedException {
        var config = new BenchmarkRunner.Config(10_000, concurrency, 500);

        var result = BenchmarkRunner.run(
                "Cenario B: Alta Concorrencia (" + concurrency + " threads)",
                poolType,
                config,
                baseUrl,
                (base, i) -> HttpRequest.newBuilder()
                        .uri(URI.create(base + "/posts/search?userId=" + ((i % 50) + 1)))
                        .GET()
                        .build()
        );

        System.out.println(BenchmarkRunner.formatReport(result));
        BenchmarkResultStore.save(
                BenchmarkResultStore.fromRunnerResult(result),
                "B_concurrency_" + concurrency + "_" + poolType.toLowerCase().replaceAll("[^a-z]", ""));

        double errorRate = (double) result.errors() / config.totalRequests() * 100;
        assertTrue(errorRate < 5,
                "Taxa de erro deve ser < 5%%, foi " + String.format("%.1f%%", errorRate));
    }
}
