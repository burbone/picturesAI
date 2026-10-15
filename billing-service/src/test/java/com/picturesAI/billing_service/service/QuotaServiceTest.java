package com.picturesAI.billing_service.service;

import com.picturesAI.billing_service.entity.Account;
import com.picturesAI.billing_service.entity.Plan;
import com.picturesAI.billing_service.entity.QuotaReservation;
import com.picturesAI.billing_service.entity.ReservationStatus;
import com.picturesAI.billing_service.repository.DailyUsageRepository;
import com.picturesAI.billing_service.repository.QuotaReservationRepository;
import com.picturesAI.billing_service.service.dto.ConfirmResult;
import com.picturesAI.billing_service.service.dto.ReleaseResult;
import com.picturesAI.billing_service.service.dto.ReserveResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private PlanService planService;

    @Mock
    private QuotaReservationRepository reservationRepository;

    @Mock
    private DailyUsageRepository dailyUsageRepository;

    @Mock
    private QuotaCounter quotaCounter;

    private SimpleMeterRegistry meterRegistry;
    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        quotaService = new QuotaService(
                planService, reservationRepository, dailyUsageRepository, quotaCounter, meterRegistry);
    }

    @Test
    void reserveAllowsWhenUnderLimit() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(reservationRepository.findById(generationId)).thenReturn(Optional.empty());
        when(planService.getOrCreate(userId)).thenReturn(Account.builder().id(userId).plan(Plan.FREE).build());
        when(planService.getDailyLimit(Plan.FREE)).thenReturn(10);
        when(planService.getReservationTtlMinutes()).thenReturn(15);
        when(quotaCounter.increment(userId, today)).thenReturn(1L);

        ReserveResult result = quotaService.reserve(userId, generationId);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(9);

        ArgumentCaptor<QuotaReservation> captor = ArgumentCaptor.forClass(QuotaReservation.class);
        verify(reservationRepository).saveAndFlush(captor.capture());
        QuotaReservation saved = captor.getValue();
        assertThat(saved.getGenerationId()).isEqualTo(generationId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(saved.getReservedDate()).isEqualTo(today);
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());

        verify(dailyUsageRepository).increment(userId, today);
        assertThat(meterRegistry.counter("billing_quota_reserved_total", "plan", "FREE").count()).isEqualTo(1.0);
    }

    @Test
    void reserveDeniesWhenOverLimit() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(reservationRepository.findById(generationId)).thenReturn(Optional.empty());
        when(planService.getOrCreate(userId)).thenReturn(Account.builder().id(userId).plan(Plan.FREE).build());
        when(planService.getDailyLimit(Plan.FREE)).thenReturn(10);
        when(quotaCounter.increment(userId, today)).thenReturn(11L);

        ReserveResult result = quotaService.reserve(userId, generationId);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        verify(quotaCounter).decrement(userId, today);
        verify(reservationRepository, never()).saveAndFlush(any());
        verify(dailyUsageRepository, never()).increment(any(), any());
        assertThat(meterRegistry.counter("billing_quota_exceeded_total", "plan", "FREE").count()).isEqualTo(1.0);
    }

    @Test
    void reserveIdempotentWhenAlreadyReserved() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        QuotaReservation existing = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(today)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.of(existing));
        when(planService.getOrCreate(userId)).thenReturn(Account.builder().id(userId).plan(Plan.FREE).build());
        when(planService.getDailyLimit(Plan.FREE)).thenReturn(10);
        when(quotaCounter.current(userId, today)).thenReturn(1L);

        ReserveResult result = quotaService.reserve(userId, generationId);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(9);
        verify(quotaCounter, never()).increment(any(), any());
        verify(quotaCounter, never()).decrement(any(), any());
        verify(reservationRepository, never()).saveAndFlush(any());
        verify(dailyUsageRepository, never()).increment(any(), any());
    }

    @Test
    void reserveIdempotentWhenAlreadyConfirmed() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        QuotaReservation existing = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(today)
                .status(ReservationStatus.CONFIRMED)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.of(existing));
        when(planService.getOrCreate(userId)).thenReturn(Account.builder().id(userId).plan(Plan.FREE).build());
        when(planService.getDailyLimit(Plan.FREE)).thenReturn(10);
        when(quotaCounter.current(userId, today)).thenReturn(3L);

        ReserveResult result = quotaService.reserve(userId, generationId);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(7);
        verify(quotaCounter, never()).increment(any(), any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void reserveDeniesWhenAlreadyReleased() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        QuotaReservation existing = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(today)
                .status(ReservationStatus.RELEASED)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.of(existing));

        ReserveResult result = quotaService.reserve(userId, generationId);

        assertThat(result.allowed()).isFalse();
        verify(quotaCounter, never()).increment(any(), any());
        verify(reservationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(dailyUsageRepository);
    }

    @Test
    void reserveUsesAccountPlanForLimit() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(reservationRepository.findById(generationId)).thenReturn(Optional.empty());
        when(planService.getOrCreate(userId)).thenReturn(Account.builder().id(userId).plan(Plan.PRO).build());
        when(planService.getDailyLimit(Plan.PRO)).thenReturn(100);
        when(planService.getReservationTtlMinutes()).thenReturn(15);
        when(quotaCounter.increment(userId, today)).thenReturn(5L);

        ReserveResult result = quotaService.reserve(userId, generationId);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(95);
        verify(planService).getDailyLimit(Plan.PRO);
        assertThat(meterRegistry.counter("billing_quota_reserved_total", "plan", "PRO").count()).isEqualTo(1.0);
    }

    @Test
    void confirmTransitionsReservedToConfirmed() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        QuotaReservation reservation = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(LocalDate.now(ZoneOffset.UTC))
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.of(reservation));
        when(planService.getPlan(userId)).thenReturn(Plan.FREE);

        ConfirmResult result = quotaService.confirm(generationId);

        assertThat(result.ok()).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getConfirmedAt()).isNotNull();
        verify(reservationRepository).save(reservation);
        assertThat(meterRegistry.counter("billing_quota_confirmed_total", "plan", "FREE").count()).isEqualTo(1.0);
    }

    @Test
    void confirmIdempotentWhenAlreadyConfirmed() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        QuotaReservation reservation = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(LocalDate.now(ZoneOffset.UTC))
                .status(ReservationStatus.CONFIRMED)
                .expiresAt(Instant.now().plusSeconds(600))
                .confirmedAt(Instant.now().minusSeconds(60))
                .build();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.of(reservation));

        ConfirmResult result = quotaService.confirm(generationId);

        assertThat(result.ok()).isTrue();
        verify(reservationRepository, never()).save(any());
        verifyNoInteractions(planService);
    }

    @Test
    void confirmNotFoundReturnsFalse() {
        UUID generationId = UUID.randomUUID();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.empty());

        ConfirmResult result = quotaService.confirm(generationId);

        assertThat(result.ok()).isFalse();
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void confirmReleasedReturnsFalse() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        QuotaReservation reservation = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(LocalDate.now(ZoneOffset.UTC))
                .status(ReservationStatus.RELEASED)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.of(reservation));

        ConfirmResult result = quotaService.confirm(generationId);

        assertThat(result.ok()).isFalse();
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void releaseTransitionsReservedToReleasedAndRestoresQuota() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        QuotaReservation reservation = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(today)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.of(reservation));
        when(planService.getPlan(userId)).thenReturn(Plan.FREE);

        ReleaseResult result = quotaService.release(generationId);

        assertThat(result.ok()).isTrue();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.getReleasedAt()).isNotNull();
        verify(reservationRepository).save(reservation);
        verify(quotaCounter).decrement(userId, today);
        verify(dailyUsageRepository).decrement(userId, today);
        assertThat(meterRegistry.counter("billing_quota_released_total",
                "plan", "FREE", "reason", "user").count()).isEqualTo(1.0);
    }

    @Test
    void releaseIdempotentWhenAlreadyReleased() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        QuotaReservation reservation = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(LocalDate.now(ZoneOffset.UTC))
                .status(ReservationStatus.RELEASED)
                .expiresAt(Instant.now().plusSeconds(600))
                .releasedAt(Instant.now().minusSeconds(30))
                .build();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.of(reservation));

        ReleaseResult result = quotaService.release(generationId);

        assertThat(result.ok()).isTrue();
        verify(reservationRepository, never()).save(any());
        verify(quotaCounter, never()).decrement(any(), any());
        verifyNoInteractions(dailyUsageRepository, planService);
    }

    @Test
    void releaseConfirmedReturnsFalse() {
        UUID generationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        QuotaReservation reservation = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(LocalDate.now(ZoneOffset.UTC))
                .status(ReservationStatus.CONFIRMED)
                .expiresAt(Instant.now().plusSeconds(600))
                .confirmedAt(Instant.now().minusSeconds(60))
                .build();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.of(reservation));

        ReleaseResult result = quotaService.release(generationId);

        assertThat(result.ok()).isFalse();
        verify(reservationRepository, never()).save(any());
        verify(quotaCounter, never()).decrement(any(), any());
    }

    @Test
    void releaseNotFoundReturnsFalse() {
        UUID generationId = UUID.randomUUID();
        when(reservationRepository.findById(generationId)).thenReturn(Optional.empty());

        ReleaseResult result = quotaService.release(generationId);

        assertThat(result.ok()).isFalse();
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void reserveTwiceSequentiallyCreatesOnlyOneReservation() {
        UUID userId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        when(planService.getOrCreate(userId)).thenReturn(Account.builder().id(userId).plan(Plan.FREE).build());
        when(planService.getDailyLimit(Plan.FREE)).thenReturn(10);
        when(planService.getReservationTtlMinutes()).thenReturn(15);
        when(quotaCounter.increment(userId, today)).thenReturn(1L);
        when(quotaCounter.current(userId, today)).thenReturn(1L);

        QuotaReservation created = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(today)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plusSeconds(600))
                .build();

        when(reservationRepository.findById(generationId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));

        ReserveResult first = quotaService.reserve(userId, generationId);
        ReserveResult second = quotaService.reserve(userId, generationId);

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isEqualTo(9);
        verify(reservationRepository, times(1)).saveAndFlush(any());
        verify(dailyUsageRepository, times(1)).increment(eq(userId), eq(today));
    }
}