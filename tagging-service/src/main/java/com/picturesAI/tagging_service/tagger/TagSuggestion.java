package com.picturesAI.tagging_service.tagger;

import java.math.BigDecimal;

public record TagSuggestion(String name, BigDecimal weight) {
}