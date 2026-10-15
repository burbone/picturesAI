package com.picturesAI.tagging_service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.tagging_service.config.LlmProperties;
import com.picturesAI.tagging_service.domain.TagVocabulary;
import com.picturesAI.tagging_service.tagger.TagSuggestion;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class LlmTagger {

    private final OllamaClient client;
    private final LlmProperties props;
    private final ObjectMapper mapper;

    public LlmTagger(OllamaClient client, LlmProperties props, ObjectMapper mapper) {
        this.client = client;
        this.props = props;
        this.mapper = mapper;
    }

    public List<TagSuggestion> tag(String prompt, List<TagVocabulary> vocabulary) {
        if (prompt == null || prompt.isBlank() || vocabulary == null || vocabulary.isEmpty()) {
            return List.of();
        }

        String request = buildRequest(prompt, vocabulary);
        String response = client.generate(request);

        List<RawSuggestion> raw = parseResponse(response);
        if (raw.isEmpty()) {
            return List.of();
        }

        Map<String, TagVocabulary> byName = vocabulary.stream()
                .collect(Collectors.toMap(TagVocabulary::getName, v -> v));

        Map<String, BigDecimal> byTag = new LinkedHashMap<>();
        for (RawSuggestion s : raw) {
            if (s.name() == null) {
                continue;
            }
            String name = s.name().trim();
            if (name.isEmpty() || !byName.containsKey(name)) {
                continue;
            }
            BigDecimal weight = clamp(s.weight());
            byTag.merge(name, weight, (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }

        if (byTag.isEmpty()) {
            return List.of();
        }

        List<TagSuggestion> sorted = new ArrayList<>(byTag.size());
        byTag.forEach((name, weight) -> sorted.add(new TagSuggestion(name, weight)));

        sorted.sort(Comparator
                .comparing(TagSuggestion::weight).reversed()
                .thenComparing(TagSuggestion::name));

        if (sorted.size() > props.maxTags()) {
            return new ArrayList<>(sorted.subList(0, props.maxTags()));
        }
        return sorted;
    }

    private String buildRequest(String prompt, List<TagVocabulary> vocabulary) {
        String dictionary = vocabulary.stream()
                .map(TagVocabulary::getName)
                .collect(Collectors.joining(", "));
        return "Match tags from dictionary to this description. "
                + "Return JSON only: [{\"tag\":\"name\",\"weight\":0.0-1.0}]. "
                + "Dictionary: " + dictionary
                + " Description: " + prompt;
    }

    private List<RawSuggestion> parseResponse(String response) {
        if (response == null || response.isBlank()) {
            throw new LlmException("Empty LLM response body", false);
        }
        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (Exception e) {
            throw new LlmException("LLM response is not valid JSON", false, e);
        }
        if (!root.isArray()) {
            throw new LlmException("LLM response is not a JSON array", false);
        }
        List<RawSuggestion> out = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            String name = node.path("tag").isTextual() ? node.path("tag").asText() : null;
            JsonNode weightNode = node.path("weight");
            BigDecimal weight = weightNode.isNumber()
                    ? BigDecimal.valueOf(weightNode.asDouble())
                    : null;
            out.add(new RawSuggestion(name, weight));
        }
        return out;
    }

    private static BigDecimal clamp(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (value.signum() < 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record RawSuggestion(String name, BigDecimal weight) {
    }
}