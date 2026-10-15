package com.picturesAI.catalog_service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.picturesAI.catalog_service.service.CatalogWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserCreatedConsumerTest {

    @Mock
    private CatalogWriteService writer;

    private UserCreatedConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new UserCreatedConsumer(mapper, writer);
    }

    @Test
    void parsesPayloadAndDelegates() {
        UUID userId = UUID.randomUUID();
        UUID keycloakId = UUID.randomUUID();
        String payload = """
                {
                  "userId": "%s",
                  "keycloakId": "%s",
                  "username": "alice",
                  "displayName": "Alice",
                  "avatarUrl": "https://cdn/avatar.png",
                  "createdAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(userId, keycloakId);

        consumer.onMessage(payload);

        ArgumentCaptor<UserCreatedEvent> captor = ArgumentCaptor.forClass(UserCreatedEvent.class);
        verify(writer).applyUserCreated(captor.capture());
        UserCreatedEvent event = captor.getValue();
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.keycloakId()).isEqualTo(keycloakId);
        assertThat(event.username()).isEqualTo("alice");
        assertThat(event.displayName()).isEqualTo("Alice");
        assertThat(event.avatarUrl()).isEqualTo("https://cdn/avatar.png");
        assertThat(event.createdAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    void allowsNullAvatar() {
        UUID userId = UUID.randomUUID();
        String payload = """
                {
                  "userId": "%s",
                  "keycloakId": "%s",
                  "username": "bob",
                  "displayName": "bob",
                  "avatarUrl": null,
                  "createdAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(userId, UUID.randomUUID());

        consumer.onMessage(payload);

        ArgumentCaptor<UserCreatedEvent> captor = ArgumentCaptor.forClass(UserCreatedEvent.class);
        verify(writer).applyUserCreated(captor.capture());
        assertThat(captor.getValue().avatarUrl()).isNull();
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> consumer.onMessage("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user.created");
    }
}