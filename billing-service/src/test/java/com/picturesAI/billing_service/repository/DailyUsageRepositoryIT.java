package com.picturesAI.billing_service.repository;

import com.picturesAI.billing_service.AbstractPostgresIT;
import com.picturesAI.billing_service.entity.DailyUsage;
import com.picturesAI.billing_service.entity.DailyUsageId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DailyUsageRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private DailyUsageRepository repository;

    @Test
    void incrementCreatesRowOnFirstCall() {
        UUID userId = UUID.randomUUID();
        LocalDate date = LocalDate.now();

        repository.increment(userId, date);

        Optional<DailyUsage> found = repository.findById(new DailyUsageId(userId, date));
        assertThat(found).isPresent();
        assertThat(found.get().getUsedCount()).isEqualTo(1);
    }

    @Test
    void incrementAccumulatesOnConflict() {
        UUID userId = UUID.randomUUID();
        LocalDate date = LocalDate.now();

        repository.increment(userId, date);
        repository.increment(userId, date);
        repository.increment(userId, date);

        Optional<DailyUsage> found = repository.findById(new DailyUsageId(userId, date));
        assertThat(found).isPresent();
        assertThat(found.get().getUsedCount()).isEqualTo(3);
    }

    @Test
    void decrementReducesCount() {
        UUID userId = UUID.randomUUID();
        LocalDate date = LocalDate.now();

        repository.increment(userId, date);
        repository.increment(userId, date);
        repository.decrement(userId, date);

        Optional<DailyUsage> found = repository.findById(new DailyUsageId(userId, date));
        assertThat(found).isPresent();
        assertThat(found.get().getUsedCount()).isEqualTo(1);
    }

    @Test
    void decrementClampsAtZero() {
        UUID userId = UUID.randomUUID();
        LocalDate date = LocalDate.now();

        repository.increment(userId, date);
        repository.decrement(userId, date);
        repository.decrement(userId, date);

        Optional<DailyUsage> found = repository.findById(new DailyUsageId(userId, date));
        assertThat(found).isPresent();
        assertThat(found.get().getUsedCount()).isEqualTo(0);
    }
}