package com.picturesAI.billing_service.service;

import com.picturesAI.billing_service.entity.Plan;
import com.picturesAI.billing_service.entity.QuotaReservation;
import com.picturesAI.billing_service.entity.ReservationStatus;
import com.picturesAI.billing_service.repository.DailyUsageRepository;
import com.picturesAI.billing_service.repository.QuotaReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCleanupServiceTest {

    @Mock
    private QuotaReservationRepository reservationRepository;

    @Mock
    private DailyUsageRepository dailyUsageRepository;

    @Mock
    private PlanService planService;

    private SimpleMeterRegistry meterRegistry;
    private ReservationCleanupService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ReservationCleanupService(
                reservationRepository, dailyUsageRepository, planService, meterRegistry);
    }

    @Test
    void doesNothingWhenNoExpiredReservations() {
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED), any()))
                .thenReturn(List.of());

        service.cleanupExpired();

        verify(reservationRepository, never()).saveAll(any());
        verifyNoInteractions(dailyUsageRepository, planService);
        assertThat(meterRegistry.counter("quota_reservations_expired_total").count()).isZero();
    }

    @Test
    void releasesExpiredReservationsAndDecrementsUsage() {
        UUID userId = UUID.randomUUID();
        LocalDate date = LocalDate.now();
        QuotaReservation expired = QuotaReservation.builder()
                .generationId(UUID.randomUUID())
                .userId(userId)
                .reservedDate(date)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().minusSeconds(60))
                .build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED), any()))
                .thenReturn(List.of(expired));
        when(planService.getPlan(userId)).thenReturn(Plan.FREE);

        service.cleanupExpired();

        assertThat(expired.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(expired.getReleasedAt()).isNotNull();
        verify(dailyUsageRepository).decrement(userId, date);
        ArgumentCaptor<List<QuotaReservation>> captor = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(meterRegistry.counter("quota_reservations_expired_total").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("billing_quota_released_total",
                "plan", "FREE", "reason", "expired").count()).isEqualTo(1.0);
    }

    @Test
    void handlesMultipleExpiredReservations() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        LocalDate date = LocalDate.now();
        QuotaReservation r1 = QuotaReservation.builder()
                .generationId(UUID.randomUUID()).userId(user1).reservedDate(date)
                .status(ReservationStatus.RESERVED).expiresAt(Instant.now().minusSeconds(60)).build();
        QuotaReservation r2 = QuotaReservation.builder()
                .generationId(UUID.randomUUID()).userId(user2).reservedDate(date)
                .status(ReservationStatus.RESERVED).expiresAt(Instant.now().minusSeconds(30)).build();
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.RESERVED), any()))
                .thenReturn(List.of(r1, r2));
        when(planService.getPlan(user1)).thenReturn(Plan.FREE);
        when(planService.getPlan(user2)).thenReturn(Plan.PRO);

        service.cleanupExpired();

        verify(dailyUsageRepository).decrement(user1, date);
        verify(dailyUsageRepository).decrement(user2, date);
        verify(reservationRepository).saveAll(any());
        assertThat(meterRegistry.counter("quota_reservations_expired_total").count()).isEqualTo(2.0);
    }
}