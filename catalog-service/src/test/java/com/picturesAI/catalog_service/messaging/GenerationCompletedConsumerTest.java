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
class GenerationCompletedConsumerTest {

    @Mock
    private CatalogWriteService writer;

    private GenerationCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new GenerationCompletedConsumer(mapper, writer);
    }

    @Test
    void parsesPayloadAndDelegates() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String payload = """
                {
                  "generationId": "%s",
                  "userId": "%s",
                  "prompt": "a cat",
                  "originalsBucket": "orig",
                  "originalsKey": "k1",
                  "thumbnailsBucket": "thumb",
                  "thumbnailsKey": "k2",
                  "provider": "pollinations",
                  "completedAt": "2025-01-01T00:00:00Z",
                  "imageUrl": "ignored",
                  "thumbnailUrl": "ignored"
                }
                """.formatted(generationId, userId);

        consumer.onMessage(payload);

        ArgumentCaptor<GenerationCompletedEvent> captor =
                ArgumentCaptor.forClass(GenerationCompletedEvent.class);
        verify(writer).applyGenerationCompleted(captor.capture());
        GenerationCompletedEvent event = captor.getValue();
        assertThat(event.generationId()).isEqualTo(generationId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.prompt()).isEqualTo("a cat");
        assertThat(event.originalsBucket()).isEqualTo("orig");
        assertThat(event.originalsKey()).isEqualTo("k1");
        assertThat(event.thumbnailsBucket()).isEqualTo("thumb");
        assertThat(event.thumbnailsKey()).isEqualTo("k2");
        assertThat(event.provider()).isEqualTo("pollinations");
        assertThat(event.completedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    void allowsNullUserId() {
        UUID generationId = UUID.randomUUID();
        String payload = """
                {
                  "generationId": "%s",
                  "prompt": "anon",
                  "originalsBucket": "orig",
                  "originalsKey": "k1",
                  "thumbnailsBucket": "thumb",
                  "thumbnailsKey": "k2",
                  "completedAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(generationId);

        consumer.onMessage(payload);

        ArgumentCaptor<GenerationCompletedEvent> captor =
                ArgumentCaptor.forClass(GenerationCompletedEvent.class);
        verify(writer).applyGenerationCompleted(captor.capture());
        assertThat(captor.getValue().userId()).isNull();
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> consumer.onMessage("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation.completed");
    }
}