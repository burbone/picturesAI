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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TagsExtractedConsumerTest {

    @Mock
    private CatalogWriteService writer;

    private TagsExtractedConsumer consumer;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new TagsExtractedConsumer(mapper, writer);
    }

    @Test
    void parsesPayloadAndDelegates() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String payload = """
                {
                  "generationId": "%s",
                  "userId": "%s",
                  "tags": [
                    {"name": "cat", "weight": 0.60},
                    {"name": "cute", "weight": 0.40}
                  ],
                  "extractedAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(generationId, userId);

        consumer.onMessage(payload);

        ArgumentCaptor<TagsExtractedEvent> captor = ArgumentCaptor.forClass(TagsExtractedEvent.class);
        verify(writer).applyTagsExtracted(captor.capture());
        TagsExtractedEvent event = captor.getValue();
        assertThat(event.generationId()).isEqualTo(generationId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.tags()).hasSize(2);
        assertThat(event.tags().get(0).name()).isEqualTo("cat");
        assertThat(event.tags().get(0).weight()).isEqualByComparingTo(new BigDecimal("0.60"));
        assertThat(event.tags().get(1).name()).isEqualTo("cute");
        assertThat(event.extractedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    void allowsNullUserIdAndEmptyTags() {
        UUID generationId = UUID.randomUUID();
        String payload = """
                {
                  "generationId": "%s",
                  "tags": [],
                  "extractedAt": "2025-01-01T00:00:00Z"
                }
                """.formatted(generationId);

        consumer.onMessage(payload);

        ArgumentCaptor<TagsExtractedEvent> captor = ArgumentCaptor.forClass(TagsExtractedEvent.class);
        verify(writer).applyTagsExtracted(captor.capture());
        TagsExtractedEvent event = captor.getValue();
        assertThat(event.userId()).isNull();
        assertThat(event.tags()).isEqualTo(List.of());
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> consumer.onMessage("not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tags.extracted");
    }
}