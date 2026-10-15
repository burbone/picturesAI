package com.picturesAI.billing_service.repository;

import com.picturesAI.billing_service.AbstractPostgresIT;
import com.picturesAI.billing_service.entity.Account;
import com.picturesAI.billing_service.entity.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private AccountRepository repository;

    @Test
    void savesAndFindsAccount() {
        UUID userId = UUID.randomUUID();
        Account saved = repository.saveAndFlush(Account.builder().id(userId).plan(Plan.FREE).build());

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Optional<Account> found = repository.findById(userId);
        assertThat(found).isPresent();
        assertThat(found.get().getPlan()).isEqualTo(Plan.FREE);
    }

    @Test
    void updatesPlanAndTimestamp() {
        UUID userId = UUID.randomUUID();
        repository.saveAndFlush(Account.builder().id(userId).plan(Plan.FREE).build());

        Account account = repository.findById(userId).orElseThrow();
        Instant before = account.getUpdatedAt();
        account.setPlan(Plan.PRO);
        repository.saveAndFlush(account);

        Account reloaded = repository.findById(userId).orElseThrow();
        assertThat(reloaded.getPlan()).isEqualTo(Plan.PRO);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(before);
    }
}