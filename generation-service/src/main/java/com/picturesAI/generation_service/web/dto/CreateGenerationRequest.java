package com.picturesAI.generation_service.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGenerationRequest(
        @NotBlank @Size(max = 2000) String prompt,
        @Size(max = 2000) String negativePrompt,
        @NotBlank @Size(max = 50) String model,
        @NotBlank @Size(max = 20) String size,
        @Min(1) @Max(4) Integer count,
        Boolean isPublic) {

    public int countOrDefault() {
        return count == null ? 1 : count;
    }

    public boolean publicOrDefault() {
        return Boolean.TRUE.equals(isPublic);
    }
}