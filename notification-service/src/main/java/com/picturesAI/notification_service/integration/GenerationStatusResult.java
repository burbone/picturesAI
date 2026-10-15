package com.picturesAI.notification_service.integration;

import com.fasterxml.jackson.databind.JsonNode;

public record GenerationStatusResult(GenerationAccessResult access, JsonNode payload) {
}