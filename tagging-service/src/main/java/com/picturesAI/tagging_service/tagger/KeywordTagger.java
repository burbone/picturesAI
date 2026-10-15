package com.picturesAI.tagging_service.tagger;

import com.picturesAI.tagging_service.config.KeywordProperties;
import com.picturesAI.tagging_service.domain.TagVocabulary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class KeywordTagger {

    private final KeywordProperties props;

    public KeywordTagger(KeywordProperties props) {
        this.props = props;
    }

    public List<TagSuggestion> tag(String prompt, List<TagVocabulary> vocabulary) {
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        if (vocabulary == null || vocabulary.isEmpty()) {
            return List.of();
        }

        List<TagVocabulary> matched = new ArrayList<>();
        for (TagVocabulary v : vocabulary) {
            if (matchesAnyTerm(prompt, v.getName(), v.getAliases())) {
                matched.add(v);
            }
        }
        if (matched.isEmpty()) {
            return List.of();
        }

        BigDecimal weight = BigDecimal.ONE
                .divide(BigDecimal.valueOf(matched.size()), 2, RoundingMode.HALF_UP);

        List<TagSuggestion> suggestions = new ArrayList<>(matched.size());
        for (TagVocabulary v : matched) {
            suggestions.add(new TagSuggestion(v.getName(), weight));
        }

        suggestions.sort(Comparator
                .comparing(TagSuggestion::weight).reversed()
                .thenComparing(TagSuggestion::name));

        if (suggestions.size() > props.maxTags()) {
            suggestions = new ArrayList<>(suggestions.subList(0, props.maxTags()));
        }

        suggestions.removeIf(s -> s.weight().compareTo(props.minWeight()) < 0);

        return suggestions;
    }

    private boolean matchesAnyTerm(String prompt, String name, String[] aliases) {
        if (matches(prompt, name)) {
            return true;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (matches(prompt, alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matches(String prompt, String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        return wordPattern(term).matcher(prompt).find();
    }

    private static Pattern wordPattern(String term) {
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])",
                flags);
    }
}