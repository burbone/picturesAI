package com.picturesAI.billing_service.service;

import java.time.LocalDate;
import java.util.UUID;

public interface QuotaCounter {

    long increment(UUID userId, LocalDate date);

    long decrement(UUID userId, LocalDate date);

    long current(UUID userId, LocalDate date);
}