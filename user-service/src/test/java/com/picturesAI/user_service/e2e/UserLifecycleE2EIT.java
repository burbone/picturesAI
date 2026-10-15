package com.picturesAI.user_service.e2e;

import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.domain.Status;
import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.dto.UpdateUserRequest;
import com.picturesAI.user_service.dto.UserResponse;
import com.picturesAI.user_service.deletion.UserDeletionWorker;
import com.picturesAI.user_service.keycloak.KeycloakAdminClient;
import com.picturesAI.user_service.outbox.OutboxPublisher;
import com.picturesAI.user_service.repository.OutboxRepository;
import com.picturesAI.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserLifecycleE2EIT {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("users_db")
                    .withUsername("picturesai")
                    .withPassword("picturesAI");

    public static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("outbox.publisher.enabled", () -> "true");
        registry.add("outbox.publisher.initial-delay-ms", () -> "3600000");
        registry.add("outbox.publisher.poll-interval-ms", () -> "3600000");
        registry.add("user.deletion.enabled", () -> "true");
        registry.add("user.deletion.initial-delay-ms", () -> "3600000");
        registry.add("user.deletion.poll-interval-ms", () -> "3600000");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private UserDeletionWorker userDeletionWorker;

    @MockitoBean
    private KeycloakAdminClient keycloakAdminClient;

    private UUID keycloakId;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        userRepository.deleteAll();
        keycloakId = UUID.randomUUID();
        headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", keycloakId.toString());
        headers.set("X-User-Name", "alice");
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, Object body, Class<T> type) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    @Test
    void fullLifecycle() {
        // 1. GET /me → lazy creation
        ResponseEntity<UserResponse> created = call(HttpMethod.GET, "/api/v1/users/me", null, UserResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).isNotNull();
        UserResponse profile = created.getBody();
        assertThat(profile.username()).isEqualTo("alice");
        assertThat(profile.displayName()).isEqualTo("alice");
        assertThat(profile.status()).isEqualTo(Status.ACTIVE);
        assertThat(profile.plan()).isEqualTo(com.picturesAI.user_service.domain.Plan.FREE);
        assertThat(profile.isPublic()).isTrue();
        UUID profileId = profile.id();

        // 2. БД: одна строка, один outbox
        assertThat(userRepository.count()).isEqualTo(1);
        List<OutboxEvent> eventsAfterCreate = outboxRepository.findAll();
        assertThat(eventsAfterCreate).hasSize(1);
        assertThat(eventsAfterCreate.get(0).getEventType()).isEqualTo("user.created");
        assertThat(eventsAfterCreate.get(0).getPublishedAt()).isNull();

        // 3. Повторный GET /me — тот же профиль, без дублей
        ResponseEntity<UserResponse> second = call(HttpMethod.GET, "/api/v1/users/me", null, UserResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().id()).isEqualTo(profileId);
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.findAll()).hasSize(1);

        // 4. Outbox publisher → published_at проставлен
        outboxPublisher.publishPending();
        OutboxEvent createdEvent = outboxRepository.findById(eventsAfterCreate.get(0).getId()).orElseThrow();
        assertThat(createdEvent.getPublishedAt()).isNotNull();

        // 5. PATCH /me → 200, поля обновились
        UpdateUserRequest patch = new UpdateUserRequest("Max", null, "hello", false);
        ResponseEntity<UserResponse> patched = call(HttpMethod.PATCH, "/api/v1/users/me", patch, UserResponse.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().displayName()).isEqualTo("Max");
        assertThat(patched.getBody().bio()).isEqualTo("hello");
        assertThat(patched.getBody().isPublic()).isFalse();

        // 6. GET /{id} чужого приватного → 404
        HttpHeaders otherHeaders = new HttpHeaders();
        otherHeaders.set("X-User-Id", UUID.randomUUID().toString());
        otherHeaders.set("X-User-Name", "bob");
        ResponseEntity<String> foreign = rest.exchange(
                "/api/v1/users/" + profileId, HttpMethod.GET, new HttpEntity<>(otherHeaders), String.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 7. GET /{id} владельца → 200
        ResponseEntity<UserResponse> own = call(HttpMethod.GET, "/api/v1/users/" + profileId, null, UserResponse.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody().isPublic()).isFalse();

        // 8. DELETE /me → 202, status=DELETING, user.deleted в outbox
        ResponseEntity<Void> deleted = call(HttpMethod.DELETE, "/api/v1/users/me", null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        User afterDelete = userRepository.findById(profileId).orElseThrow();
        assertThat(afterDelete.getStatus()).isEqualTo(Status.DELETING);
        assertThat(outboxRepository.findAll())
                .filteredOn(e -> "user.deleted".equals(e.getEventType()))
                .hasSize(1);

        // 9. Повторный DELETE → 202, второго user.deleted нет
        ResponseEntity<Void> deletedAgain = call(HttpMethod.DELETE, "/api/v1/users/me", null, Void.class);
        assertThat(deletedAgain.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxRepository.findAll())
                .filteredOn(e -> "user.deleted".equals(e.getEventType()))
                .hasSize(1);

        // 10. PATCH после DELETE → 409
        ResponseEntity<String> patchConflict = call(
                HttpMethod.PATCH, "/api/v1/users/me",
                new UpdateUserRequest("X", null, null, null), String.class);
        assertThat(patchConflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 11. GET /{id} пока DELETING → 404
        ResponseEntity<String> getDeleting = call(HttpMethod.GET, "/api/v1/users/" + profileId, null, String.class);
        assertThat(getDeleting.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 12. Воркер → Keycloak delete + физическое удаление строки
        userDeletionWorker.processPending();
        verify(keycloakAdminClient, times(1)).deleteUser(keycloakId);
        assertThat(userRepository.findById(profileId)).isEmpty();

        // 13. GET /me после удаления → лениво пересоздаётся новый профиль
        ResponseEntity<UserResponse> recreated = call(HttpMethod.GET, "/api/v1/users/me", null, UserResponse.class);
        assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recreated.getBody().id()).isNotEqualTo(profileId);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void missingUsernameReturns500() {
        HttpHeaders noName = new HttpHeaders();
        noName.set("X-User-Id", UUID.randomUUID().toString());

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(noName), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("missing username claim");
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void missingUserIdReturns401() {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void generationsStubReturns501() {
        ResponseEntity<String> response = call(
                HttpMethod.GET, "/api/v1/users/" + UUID.randomUUID() + "/generations", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }
}
