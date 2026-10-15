package com.picturesAI.tagging_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.tagging_service.config.TaggingModeProperties;
import com.picturesAI.tagging_service.domain.GenerationTag;
import com.picturesAI.tagging_service.domain.OutboxEvent;
import com.picturesAI.tagging_service.domain.TagVocabulary;
import com.picturesAI.tagging_service.llm.LlmException;
import com.picturesAI.tagging_service.llm.LlmTagger;
import com.picturesAI.tagging_service.repository.GenerationTagRepository;
import com.picturesAI.tagging_service.repository.OutboxRepository;
import com.picturesAI.tagging_service.repository.TagStatRepository;
import com.picturesAI.tagging_service.tagger.KeywordTagger;
import com.picturesAI.tagging_service.tagger.TagSuggestion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaggingServiceTest {

    private final GenerationTagRepository generationTags = mock(GenerationTagRepository.class);
    private final TagStatRepository tagStats = mock(TagStatRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final VocabularyService vocabulary = mock(VocabularyService.class);
    private final KeywordTagger keywordTagger = mock(KeywordTagger.class);
    private final LlmTagger llmTagger = mock(LlmTagger.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final TagVocabulary cat = tag("cat");
    private final TagVocabulary dog = tag("dog");
    private final TagVocabulary sunset = tag("sunset");

    private TaggingService service;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());

        lenient().when(vocabulary.activeVocabulary()).thenReturn(List.of(cat, dog, sunset));
    }

    private void withMode(String mode) {
        service = new TaggingService(
                generationTags, tagStats, outbox, vocabulary,
                keywordTagger, llmTagger, new TaggingModeProperties(mode),
                mapper, tx, registry);
    }

    @Test
    void llmSuccessSavesTagsAndPublishesOutbox() {
        withMode("llm");
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(llmTagger.tag(eq("a cat"), anyList())).thenReturn(List.of(
                new TagSuggestion("cat", new BigDecimal("0.90")),
                new TagSuggestion("dog", new BigDecimal("0.30"))));

        TaggingResult result = service.process(generationId, userId, "a cat");

        assertThat(result).isEqualTo(TaggingResult.EXTRACTED);
        verify(generationTags, times(2)).save(any(GenerationTag.class));
        verify(tagStats).incrementUsage(anyList());
        verify(keywordTagger, never()).tag(anyString(), anyList());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        OutboxEvent e = captor.getValue();
        assertThat(e.getEventType()).isEqualTo("tags.extracted");
        assertThat(e.getAggregateId()).isEqualTo(generationId);
        assertThat(e.getPayload()).contains("\"generationId\":\"" + generationId + "\"");
        assertThat(e.getPayload()).contains("\"userId\":\"" + userId + "\"");
        assertThat(e.getPayload()).contains("\"name\":\"cat\"");
        assertThat(e.getPayload()).contains("\"name\":\"dog\"");
        assertThat(e.getPayload()).contains("\"extractedAt\"");

        assertThat(registry.counter("tagging_extraction_total",
                "mode", "llm", "result", "success").count()).isEqualTo(1.0);
        assertThat(registry.counter("tagging_llm_calls_total",
                "result", "success").count()).isEqualTo(1.0);
        assertThat(registry.find("tagging_extraction_duration_seconds")
                .tag("mode", "llm").timer().count()).isEqualTo(1L);
        assertThat(registry.find("tagging_tags_per_generation").summary().totalAmount())
                .isEqualTo(2.0);
    }

    @Test
    void llmFailureFallsBackToKeyword() {
        withMode("llm");
        UUID generationId = UUID.randomUUID();

        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(llmTagger.tag(anyString(), anyList())).thenThrow(new LlmException("boom", true));
        when(keywordTagger.tag(anyString(), anyList()))
                .thenReturn(List.of(new TagSuggestion("cat", new BigDecimal("1.00"))));

        TaggingResult result = service.process(generationId, UUID.randomUUID(), "a cat");

        assertThat(result).isEqualTo(TaggingResult.EXTRACTED);
        verify(keywordTagger).tag(anyString(), anyList());
        verify(generationTags).save(any(GenerationTag.class));
        assertThat(registry.counter("tagging_extraction_total",
                "mode", "keyword", "result", "fallback").count()).isEqualTo(1.0);
        assertThat(registry.counter("tagging_llm_calls_total",
                "result", "fail").count()).isEqualTo(1.0);
    }

    @Test
    void llmEmptyResultTriggersFallback() {
        withMode("llm");
        UUID generationId = UUID.randomUUID();

        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(llmTagger.tag(anyString(), anyList())).thenReturn(List.of());
        when(keywordTagger.tag(anyString(), anyList()))
                .thenReturn(List.of(new TagSuggestion("cat", new BigDecimal("1.00"))));

        service.process(generationId, UUID.randomUUID(), "a cat");

        verify(keywordTagger).tag(anyString(), anyList());
        assertThat(registry.counter("tagging_extraction_total",
                "mode", "keyword", "result", "fallback").count()).isEqualTo(1.0);
        assertThat(registry.counter("tagging_llm_calls_total",
                "result", "success").count()).isEqualTo(1.0);
    }

    @Test
    void keywordModeNeverCallsLlm() {
        withMode("keywords");
        UUID generationId = UUID.randomUUID();

        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(keywordTagger.tag(anyString(), anyList()))
                .thenReturn(List.of(new TagSuggestion("cat", new BigDecimal("1.00"))));

        service.process(generationId, UUID.randomUUID(), "a cat");

        verify(llmTagger, never()).tag(anyString(), anyList());
        assertThat(registry.counter("tagging_extraction_total",
                "mode", "keyword", "result", "success").count()).isEqualTo(1.0);
        assertThat(registry.find("tagging_llm_calls_total").counter()).isNull();
    }

    @Test
    void idempotentSkipWhenTagsExist() {
        withMode("llm");
        UUID generationId = UUID.randomUUID();
        when(generationTags.existsByGenerationId(generationId)).thenReturn(true);

        TaggingResult result = service.process(generationId, UUID.randomUUID(), "a cat");

        assertThat(result).isEqualTo(TaggingResult.SKIPPED);
        verify(llmTagger, never()).tag(anyString(), anyList());
        verify(keywordTagger, never()).tag(anyString(), anyList());
        verify(outbox, never()).save(any());
        verify(generationTags, never()).save(any());
    }

    @Test
    void weightsNormalizedWhenSumAboveOne() {
        withMode("keywords");
        UUID generationId = UUID.randomUUID();

        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(keywordTagger.tag(anyString(), anyList())).thenReturn(List.of(
                new TagSuggestion("cat", new BigDecimal("1.00")),
                new TagSuggestion("dog", new BigDecimal("0.80")),
                new TagSuggestion("sunset", new BigDecimal("0.60"))));

        service.process(generationId, UUID.randomUUID(), "a cat and a dog at sunset");

        ArgumentCaptor<GenerationTag> captor = ArgumentCaptor.forClass(GenerationTag.class);
        verify(generationTags, times(3)).save(captor.capture());
        BigDecimal sum = captor.getAllValues().stream()
                .map(GenerationTag::getWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isLessThanOrEqualTo(BigDecimal.ONE);
        assertThat(sum).isGreaterThan(new BigDecimal("0.99"));
    }

    @Test
    void weightsOutsideRangeAreFiltered() {
        withMode("keywords");
        UUID generationId = UUID.randomUUID();

        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(keywordTagger.tag(anyString(), anyList())).thenReturn(List.of(
                new TagSuggestion("cat", new BigDecimal("1.50")),
                new TagSuggestion("dog", new BigDecimal("-0.10")),
                new TagSuggestion("sunset", new BigDecimal("0.50"))));

        service.process(generationId, UUID.randomUUID(), "anything");

        ArgumentCaptor<GenerationTag> captor = ArgumentCaptor.forClass(GenerationTag.class);
        verify(generationTags).save(captor.capture());
        assertThat(captor.getValue().getWeight()).isEqualByComparingTo("0.50");
    }

    @Test
    void top10LimitAppliedOnValidation() {
        withMode("keywords");
        UUID generationId = UUID.randomUUID();

        List<TagSuggestion> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(new TagSuggestion("cat", new BigDecimal("0.01")));
        }
        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(keywordTagger.tag(anyString(), anyList())).thenReturn(many);

        service.process(generationId, UUID.randomUUID(), "anything");

        verify(generationTags, times(10)).save(any(GenerationTag.class));
    }

    @Test
    void emptyVocabularyReturnsExtractedWithoutSaving() {
        withMode("keywords");
        UUID generationId = UUID.randomUUID();

        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(vocabulary.activeVocabulary()).thenReturn(List.of());

        TaggingResult result = service.process(generationId, UUID.randomUUID(), "a cat");

        assertThat(result).isEqualTo(TaggingResult.EXTRACTED);
        verify(generationTags, never()).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void emptySuggestionsStillPublishesOutboxWithEmptyTags() {
        withMode("keywords");
        UUID generationId = UUID.randomUUID();

        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(keywordTagger.tag(anyString(), anyList())).thenReturn(List.of());

        service.process(generationId, UUID.randomUUID(), "nothing matches");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"tags\":[]");
        verify(generationTags, never()).save(any());
        verify(tagStats, never()).incrementUsage(anyList());
    }

    @Test
    void transactionFailureRecordsErrorMetricAndPropagates() {
        withMode("keywords");
        UUID generationId = UUID.randomUUID();

        when(generationTags.existsByGenerationId(generationId)).thenReturn(false);
        when(keywordTagger.tag(anyString(), anyList()))
                .thenReturn(List.of(new TagSuggestion("cat", new BigDecimal("1.00"))));
        doAnswer(inv -> {
            throw new RuntimeException("db down");
        }).when(tx).executeWithoutResult(any());

        assertThatThrownBy(() -> service.process(generationId, UUID.randomUUID(), "a cat"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db down");

        assertThat(registry.counter("tagging_extraction_total",
                "mode", "keyword", "result", "error").count()).isEqualTo(1.0);
    }

    private static TagVocabulary tag(String name) {
        TagVocabulary v = new TagVocabulary();
        v.setId(UUID.randomUUID());
        v.setName(name);
        v.setCategory("subject");
        v.setAliases(new String[0]);
        v.setActive(true);
        return v;
    }
}