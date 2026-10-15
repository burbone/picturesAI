package com.picturesAI.billing_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "quota_reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotaReservation {

    @Id
    @Column(name = "generation_id", nullable = false)
    private UUID generationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "reserved_date", nullable = false)
    private LocalDate reservedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMP")
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP")
    private Instant createdAt;

    @Column(name = "confirmed_at", columnDefinition = "TIMESTAMP")
    private Instant confirmedAt;

    @Column(name = "released_at", columnDefinition = "TIMESTAMP")
    private Instant releasedAt;
}