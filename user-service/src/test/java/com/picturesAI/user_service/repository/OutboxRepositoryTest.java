package com.picturesAI.user_service.repository;

import com.picturesAI.user_service.AbstractPostgresTest;
import com.picturesAI.user_service.domain.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRepositoryTest extends AbstractPostgresTest {

    @Autowired
    private OutboxRepository outboxRepository;

    private OutboxEvent event(UUID aggregateId, String type, Instant publishedAt) {
        OutboxEvent e = new OutboxEvent();
        e.setId(UUID.randomUUID());
        e.setAggregateId(aggregateId);
        e.setEventType(type);
        e.setPayload("{\"k\":\"v\"}");
        e.setPublishedAt(publishedAt);
        return e;
    }

    @Test
    @Transactional
    void jsonbRoundTrip() {
        OutboxEvent saved = outboxRepository.saveAndFlush(
                event(UUID.randomUUID(), "user.created", null));

        OutboxEvent found = outboxRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getPayload()).contains("\"k\"").contains("\"v\"");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getPublishedAt()).isNull();
    }

    @Test
    @Transactional
    void returnsOnlyUnpublishedOrderedByCreatedAt() {
        UUID agg = UUID.randomUUID();
        OutboxEvent first = outboxRepository.saveAndFlush(event(agg, "user.created", null));
        OutboxEvent second = outboxRepository.saveAndFlush(event(agg, "user.deleted", null));
        outboxRepository.saveAndFlush(event(agg, "user.created", Instant.now()));

        List<OutboxEvent> unpublished =
                outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, 10));

        assertThat(unpublished)
                .extracting(OutboxEvent::getId)
                .containsExactly(first.getId(), second.getId());
    }
}