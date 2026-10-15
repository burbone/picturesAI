package com.picturesAI.billing_service.service;

import com.picturesAI.billing_service.AbstractPostgresIT;
import com.picturesAI.billing_service.entity.QuotaReservation;
import com.picturesAI.billing_service.entity.ReservationStatus;
import com.picturesAI.billing_service.repository.DailyUsageRepository;
import com.picturesAI.billing_service.repository.QuotaReservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationCleanupServiceIT extends AbstractPostgresIT {

    @Autowired
    private ReservationCleanupService service;

    @Autowired
    private QuotaReservationRepository reservationRepository;

    @Autowired
    private DailyUsageRepository dailyUsageRepository;

    @Autowired
    private QuotaService quotaService;

    @Test
    void cleanupReleasesExpiredReservationAndRestoresUsage() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();

        quotaService.reserve(userId, generationId);

        QuotaReservation reservation = reservationRepository.findById(generationId).orElseThrow();
        reservation.setExpiresAt(Instant.now().minusSeconds(1));
        reservationRepository.saveAndFlush(reservation);

        service.cleanupExpired();

        QuotaReservation reloaded = reservationRepository.findById(generationId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reloaded.getReleasedAt()).isNotNull();

        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        int used = dailyUsageRepository.findById(
                        new com.picturesAI.billing_service.entity.DailyUsageId(userId, today))
                .map(u -> u.getUsedCount())
                .orElse(0);
        assertThat(used).isZero();
    }

    @Test
    void cleanupLeavesConfirmedReservationsUntouched() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();

        quotaService.reserve(userId, generationId);
        quotaService.confirm(generationId);

        QuotaReservation reservation = reservationRepository.findById(generationId).orElseThrow();
        reservation.setExpiresAt(Instant.now().minusSeconds(1));
        reservationRepository.saveAndFlush(reservation);

        service.cleanupExpired();

        QuotaReservation reloaded = reservationRepository.findById(generationId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }
}