package com.picturesAI.tagging_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.tagging_service.config.TaggingModeProperties;
import com.picturesAI.tagging_service.domain.GenerationTag;
import com.picturesAI.tagging_service.domain.GenerationTagId;
import com.picturesAI.tagging_service.domain.OutboxEvent;
import com.picturesAI.tagging_service.domain.TagVocabulary;
import com.picturesAI.tagging_service.llm.LlmException;
import com.picturesAI.tagging_service.llm.LlmTagger;
import com.picturesAI.tagging_service.repository.GenerationTagRepository;
import com.picturesAI.tagging_service.repository.OutboxRepository;
import com.picturesAI.tagging_service.repository.TagStatRepository;
import com.picturesAI.tagging_service.tagger.KeywordTagger;
import com.picturesAI.tagging_service.tagger.TagSuggestion;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaggingService {

    private static final Logger log = LoggerFactory.getLogger(TaggingService.class);
    private static final int MAX_TAGS = 10;
    private static final String METRIC_EXTRACTION = "tagging_extraction_total";
    private static final String METRIC_DURATION = "tagging_extraction_duration_seconds";
    private static final String METRIC_LLM_CALLS = "tagging_llm_calls_total";
    private static final String METRIC_TAGS_PER_GEN = "tagging_tags_per_generation";

    private final GenerationTagRepository generationTags;
    private final TagStatRepository tagStats;
    private final OutboxRepository outbox;
    private final VocabularyService vocabulary;
    private final KeywordTagger keywordTagger;
    private final LlmTagger llmTagger;
    private final TaggingModeProperties props;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;

    public TaggingService(GenerationTagRepository generationTags,
                          TagStatRepository tagStats,
                          OutboxRepository outbox,
                          VocabularyService vocabulary,
                          KeywordTagger keywordTagger,
                          LlmTagger llmTagger,
                          TaggingModeProperties props,
                          ObjectMapper mapper,
                          TransactionTemplate tx,
                          MeterRegistry meterRegistry) {
        this.generationTags = generationTags;
        this.tagStats = tagStats;
        this.outbox = outbox;
        this.vocabulary = vocabulary;
        this.keywordTagger = keywordTagger;
        this.llmTagger = llmTagger;
        this.props = props;
        this.mapper = mapper;
        this.tx = tx;
        this.meterRegistry = meterRegistry;
    }

    public TaggingResult process(UUID generationId, UUID userId, String prompt) {
        if (generationTags.existsByGenerationId(generationId)) {
            log.info("Tags already extracted for generation {} — skipping", generationId);
            return TaggingResult.SKIPPED;
        }

        List<TagVocabulary> vocab = vocabulary.activeVocabulary();
        if (vocab.isEmpty()) {
            log.warn("Vocabulary is empty, no tags can be extracted for generation {}", generationId);
            return TaggingResult.EXTRACTED;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        List<TagSuggestion> suggestions;
        String usedMode;
        String result;

        if (props.isLlm()) {
            try {
                suggestions = llmTagger.tag(prompt, vocab);
                meterRegistry.counter(METRIC_LLM_CALLS, "result", "success").increment();
                if (suggestions.isEmpty()) {
                    suggestions = keywordTagger.tag(prompt, vocab);
                    usedMode = "keyword";
                    result = "fallback";
                } else {
                    usedMode = "llm";
                    result = "success";
                }
            } catch (LlmException e) {
                log.warn("LLM tagging failed for generation {}: {}", generationId, e.toString());
                meterRegistry.counter(METRIC_LLM_CALLS, "result", "fail").increment();
                suggestions = keywordTagger.tag(prompt, vocab);
                usedMode = "keyword";
                result = "fallback";
            }
        } else {
            suggestions = keywordTagger.tag(prompt, vocab);
            usedMode = "keyword";
            result = "success";
        }
        sample.stop(meterRegistry.timer(METRIC_DURATION, "mode", usedMode));
        meterRegistry.counter(METRIC_EXTRACTION, "mode", usedMode, "result", result).increment();

        Set<String> vocabNames = vocab.stream()
                .map(TagVocabulary::getName)
                .collect(Collectors.toSet());
        List<TagSuggestion> validated = validate(suggestions, vocabNames);

        Map<String, UUID> tagIds = vocab.stream()
                .collect(Collectors.toMap(TagVocabulary::getName, TagVocabulary::getId));

        try {
            tx.executeWithoutResult(status -> persist(generationId, userId, validated, tagIds));
        } catch (RuntimeException e) {
            meterRegistry.counter(METRIC_EXTRACTION, "mode", usedMode, "result", "error").increment();
            throw e;
        }

        DistributionSummary.builder(METRIC_TAGS_PER_GEN)
                .register(meterRegistry)
                .record(validated.size());

        return TaggingResult.EXTRACTED;
    }

    private List<TagSuggestion> validate(List<TagSuggestion> suggestions, Set<String> vocabNames) {
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }

        List<TagSuggestion> filtered = new ArrayList<>();
        for (TagSuggestion s : suggestions) {
            if (s.name() == null || !vocabNames.contains(s.name())) {
                continue;
            }
            if (s.weight() == null) {
                continue;
            }
            if (s.weight().compareTo(BigDecimal.ZERO) < 0
                    || s.weight().compareTo(BigDecimal.ONE) > 0) {
                continue;
            }
            filtered.add(s);
        }
        if (filtered.isEmpty()) {
            return List.of();
        }

        BigDecimal sum = filtered.stream()
                .map(TagSuggestion::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(BigDecimal.ONE) > 0) {
            BigDecimal scale = BigDecimal.ONE.divide(sum, 6, RoundingMode.HALF_UP);
            List<TagSuggestion> normalized = new ArrayList<>(filtered.size());
            for (TagSuggestion s : filtered) {
                BigDecimal w = s.weight().multiply(scale).setScale(2, RoundingMode.HALF_UP);
                normalized.add(new TagSuggestion(s.name(), w));
            }
            filtered = normalized;
        }

        if (filtered.size() > MAX_TAGS) {
            filtered = new ArrayList<>(filtered.subList(0, MAX_TAGS));
        }
        return filtered;
    }

    private void persist(UUID generationId,
                         UUID userId,
                         List<TagSuggestion> validated,
                         Map<String, UUID> tagIds) {
        if (!validated.isEmpty()) {
            for (TagSuggestion s : validated) {
                GenerationTag gt = new GenerationTag();
                gt.setId(new GenerationTagId(generationId, tagIds.get(s.name())));
                gt.setWeight(s.weight());
                generationTags.save(gt);
            }
            List<UUID> ids = validated.stream()
                    .map(s -> tagIds.get(s.name()))
                    .toList();
            tagStats.incrementUsage(ids);
        }
        outbox.save(buildOutbox(generationId, userId, validated));
    }

    private OutboxEvent buildOutbox(UUID generationId, UUID userId, List<TagSuggestion> tags) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", generationId.toString());
        if (userId != null) {
            payload.put("userId", userId.toString());
        }
        List<Map<String, Object>> tagList = new ArrayList<>(tags.size());
        for (TagSuggestion s : tags) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", s.name());
            t.put("weight", s.weight());
            tagList.add(t);
        }
        payload.put("tags", tagList);
        payload.put("extractedAt", Instant.now().toString());

        OutboxEvent e = new OutboxEvent();
        e.setAggregateId(generationId);
        e.setEventType("tags.extracted");
        try {
            e.setPayload(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
        return e;
    }
}