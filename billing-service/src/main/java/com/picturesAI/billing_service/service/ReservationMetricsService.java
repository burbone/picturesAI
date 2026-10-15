package com.picturesAI.billing_service.service;

import com.picturesAI.billing_service.entity.ReservationStatus;
import com.picturesAI.billing_service.repository.QuotaReservationRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Profile("!test")
public class ReservationMetricsService {

    private final QuotaReservationRepository reservationRepository;
    private final AtomicLong activeReservations = new AtomicLong(0);

    public ReservationMetricsService(QuotaReservationRepository reservationRepository,
                                     MeterRegistry meterRegistry) {
        this.reservationRepository = reservationRepository;
        Gauge.builder("billing_reservations_active", activeReservations, AtomicLong::get)
                .description("Number of active (RESERVED) reservations")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${billing.cleanup-interval-ms}")
    public void updateActiveReservations() {
        Instant now = Instant.now();
        long count = reservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, now.plusSeconds(86400))
                .size();
        activeReservations.set(count);
    }
}