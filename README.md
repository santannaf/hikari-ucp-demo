# HikariCP vs Oracle UCP - Benchmark Comparativo

Projeto de benchmark comparativo entre **HikariCP** e **Oracle UCP (Universal Connection Pool)** rodando sobre **Oracle 23ai Free** com **Spring Boot 4.0.4** e **Java 25 (Virtual Threads)**.

## Stack

| Componente        | Versao                             |
|-------------------|------------------------------------|
| Java              | 25 (Virtual Threads habilitadas)   |
| Spring Boot       | 4.0.4                              |
| Banco de dados    | Oracle 23ai Free (2 instancias)    |
| HikariCP          | Default do Spring Boot             |
| Oracle UCP        | ucp11 (ojdbc11)                    |
| Build             | Gradle 9.4                         |

## Arquitetura do Teste

```
                        +-----------------+
                        |  Benchmark      |
                        |  Runner         |
                        |  (Virtual       |
                        |   Threads)      |
                        +--------+--------+
                                 |
                          HTTP requests
                                 |
                        +--------v--------+
                        |  Spring Boot    |
                        |  App            |
                        |  (PostController|
                        |   + JDBC)       |
                        +--------+--------+
                                 |
                     +-----------+-----------+
                     |                       |
              +------v------+         +------v------+
              | oracle-node1|         | oracle-node2|
              | :1521       |         | :1522       |
              +-------------+         +-------------+
```

Ambos os pools usam a mesma **ADDRESS_LIST** com `FAILOVER=ON`, apontando para as duas instancias Oracle. O pool size e fixo em **10 conexoes** para ambos, garantindo comparacao justa.

## Cenarios de Benchmark

### Cenario A - Leitura Simples

Mede o overhead puro do pool em operacoes triviais.

| Sub-cenario | Endpoint           | Requests | Threads | Warmup |
|-------------|--------------------|----------|---------|--------|
| A1          | `GET /posts/{id}`  | 5.000    | 20      | 500    |
| A2          | `GET /posts/count` | 3.000    | 20      | 300    |

### Cenario B - Alta Concorrencia

Testa degradacao de throughput e tempos de fila sob pressao crescente.

| Nivel | Threads | Requests | Endpoint                        |
|-------|---------|----------|---------------------------------|
| B-50  | 50      | 10.000   | `GET /posts/search?userId={n}`  |
| B-100 | 100     | 10.000   | `GET /posts/search?userId={n}`  |
| B-200 | 200     | 10.000   | `GET /posts/search?userId={n}`  |

### Cenario C - Workload Mista

Simula carga real com mix de leitura e escrita.

- **70% GET** (`/posts/{id}`) + **30% POST** (`/posts`)
- 8.000 requests, 50 threads, 500 warmup

### Cenario D - Failover e Recuperacao

Para **ambos** os nodes Oracle via `docker stop` (fecha TCP com RST), envia requests durante a queda, reinicia ambos e mede tempo de recuperacao.

### Cenario E - Failover Multi-Node

Para apenas o **node1**, mede o gap de failover ate o trafego rotear para o node2, e depois testa o failback quando o node1 retorna.

### Cenario F - Queda Total + Recuperacao Parcial

Para ambos os nodes, reinicia apenas o **node2** e mede a recuperacao operando com um unico node.

## Resultados

### Cenarios A-C (Performance em operacao normal)

| Cenario       | Pool       | Throughput (req/s) | Avg (ms)  | P50 (ms)  | P95 (ms)  | P99 (ms)   | Erros |
|---------------|------------|--------------------|-----------|-----------|-----------|------------|-------|
| A1 Read by ID | HikariCP   | **4.072**          | **4,83**  | **4,36**  | **8,42**  | **12,35**  | 0     |
| A1 Read by ID | Oracle UCP | 3.142              | 6,30      | 5,85      | 10,18     | 15,05      | 0     |
| A2 Count      | HikariCP   | **7.506**          | **2,51**  | **2,22**  | **4,57**  | **9,11**   | 0     |
| A2 Count      | Oracle UCP | 5.193              | 3,82      | 3,58      | 6,28      | 9,87       | 0     |
| B 50 threads  | HikariCP   | **4.220**          | **11,81** | **11,11** | **18,76** | **22,93**  | 0     |
| B 50 threads  | Oracle UCP | 3.052              | 16,34     | 16,20     | 22,40     | 29,27      | 0     |
| B 100 threads | HikariCP   | **3.176**          | **31,34** | **30,01** | **56,00** | 76,09      | 0     |
| B 100 threads | Oracle UCP | 2.205              | 45,15     | 44,21     | 62,59     | 80,32      | 0     |
| B 200 threads | HikariCP   | **2.842**          | **69,85** | 58,90     | 140,29    | 212,69     | 0     |
| B 200 threads | Oracle UCP | 2.488              | 79,73     | 76,11     | 141,31    | **178,86** | 0     |
| C Mixed 70/30 | HikariCP   | **4.987**          | **9,98**  | **8,74**  | 16,84     | 24,82      | 0     |
| C Mixed 70/30 | Oracle UCP | 4.560              | 10,92     | 10,71     | **15,79** | **22,73**  | 0     |

