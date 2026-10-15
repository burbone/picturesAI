package com.picturesAI.user_service.service;

import com.picturesAI.user_service.AbstractPostgresTest;
import com.picturesAI.user_service.domain.OutboxEvent;
import com.picturesAI.user_service.dto.UserResponse;
import com.picturesAI.user_service.repository.OutboxRepository;
import com.picturesAI.user_service.repository.UserRepository;
import com.picturesAI.user_service.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserServiceIT extends AbstractPostgresTest {

    @TestConfiguration
    static class TestIdentityConfig {

        @Bean
        @Primary
        MutableCurrentUserProvider mutableCurrentUserProvider() {
            return new MutableCurrentUserProvider();
        }
    }

    static class MutableCurrentUserProvider implements CurrentUserProvider {

        volatile UUID userId;
        volatile String username;

        @Override
        public UUID userId() {
            return userId;
        }

        @Override
        public Set<String> roles() {
            return Set.of("ROLE_USER");
        }

        @Override
        public String username() {
            return username;
        }
    }

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private MutableCurrentUserProvider identity;

    @BeforeEach
    void cleanUp() {
        outboxRepository.deleteAll();
        userRepository.deleteAll();
        identity.username = null;
    }

    @Test
    void firstCallCreatesProfileAndEvent() {
        identity.userId = UUID.randomUUID();
        identity.username = "alice";

        UserResponse response = userService.getOrCreateCurrent();

        assertThat(response.username()).isEqualTo("alice");
        assertThat(userRepository.findById(response.id())).isPresent();

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("user.created");
    }

    @Test
    void repeatedCallReturnsSameProfileWithoutDuplicatingEvent() {
        identity.userId = UUID.randomUUID();
        identity.username = "alice";

        UserResponse first = userService.getOrCreateCurrent();
        UserResponse second = userService.getOrCreateCurrent();

        assertThat(first.id()).isEqualTo(second.id());
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void missingUsernameThrows() {
        identity.userId = UUID.randomUUID();
        identity.username = null;

        assertThatThrownBy(() -> userService.getOrCreateCurrent())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("missing username claim");
        assertThat(userRepository.count()).isZero();
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    @Test
    void existingProfileDoesNotRequireUsername() {
        identity.userId = UUID.randomUUID();
        identity.username = "alice";
        userService.getOrCreateCurrent();

        identity.username = null;
        UserResponse response = userService.getOrCreateCurrent();

        assertThat(response.username()).isEqualTo("alice");
        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentCallsProduceSingleRowAndSingleEvent() throws Exception {
        UUID keycloakId = UUID.randomUUID();
        identity.userId = keycloakId;
        identity.username = "alice";

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<UserResponse>> tasks = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (Callable<UserResponse>) () -> {
                        start.await();
                        return userService.getOrCreateCurrent();
                    })
                    .toList();

            List<Future<UserResponse>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();

            Set<UUID> ids = new HashSet<>();
            for (Future<UserResponse> f : futures) {
                ids.add(f.get().id());
            }

            assertThat(ids).hasSize(1);
            assertThat(userRepository.count()).isEqualTo(1);
            assertThat(outboxRepository.findAll())
                    .filteredOn(e -> "user.created".equals(e.getEventType()))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}