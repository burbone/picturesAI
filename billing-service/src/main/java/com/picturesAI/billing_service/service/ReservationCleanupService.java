package com.picturesAI.billing_service.service;

import com.picturesAI.billing_service.entity.QuotaReservation;
import com.picturesAI.billing_service.entity.ReservationStatus;
import com.picturesAI.billing_service.repository.DailyUsageRepository;
import com.picturesAI.billing_service.repository.QuotaReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ReservationCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ReservationCleanupService.class);

    private final QuotaReservationRepository reservationRepository;
    private final DailyUsageRepository dailyUsageRepository;
    private final PlanService planService;
    private final MeterRegistry meterRegistry;

    public ReservationCleanupService(QuotaReservationRepository reservationRepository,
                                     DailyUsageRepository dailyUsageRepository,
                                     PlanService planService,
                                     MeterRegistry meterRegistry) {
        this.reservationRepository = reservationRepository;
        this.dailyUsageRepository = dailyUsageRepository;
        this.planService = planService;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${billing.cleanup-interval-ms}")
    @Transactional
    public void cleanupExpired() {
        Instant now = Instant.now();
        List<QuotaReservation> expired = reservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, now);
        if (expired.isEmpty()) {
            return;
        }
        for (QuotaReservation reservation : expired) {
            reservation.setStatus(ReservationStatus.RELEASED);
            reservation.setReleasedAt(now);
            dailyUsageRepository.decrement(reservation.getUserId(), reservation.getReservedDate());
            meterRegistry.counter("billing_quota_released_total",
                    "plan", planService.getPlan(reservation.getUserId()).name(),
                    "reason", "expired").increment();
        }
        reservationRepository.saveAll(expired);
        meterRegistry.counter("quota_reservations_expired_total").increment(expired.size());
        log.info("Released {} expired reservations", expired.size());
    }
}