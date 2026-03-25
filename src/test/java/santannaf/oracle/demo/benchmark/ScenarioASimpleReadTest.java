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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cenario A - Leitura simples.
 * Query curta e repetitiva para medir overhead puro do pool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("benchmark")
class ScenarioASimpleReadTest {

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
    void simpleReadById() throws InterruptedException {
        var config = new BenchmarkRunner.Config(5_000, 20, 500);

        var result = BenchmarkRunner.run(
                "Cenário A: Leitura Simples (GET /posts/{id})",
                poolType,
                config,
                baseUrl,
                (base, i) -> HttpRequest.newBuilder()
                        .uri(URI.create(base + "/posts/" + ((i % 1000) + 1)))
                        .GET()
                        .build()
        );

        System.out.println(BenchmarkRunner.formatReport(result));
        BenchmarkResultStore.save(
                BenchmarkResultStore.fromRunnerResult(result),
                "A1_read_by_id_" + poolType.toLowerCase().replaceAll("[^a-z]", ""));

        assertEquals(0, result.errors(), "Nenhum erro esperado em leitura simples");
        assertTrue(result.throughput() > 0, "Throughput deve ser > 0");
    }

    @Test
    void simpleReadCount() throws InterruptedException {
        var config = new BenchmarkRunner.Config(3_000, 20, 300);

        var result = BenchmarkRunner.run(
                "Cenario A: Count Simples (GET /posts/count)",
                poolType,
                config,
                baseUrl,
                (base, i) -> HttpRequest.newBuilder()
                        .uri(URI.create(base + "/posts/count"))
                        .GET()
                        .build()
        );

        System.out.println(BenchmarkRunner.formatReport(result));
        BenchmarkResultStore.save(
                BenchmarkResultStore.fromRunnerResult(result),
                "A2_count_" + poolType.toLowerCase().replaceAll("[^a-z]", ""));

        assertEquals(0, result.errors(), "Nenhum erro esperado em count");
    }
}
