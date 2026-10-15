package com.picturesAI.generation_service.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.generation_service.GenerationServiceApplication;
import io.minio.MinioClient;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = GenerationServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.root=INFO",
                "logging.level.com.picturesAI=DEBUG",
                "logging.level.org.apache.kafka.clients.consumer=INFO",
                "logging.level.org.apache.kafka.clients.producer=INFO",
                "logging.level.org.apache.kafka.clients.NetworkClient=WARN",
                "logging.level.org.apache.kafka.clients.Metadata=WARN"
        })
@Import(E2EConfig.class)
@ActiveProfiles("e2e")
@Testcontainers
class E2EStackIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("generations_db")
            .withUsername("picturesAI")
            .withPassword("picturesAI");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka-native:3.9.0"))
            .withEnv("KAFKA_LISTENERS",
                    "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    static GenericContainer<?> minio = new GenericContainer<>("pgsty/minio:latest")
            .withEnv("MINIO_ROOT_USER", "picturesAI")
            .withEnv("MINIO_ROOT_PASSWORD", "picturesAI")
            .withCommand("server", "/data")
            .withExposedPorts(9000);

    @Container
    static GenericContainer<?> natsContainer = new GenericContainer<>("nats:2.10-alpine")
            .withExposedPorts(4222);

    static Connection natsConnection;
    static Dispatcher natsResponder;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("minio.endpoint",
                () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("nats.url",
                () -> "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222));
    }

    @BeforeAll
    static void setUpInfra() throws Exception {
        createTopics();
        startNatsResponder();
    }

    static void createTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic("generation.requested.pro", 1, (short) 1),
                    new NewTopic("generation.requested.free", 1, (short) 1),
                    new NewTopic("generation.requested.dlq", 1, (short) 1),
                    new NewTopic("generation.completed", 1, (short) 1),
                    new NewTopic("generation.failed", 1, (short) 1),
                    new NewTopic("generation.cancelled", 1, (short) 1),
                    new NewTopic("user.deleted", 1, (short) 1)
            )).all().get();
        }
    }

    static void startNatsResponder() throws Exception {
        String url = "nats://" + natsContainer.getHost() + ":" + natsContainer.getMappedPort(4222);
        natsConnection = Nats.connect(new Options.Builder()
                .server(url)
                .connectionTimeout(Duration.ofSeconds(5))
                .build());
        natsResponder = natsConnection.createDispatcher(msg -> {
            String reply = msg.getReplyTo();
            if (reply == null) {
                return;
            }
            natsConnection.publish(reply,
                    "{\"allowed\":true}".getBytes(StandardCharsets.UTF_8));
        });
        natsResponder.subscribe("billing.reserveQuota");
        natsResponder.subscribe("billing.confirmQuota");
        natsResponder.subscribe("billing.releaseQuota");
        natsConnection.flush(Duration.ofSeconds(2));
    }

    @AfterAll
    static void tearDownInfra() {
        if (natsResponder != null) {
            natsResponder.unsubscribe("billing.reserveQuota");
            natsResponder.unsubscribe("billing.confirmQuota");
            natsResponder.unsubscribe("billing.releaseQuota");
        }
        if (natsConnection != null) {
            try {
                natsConnection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MinioClient minioClient;

    @Test
    void postThenGetReachesCompleted() throws Exception {
        String user = UUID.randomUUID().toString();
        UUID jobId = post(user, "e2e-complete", 1);

        dumpOutboxAfter("after POST");
        Thread.sleep(3000);
        dumpOutboxAfter("after 3s");

        JsonNode completed = awaitStatus(user, jobId, "COMPLETED", 60000);
        assertThat(completed.path("imageUrl").asText()).startsWith("http");
        assertThat(completed.path("thumbnailUrl").asText()).startsWith("http");
    }

    @Test
    void cancelQueuedNeverProcesses() throws Exception {
        String user = UUID.randomUUID().toString();
        UUID jobId = post(user, "e2e-cancel", 1);

        HttpHeaders headers = headers(user);
        ResponseEntity<String> del = rest.exchange(
                "/api/v1/generations/" + jobId, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
        assertThat(del.getStatusCode().value()).isIn(202, 409);

        JsonNode status = awaitStatus(user, jobId, "CANCELLED", 10000);
        assertThat(status.path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void batchCreatesThreeJobsWithSameBatchId() throws Exception {
        String user = UUID.randomUUID().toString();
        String idem = "e2e-batch-" + UUID.randomUUID();
        String body = """
                {"prompt":"a cat","model":"sd-xl","size":"512x512","count":3}
                """;
        HttpHeaders headers = headers(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idem);

        ResponseEntity<String> resp = rest.postForEntity(
                "/api/v1/generations", new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(202);

        JsonNode node = mapper.readTree(resp.getBody());
        assertThat(node.path("batchId").asText()).isNotBlank();
        assertThat(node.path("count").asInt()).isEqualTo(3);

        for (int i = 0; i < 3; i++) {
            awaitStatus(user, UUID.fromString(node.path("jobId").asText()), "COMPLETED", 60000);
        }
    }

    private void dumpOutboxAfter(String tag) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT event_type, published_at FROM outbox ORDER BY created_at");
            System.out.println("=== OUTBOX " + tag + ": " + rows.size() + " rows ===");
            rows.forEach(r -> System.out.println("===   " + r + " ==="));
        } catch (Exception e) {
            System.out.println("=== OUTBOX " + tag + " FAILED: " + e + " ===");
        }
    }

    private UUID post(String user, String idemPrefix, int count) throws Exception {
        String idem = idemPrefix + "-" + UUID.randomUUID();
        String body = String.format(
                "{\"prompt\":\"a cat\",\"model\":\"sd-xl\",\"size\":\"512x512\",\"count\":%d}", count);
        HttpHeaders headers = headers(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idem);

        ResponseEntity<String> resp = rest.postForEntity(
                "/api/v1/generations", new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        return UUID.fromString(mapper.readTree(resp.getBody()).path("jobId").asText());
    }

    private JsonNode awaitStatus(String user, UUID jobId, String expected, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpHeaders headers = headers(user);
            ResponseEntity<String> resp = rest.exchange(
                    "/api/v1/generations/" + jobId + "/status",
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                last = mapper.readTree(resp.getBody());
                if (expected.equals(last.path("status").asText())) {
                    return last;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Did not reach status " + expected + " within " + timeoutMs
                + "ms, last = " + (last == null ? "null" : last.toString()));
    }

    private HttpHeaders headers(String userId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", userId);
        h.set("X-User-Roles", "ROLE_USER");
        h.set("X-User-Name", "e2e");
        return h;
    }
}