> **Valores em negrito** indicam o vencedor da métrica naquele cenario.

### Cenarios D-F (Resiliencia e Failover)

| Cenario              | Pool       | Recovery (ms) | Erros | P99 Baseline (ms)   | P99 Pos-Recovery (ms) |
|----------------------|------------|---------------|-------|---------------------|-----------------------|
| D Failover           | HikariCP   | **66,79**     | 10    | **3,65**            | **11,32**             |
| D Failover           | Oracle UCP | 77,28         | 10    | 4,27                | 15,92                 |
| E Multi-Node         | HikariCP   | 3,99          | 0     | **3,72**            | **10,17**             |
| E Multi-Node         | Oracle UCP | **3,85**      | 0     | 4,71                | 14,66                 |
| F Queda Total        | HikariCP   | 65,37         | 10    | 10,30               | 12,77                 |
| F Queda Total        | Oracle UCP | **49,67**     | 10    | **4,10**            | 17,26                 |

### Analise dos Resultados

**HikariCP vence em throughput puro** (20-30% mais rapido em leituras simples) devido ao overhead minimo do pool. Nos cenarios A, B e C, o HikariCP consistentemente entrega mais requisicoes por segundo.

**Oracle UCP se destaca em cenarios criticos:**

- **200 threads (B-200):** UCP tem P99 mais estavel (178ms vs 212ms) - melhor previsibilidade sob carga extrema
- **Mixed workload (C):** UCP vence em P95 e P99 - tail latency mais controlada com mix de read/write
- **Queda total (F):** UCP recupera **24% mais rapido** (49ms vs 65ms) e tem P99 baseline muito melhor (4,1ms vs 10,3ms)

### Conclusao

| Criterio                       | Vencedor    | Margem                 |
|--------------------------------|-------------|------------------------|
| Throughput (reads simples)     | HikariCP    | ~30% mais rapido       |
| Throughput (alta concorrencia) | HikariCP    | ~15-30% mais rapido    |
| Tail latency (carga extrema)   | Oracle UCP  | P99 ~16% menor (200t)  |
| Recovery (queda total)         | Oracle UCP  | ~24% mais rapido       |
| Failover multi-node            | Empate      | ~igual                 |

**Em producao com Oracle RAC + ONS real**, o UCP teria vantagem ainda maior com FAN events, Application Continuity e Runtime Load Balancing — features nao disponíveis no setup de teste com Oracle Free.

## Como Executar

### Pre-requisitos

- Docker (para as instancias Oracle 23ai)
- Java 25+
- Gradle 9.4+

### Execucao completa (recomendado)

```bash
# Limpa containers anteriores, sobe 2 nodes Oracle e executa ambos os benchmarks
./start.sh
```

### Execucao manual

```bash
# 1. Subir o ambiente Oracle
docker compose up -d

# 2. Aguardar inicializacao (~30s)
sleep 30

# 3. Executar benchmark HikariCP
./gradlew benchmarkHikari

# 4. Executar benchmark Oracle UCP
./gradlew benchmarkUcp

# 5. Gerar relatorio HTML comparativo
./gradlew benchmarkReport
```

### Relatorio

O relatorio HTML interativo com graficos Chart.js e gerado automaticamente em:

```
benchmark-results/report.html
```

Abra no navegador para visualizar os graficos comparativos de throughput, latencia, percentis e recovery time.

## Estrutura do Projeto

```
src/
  main/java/.../
    config/UcpReadTimeoutConfig.java  # Read timeout para UCP (detecta conexoes congeladas)
    domain/Post.java                  # Entidade Post (id, title, userId, body)
    domain/PostRepository.java        # Repository com queries customizadas
    web/PostController.java           # REST API: GET/POST /posts, /posts/count, /posts/search
  main/resources/
    application.properties            # Config comum (virtual threads, actuator)
    application-hikari.properties     # Config HikariCP (pool=10, timeout=3s)
    application-ucp.properties        # Config Oracle UCP (pool=10, validate-on-borrow)
  test/java/.../benchmark/
    BenchmarkRunner.java              # Engine: virtual threads + semaphore + percentil calc
    BenchmarkResultStore.java         # Persistencia JSON dos resultados
    DockerControl.java                # Controle docker stop/start para failover
    PoolDetector.java                 # Detecta tipo do pool ativo
    ScenarioASimpleReadTest.java      # Cenario A: leitura simples
    ScenarioBHighConcurrencyTest.java # Cenario B: alta concorrencia
    ScenarioCMixedWorkloadTest.java   # Cenario C: workload mista
    ScenarioDFailoverTest.java        # Cenario D: failover ambos nodes
    ScenarioEMultiNodeFailoverTest.java # Cenario E: failover multi-node
    ScenarioFTotalOutageTest.java     # Cenario F: queda total + recuperacao parcial
docker-compose.yaml                   # 2x Oracle 23ai Free (node1:1521, node2:1522)
benchmark-results/                    # JSONs + report.html gerado
```
