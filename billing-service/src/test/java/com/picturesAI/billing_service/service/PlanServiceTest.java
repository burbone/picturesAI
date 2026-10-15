package com.picturesAI.billing_service.service;

import com.picturesAI.billing_service.config.BillingProperties;
import com.picturesAI.billing_service.entity.Account;
import com.picturesAI.billing_service.entity.Plan;
import com.picturesAI.billing_service.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private BillingProperties billingProperties;
    private PlanService planService;

    @BeforeEach
    void setUp() {
        billingProperties = new BillingProperties();
        BillingProperties.PlanLimit free = new BillingProperties.PlanLimit();
        free.setDailyLimit(10);
        BillingProperties.PlanLimit pro = new BillingProperties.PlanLimit();
        pro.setDailyLimit(100);
        billingProperties.setPlans(Map.of("FREE", free, "PRO", pro));
        billingProperties.setReservationTtlMinutes(15);
        billingProperties.setCleanupIntervalMs(60000);
        planService = new PlanService(accountRepository, billingProperties);
    }

    @Test
    void getOrCreateReturnsExistingAccount() {
        UUID userId = UUID.randomUUID();
        Account existing = Account.builder().id(userId).plan(Plan.PRO).build();
        when(accountRepository.findById(userId)).thenReturn(Optional.of(existing));

        Account result = planService.getOrCreate(userId);

        assertThat(result).isSameAs(existing);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void getOrCreateCreatesFreeAccountWhenMissing() {
        UUID userId = UUID.randomUUID();
        when(accountRepository.findById(userId)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account result = planService.getOrCreate(userId);

        assertThat(result.getId()).isEqualTo(userId);
        assertThat(result.getPlan()).isEqualTo(Plan.FREE);
        verify(accountRepository).save(any(Account.class));
    }

    @Test
    void getPlanReturnsFreeWhenAccountMissing() {
        UUID userId = UUID.randomUUID();
        when(accountRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(planService.getPlan(userId)).isEqualTo(Plan.FREE);
    }

    @Test
    void getPlanReturnsStoredPlan() {
        UUID userId = UUID.randomUUID();
        when(accountRepository.findById(userId)).thenReturn(Optional.of(
                Account.builder().id(userId).plan(Plan.PRO).build()));

        assertThat(planService.getPlan(userId)).isEqualTo(Plan.PRO);
    }

    @Test
    void changePlanUpdatesExistingAccount() {
        UUID userId = UUID.randomUUID();
        Account existing = Account.builder().id(userId).plan(Plan.FREE).build();
        when(accountRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account result = planService.changePlan(userId, Plan.PRO);

        assertThat(result.getPlan()).isEqualTo(Plan.PRO);
        assertThat(result.getId()).isEqualTo(userId);
    }

    @Test
    void changePlanCreatesAccountWhenMissing() {
        UUID userId = UUID.randomUUID();
        when(accountRepository.findById(userId)).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account result = planService.changePlan(userId, Plan.PRO);

        assertThat(result.getId()).isEqualTo(userId);
        assertThat(result.getPlan()).isEqualTo(Plan.PRO);
    }

    @Test
    void getDailyLimitReturnsConfiguredValues() {
        assertThat(planService.getDailyLimit(Plan.FREE)).isEqualTo(10);
        assertThat(planService.getDailyLimit(Plan.PRO)).isEqualTo(100);
    }

    @Test
    void getDailyLimitThrowsWhenPlanNotConfigured() {
        billingProperties.setPlans(Map.of());

        assertThatThrownBy(() -> planService.getDailyLimit(Plan.FREE))
                .isInstanceOf(IllegalStateException.class);
    }
}