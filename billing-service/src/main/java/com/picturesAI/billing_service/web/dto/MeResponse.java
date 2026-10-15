package com.picturesAI.billing_service.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MeResponse(
        UUID userId,
        String plan,
        int dailyLimit,
        int usedToday,
        int remaining,
        LocalDate date,
        List<ActiveReservation> activeReservations) {

    public record ActiveReservation(UUID generationId, String expiresAt) {
    }
}