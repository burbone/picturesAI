package com.picturesAI.billing_service.repository;

import com.picturesAI.billing_service.AbstractPostgresIT;
import com.picturesAI.billing_service.entity.QuotaReservation;
import com.picturesAI.billing_service.entity.ReservationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaReservationRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private QuotaReservationRepository repository;

    @Test
    void savesAndCountsActiveReservations() {
        UUID userId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();

        repository.saveAndFlush(reservation(UUID.randomUUID(), userId, today, ReservationStatus.RESERVED, now.plusSeconds(60)));
        repository.saveAndFlush(reservation(UUID.randomUUID(), userId, today, ReservationStatus.RESERVED, now.plusSeconds(60)));
        repository.saveAndFlush(reservation(UUID.randomUUID(), userId, today, ReservationStatus.CONFIRMED, now.plusSeconds(60)));

        long active = repository.countByUserIdAndReservedDateAndStatus(userId, today, ReservationStatus.RESERVED);
        assertThat(active).isEqualTo(2);
    }

    @Test
    void findsExpiredReservations() {
        UUID userId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();

        UUID expiredId = UUID.randomUUID();
        repository.saveAndFlush(reservation(expiredId, userId, today, ReservationStatus.RESERVED, now.minus(1, ChronoUnit.MINUTES)));
        repository.saveAndFlush(reservation(UUID.randomUUID(), userId, today, ReservationStatus.RESERVED, now.plus(5, ChronoUnit.MINUTES)));
        repository.saveAndFlush(reservation(UUID.randomUUID(), userId, today, ReservationStatus.CONFIRMED, now.minus(1, ChronoUnit.MINUTES)));

        List<QuotaReservation> expired = repository.findByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, now);

        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).getGenerationId()).isEqualTo(expiredId);
    }

    private QuotaReservation reservation(UUID generationId, UUID userId, LocalDate date, ReservationStatus status, Instant expiresAt) {
        return QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(date)
                .status(status)
                .expiresAt(expiresAt)
                .build();
    }
}