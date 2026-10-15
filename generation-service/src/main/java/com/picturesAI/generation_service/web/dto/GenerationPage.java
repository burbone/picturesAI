package com.picturesAI.generation_service.web.dto;

import java.util.List;
import java.util.UUID;

public record GenerationPage(
        List<GenerationView> items,
        UUID nextCursor) {
}