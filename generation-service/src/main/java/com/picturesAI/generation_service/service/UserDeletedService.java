package com.picturesAI.generation_service.service;

import com.picturesAI.generation_service.repository.GenerationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class UserDeletedService {

    private static final Logger log = LoggerFactory.getLogger(UserDeletedService.class);

    private final GenerationRepository generations;
    private final TransactionTemplate tx;
    private final MeterRegistry meterRegistry;

    public UserDeletedService(GenerationRepository generations,
                              TransactionTemplate tx,
                              MeterRegistry meterRegistry) {
        this.generations = generations;
        this.tx = tx;
        this.meterRegistry = meterRegistry;
    }

    public void anonymize(UUID userId) {
        int[] updated = new int[1];
        tx.executeWithoutResult(s -> updated[0] = generations.anonymizeByUserId(userId));
        log.info("Anonymized {} generations for user {}", updated[0], userId);
        meterRegistry.counter("generation_user_deleted_anonymized_total").increment(updated[0]);
    }
}
