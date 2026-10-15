package com.picturesAI.tagging_service.tagger;

import com.picturesAI.tagging_service.config.KeywordProperties;
import com.picturesAI.tagging_service.domain.TagVocabulary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordTaggerTest {

    private static final KeywordProperties DEFAULT =
            new KeywordProperties(10, new BigDecimal("0.1"));

    private final KeywordTagger tagger = new KeywordTagger(DEFAULT);

    private final TagVocabulary cat = tag("cat", "subject", "кот", "кошка", "kitten", "котёнок");
    private final TagVocabulary dog = tag("dog", "subject", "собака", "пёс", "puppy", "щенок");
    private final TagVocabulary sunset = tag("sunset", "subject", "закат", "sunrise", "рассвет");
    private final TagVocabulary mountain = tag("mountain", "subject", "гора", "горы", "mountains", "peak");

    private final List<TagVocabulary> vocab = List.of(cat, dog, sunset, mountain);

    @Test
    void matchesNameLatin() {
        List<TagSuggestion> result = tagger.tag("a cat on the sofa", vocab);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("cat");
        assertThat(result.get(0).weight()).isEqualByComparingTo("1.00");
    }

    @Test
    void matchesAliasCyrillic() {
        List<TagSuggestion> result = tagger.tag("на диване лежит кот", vocab);

        assertThat(result).extracting(TagSuggestion::name).containsExactly("cat");
    }

    @Test
    void matchesCaseInsensitiveLatin() {
        List<TagSuggestion> result = tagger.tag("A CAT and a DOG", vocab);

        assertThat(result).extracting(TagSuggestion::name)
                .containsExactlyInAnyOrder("cat", "dog");
    }

    @Test
    void matchesCaseInsensitiveCyrillic() {
        List<TagSuggestion> result = tagger.tag("КОТ и СОБАКА", vocab);

        assertThat(result).extracting(TagSuggestion::name)
                .containsExactlyInAnyOrder("cat", "dog");
    }

    @Test
    void respectsWordBoundaryBefore() {
        List<TagSuggestion> result = tagger.tag("concatenate the strings", vocab);

        assertThat(result).isEmpty();
    }

    @Test
    void respectsWordBoundaryAfter() {
        List<TagSuggestion> result = tagger.tag("there are many cats here", vocab);

        assertThat(result).isEmpty();
    }

    @Test
    void matchesWithPunctuationBoundary() {
        List<TagSuggestion> result = tagger.tag("cat, dog! sunset?", vocab);

        assertThat(result).extracting(TagSuggestion::name)
                .containsExactlyInAnyOrder("cat", "dog", "sunset");
    }

    @Test
    void normalizesWeightsSoSumIsAtMostOne() {
        List<TagSuggestion> result = tagger.tag("sunset over the mountains with a cat", vocab);

        assertThat(result).hasSize(3);
        BigDecimal sum = result.stream()
                .map(TagSuggestion::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isLessThanOrEqualTo(BigDecimal.ONE);
    }

    @Test
    void twoMatchesGetHalfEach() {
        List<TagSuggestion> result = tagger.tag("cat and dog", vocab);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(s ->
                assertThat(s.weight()).isEqualByComparingTo("0.50"));
    }

    @Test
    void topNLimitApplied() {
        KeywordTagger limited = new KeywordTagger(new KeywordProperties(2, new BigDecimal("0.01")));

        List<TagSuggestion> result = limited.tag("cat dog sunset mountain", vocab);

        assertThat(result).hasSize(2);
    }

    @Test
    void minWeightFilterPassesWhenAboveThreshold() {
        KeywordTagger lenient = new KeywordTagger(new KeywordProperties(10, new BigDecimal("0.5")));

        List<TagSuggestion> result = lenient.tag("cat on a sofa", vocab);

        assertThat(result).extracting(TagSuggestion::name).containsExactly("cat");
        assertThat(result.get(0).weight()).isEqualByComparingTo("1.00");
    }

    @Test
    void minWeightFilterDropsAllWhenBelowThreshold() {
        KeywordTagger strict = new KeywordTagger(new KeywordProperties(10, new BigDecimal("0.5")));

        List<TagSuggestion> result = strict.tag("cat dog sunset", vocab);

        assertThat(result).isEmpty();
    }

    @Test
    void minWeightExactlyAtThresholdIsKept() {
        KeywordTagger exact = new KeywordTagger(new KeywordProperties(10, new BigDecimal("0.50")));

        List<TagSuggestion> result = exact.tag("cat and dog", vocab);

        assertThat(result).extracting(TagSuggestion::name)
                .containsExactlyInAnyOrder("cat", "dog");
    }

    @Test
    void emptyPromptReturnsEmpty() {
        assertThat(tagger.tag("", vocab)).isEmpty();
        assertThat(tagger.tag("   ", vocab)).isEmpty();
        assertThat(tagger.tag(null, vocab)).isEmpty();
    }

    @Test
    void emptyVocabularyReturnsEmpty() {
        assertThat(tagger.tag("a cat", List.of())).isEmpty();
        assertThat(tagger.tag("a cat", null)).isEmpty();
    }

    @Test
    void noMatchesReturnsEmpty() {
        assertThat(tagger.tag("a completely unrelated phrase", vocab)).isEmpty();
    }

    @Test
    void matchingIsDeduplicatedPerTag() {
        List<TagSuggestion> result = tagger.tag("cat and cat and kitten and кошка", vocab);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("cat");
    }

    @Test
    void nullAliasArrayHandled() {
        TagVocabulary noAliases = tag("unique", "subject");

        List<TagSuggestion> result = tagger.tag("unique word", List.of(noAliases));

        assertThat(result).extracting(TagSuggestion::name).containsExactly("unique");
    }

    private static TagVocabulary tag(String name, String category, String... aliases) {
        TagVocabulary v = new TagVocabulary();
        v.setId(UUID.randomUUID());
        v.setName(name);
        v.setCategory(category);
        v.setAliases(aliases);
        v.setActive(true);
        return v;
    }
}