package com.picturesAI.billing_service.service;

import com.picturesAI.billing_service.config.BillingProperties;
import com.picturesAI.billing_service.entity.Account;
import com.picturesAI.billing_service.entity.Plan;
import com.picturesAI.billing_service.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PlanService {

    private final AccountRepository accountRepository;
    private final BillingProperties billingProperties;

    public PlanService(AccountRepository accountRepository, BillingProperties billingProperties) {
        this.accountRepository = accountRepository;
        this.billingProperties = billingProperties;
    }

    @Transactional
    public Account getOrCreate(UUID userId) {
        return accountRepository.findById(userId)
                .orElseGet(() -> accountRepository.save(Account.builder()
                        .id(userId)
                        .plan(Plan.FREE)
                        .build()));
    }

    @Transactional(readOnly = true)
    public Plan getPlan(UUID userId) {
        return accountRepository.findById(userId)
                .map(Account::getPlan)
                .orElse(Plan.FREE);
    }

    @Transactional
    public Account changePlan(UUID userId, Plan plan) {
        Account account = accountRepository.findById(userId)
                .orElseGet(() -> Account.builder().id(userId).plan(Plan.FREE).build());
        account.setPlan(plan);
        return accountRepository.save(account);
    }

    public int getDailyLimit(Plan plan) {
        BillingProperties.PlanLimit limit = billingProperties.getPlans().get(plan.name());
        if (limit == null) {
            throw new IllegalStateException("No daily limit configured for plan " + plan);
        }
        return limit.getDailyLimit();
    }

    public int getReservationTtlMinutes() {
        return billingProperties.getReservationTtlMinutes();
    }

    public Instant now() {
        return Instant.now();
    }
}