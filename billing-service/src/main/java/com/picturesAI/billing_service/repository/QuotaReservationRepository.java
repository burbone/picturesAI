package com.picturesAI.billing_service.repository;

import com.picturesAI.billing_service.entity.QuotaReservation;
import com.picturesAI.billing_service.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface QuotaReservationRepository extends JpaRepository<QuotaReservation, UUID> {

    List<QuotaReservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant now);

    long countByUserIdAndReservedDateAndStatus(UUID userId, LocalDate reservedDate, ReservationStatus status);

    List<QuotaReservation> findByUserIdAndReservedDateAndStatus(UUID userId, LocalDate reservedDate, ReservationStatus status);
}