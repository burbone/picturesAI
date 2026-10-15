package com.picturesAI.generation_service.service;

import com.picturesAI.generation_service.repository.GenerationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDeletedServiceTest {

    private final GenerationRepository generations = mock(GenerationRepository.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final UserDeletedService service = new UserDeletedService(generations, tx, registry);

    @Test
    void anonymizesAllGenerationsForUser() {
        UUID userId = UUID.randomUUID();
        when(generations.anonymizeByUserId(userId)).thenReturn(5);
        doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());

        service.anonymize(userId);

        assertThat(registry.counter("generation_user_deleted_anonymized_total").count())
                .isEqualTo(5.0);
    }

    @Test
    void recordsZeroWhenNoGenerations() {
        UUID userId = UUID.randomUUID();
        when(generations.anonymizeByUserId(userId)).thenReturn(0);
        doAnswer(inv -> {
            Consumer<TransactionStatus> cb = inv.getArgument(0);
            cb.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());

        service.anonymize(userId);

        assertThat(registry.counter("generation_user_deleted_anonymized_total").count())
                .isZero();
    }
}