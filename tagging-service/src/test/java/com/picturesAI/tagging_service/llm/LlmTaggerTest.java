package com.picturesAI.tagging_service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturesAI.tagging_service.config.LlmProperties;
import com.picturesAI.tagging_service.domain.TagVocabulary;
import com.picturesAI.tagging_service.tagger.TagSuggestion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmTaggerTest {

    private final OllamaClient client = mock(OllamaClient.class);
    private final LlmProperties props = new LlmProperties(
            "http://localhost:11434", "qwen2.5:1.5b", 10000, 10);
    private final LlmTagger tagger = new LlmTagger(client, props, new ObjectMapper());

    private final TagVocabulary cat = tag("cat");
    private final TagVocabulary dog = tag("dog");
    private final TagVocabulary sunset = tag("sunset");

    private final List<TagVocabulary> vocab = List.of(cat, dog, sunset);

    @Test
    void parsesValidJsonArray() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"cat\",\"weight\":0.9},{\"tag\":\"dog\",\"weight\":0.4}]");

        List<TagSuggestion> result = tagger.tag("a cat and a dog", vocab);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("cat");
        assertThat(result.get(0).weight()).isEqualByComparingTo("0.90");
        assertThat(result.get(1).name()).isEqualTo("dog");
        assertThat(result.get(1).weight()).isEqualByComparingTo("0.40");
    }

    @Test
    void requestContainsDictionaryAndPrompt() {
        when(client.generate(anyString())).thenReturn("[]");

        tagger.tag("a cat on a sofa", vocab);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client).generate(captor.capture());
        String request = captor.getValue();
        assertThat(request).contains("cat, dog, sunset");
        assertThat(request).contains("a cat on a sofa");
        assertThat(request).contains("JSON only");
    }

    @Test
    void invalidJsonThrows() {
        when(client.generate(anyString())).thenReturn("totally not json");

        assertThatThrownBy(() -> tagger.tag("a cat", vocab))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void nonArrayJsonThrows() {
        when(client.generate(anyString())).thenReturn("{\"tag\":\"cat\"}");

        assertThatThrownBy(() -> tagger.tag("a cat", vocab))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("array");
    }

    @Test
    void emptyArrayReturnsEmptyList() {
        when(client.generate(anyString())).thenReturn("[]");

        assertThat(tagger.tag("a cat", vocab)).isEmpty();
    }

    @Test
    void allTagsOutsideDictionaryReturnsEmpty() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"dragon\",\"weight\":1.0},{\"tag\":\"spaceship\",\"weight\":0.5}]");

        assertThat(tagger.tag("a cat", vocab)).isEmpty();
    }

    @Test
    void weightAboveOneClampedToOne() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"cat\",\"weight\":5.0}]");

        List<TagSuggestion> result = tagger.tag("a cat", vocab);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).weight()).isEqualByComparingTo("1.00");
    }

    @Test
    void negativeWeightClampedToZero() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"cat\",\"weight\":-2.0}]");

        List<TagSuggestion> result = tagger.tag("a cat", vocab);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).weight()).isEqualByComparingTo("0.00");
    }

    @Test
    void missingWeightBecomesZero() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"cat\"}]");

        List<TagSuggestion> result = tagger.tag("a cat", vocab);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).weight()).isEqualByComparingTo("0.00");
    }

    @Test
    void duplicateTagKeepsHighestWeight() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"cat\",\"weight\":0.3},{\"tag\":\"cat\",\"weight\":0.8}]");

        List<TagSuggestion> result = tagger.tag("a cat", vocab);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).weight()).isEqualByComparingTo("0.80");
    }

    @Test
    void tagNameIsTrimmedBeforeLookup() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"  cat  \",\"weight\":0.7}]");

        List<TagSuggestion> result = tagger.tag("a cat", vocab);

        assertThat(result).extracting(TagSuggestion::name).containsExactly("cat");
    }

    @Test
    void caseSensitiveTagLookupRejectsUnknownCase() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"CAT\",\"weight\":0.7}]");

        assertThat(tagger.tag("a cat", vocab)).isEmpty();
    }

    @Test
    void sortedByWeightDescending() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"dog\",\"weight\":0.2},{\"tag\":\"cat\",\"weight\":0.9},{\"tag\":\"sunset\",\"weight\":0.5}]");

        List<TagSuggestion> result = tagger.tag("...", vocab);

        assertThat(result).extracting(TagSuggestion::name)
                .containsExactly("cat", "sunset", "dog");
    }

    @Test
    void maxTagsLimitApplied() {
        LlmProperties limited = new LlmProperties(
                "http://localhost:11434", "qwen2.5:1.5b", 10000, 2);
        LlmTagger t = new LlmTagger(client, limited, new ObjectMapper());
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":\"cat\",\"weight\":0.9},{\"tag\":\"dog\",\"weight\":0.8},{\"tag\":\"sunset\",\"weight\":0.7}]");

        List<TagSuggestion> result = t.tag("...", vocab);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TagSuggestion::name)
                .containsExactly("cat", "dog");
    }

    @Test
    void nullOrBlankPromptReturnsEmptyWithoutCallingClient() {
        assertThat(tagger.tag(null, vocab)).isEmpty();
        assertThat(tagger.tag("", vocab)).isEmpty();
        assertThat(tagger.tag("  ", vocab)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void emptyVocabularyReturnsEmptyWithoutCallingClient() {
        assertThat(tagger.tag("a cat", List.of())).isEmpty();
        assertThat(tagger.tag("a cat", null)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void entryWithNullTagIsIgnored() {
        when(client.generate(anyString())).thenReturn(
                "[{\"tag\":null,\"weight\":0.5},{\"tag\":\"cat\",\"weight\":0.9}]");

        List<TagSuggestion> result = tagger.tag("a cat", vocab);

        assertThat(result).extracting(TagSuggestion::name).containsExactly("cat");
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