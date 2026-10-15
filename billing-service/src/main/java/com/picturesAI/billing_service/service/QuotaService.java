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
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
public class QuotaService {

    private final PlanService planService;
    private final QuotaReservationRepository reservationRepository;
    private final DailyUsageRepository dailyUsageRepository;
    private final QuotaCounter quotaCounter;
    private final MeterRegistry meterRegistry;

    public QuotaService(PlanService planService,
                        QuotaReservationRepository reservationRepository,
                        DailyUsageRepository dailyUsageRepository,
                        QuotaCounter quotaCounter,
                        MeterRegistry meterRegistry) {
        this.planService = planService;
        this.reservationRepository = reservationRepository;
        this.dailyUsageRepository = dailyUsageRepository;
        this.quotaCounter = quotaCounter;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ReserveResult reserve(UUID userId, UUID generationId) {
        Optional<QuotaReservation> existing = reservationRepository.findById(generationId);
        if (existing.isPresent()) {
            return existingReservationResult(existing.get(), userId);
        }

        Account account = planService.getOrCreate(userId);
        Plan plan = account.getPlan();
        int limit = planService.getDailyLimit(plan);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        long current = quotaCounter.increment(userId, today);
        if (current > limit) {
            quotaCounter.decrement(userId, today);
            meterRegistry.counter("billing_quota_exceeded_total", "plan", plan.name()).increment();
            return new ReserveResult(false, 0);
        }

        QuotaReservation reservation = QuotaReservation.builder()
                .generationId(generationId)
                .userId(userId)
                .reservedDate(today)
                .status(ReservationStatus.RESERVED)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(planService.getReservationTtlMinutes())))
                .build();
        reservationRepository.saveAndFlush(reservation);
        dailyUsageRepository.increment(userId, today);
        meterRegistry.counter("billing_quota_reserved_total", "plan", plan.name()).increment();

        return new ReserveResult(true, (int) Math.max(0L, limit - current));
    }

    @Transactional
    public ConfirmResult confirm(UUID generationId) {
        Optional<QuotaReservation> opt = reservationRepository.findById(generationId);
        if (opt.isEmpty()) {
            return new ConfirmResult(false);
        }
        QuotaReservation reservation = opt.get();
        return switch (reservation.getStatus()) {
            case RESERVED -> {
                reservation.setStatus(ReservationStatus.CONFIRMED);
                reservation.setConfirmedAt(Instant.now());
                reservationRepository.save(reservation);
                Plan plan = planService.getPlan(reservation.getUserId());
                meterRegistry.counter("billing_quota_confirmed_total", "plan", plan.name()).increment();
                yield new ConfirmResult(true);
            }
            case CONFIRMED -> new ConfirmResult(true);
            case RELEASED -> new ConfirmResult(false);
        };
    }

    @Transactional
    public ReleaseResult release(UUID generationId) {
        Optional<QuotaReservation> opt = reservationRepository.findById(generationId);
        if (opt.isEmpty()) {
            return new ReleaseResult(false);
        }
        QuotaReservation reservation = opt.get();
        return switch (reservation.getStatus()) {
            case RESERVED -> {
                reservation.setStatus(ReservationStatus.RELEASED);
                reservation.setReleasedAt(Instant.now());
                reservationRepository.save(reservation);
                quotaCounter.decrement(reservation.getUserId(), reservation.getReservedDate());
                dailyUsageRepository.decrement(reservation.getUserId(), reservation.getReservedDate());
                Plan plan = planService.getPlan(reservation.getUserId());
                meterRegistry.counter("billing_quota_released_total",
                        "plan", plan.name(), "reason", "user").increment();
                yield new ReleaseResult(true);
            }
            case RELEASED -> new ReleaseResult(true);
            case CONFIRMED -> new ReleaseResult(false);
        };
    }

    private ReserveResult existingReservationResult(QuotaReservation reservation, UUID userId) {
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return new ReserveResult(false, 0);
        }
        Account account = planService.getOrCreate(userId);
        int limit = planService.getDailyLimit(account.getPlan());
        long current = quotaCounter.current(userId, reservation.getReservedDate());
        return new ReserveResult(true, (int) Math.max(0L, limit - current));
    }
}