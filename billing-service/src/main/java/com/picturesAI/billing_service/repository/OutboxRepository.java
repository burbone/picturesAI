package com.picturesAI.billing_service.repository;

import com.picturesAI.billing_service.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxRepository extends JpaRepository<Outbox, UUID> {
}