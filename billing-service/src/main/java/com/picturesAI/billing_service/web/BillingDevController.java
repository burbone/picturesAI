package com.picturesAI.billing_service.web;

import com.picturesAI.billing_service.config.BillingProperties;
import com.picturesAI.billing_service.entity.Account;
import com.picturesAI.billing_service.entity.Plan;
import com.picturesAI.billing_service.entity.QuotaReservation;
import com.picturesAI.billing_service.entity.ReservationStatus;
import com.picturesAI.billing_service.repository.DailyUsageRepository;
import com.picturesAI.billing_service.repository.QuotaReservationRepository;
import com.picturesAI.billing_service.service.PlanService;
import com.picturesAI.billing_service.service.QuotaCounter;
import com.picturesAI.billing_service.web.dto.MeResponse;
import com.picturesAI.billing_service.web.dto.PlanResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@Profile("dev")
@RequestMapping("/api/v1")
public class BillingDevController {

    private final PlanService planService;
    private final QuotaReservationRepository reservationRepository;
    private final DailyUsageRepository dailyUsageRepository;
    private final QuotaCounter quotaCounter;
    private final BillingProperties billingProperties;

    public BillingDevController(PlanService planService,
                                QuotaReservationRepository reservationRepository,
                                DailyUsageRepository dailyUsageRepository,
                                QuotaCounter quotaCounter,
                                BillingProperties billingProperties) {
        this.planService = planService;
        this.reservationRepository = reservationRepository;
        this.dailyUsageRepository = dailyUsageRepository;
        this.quotaCounter = quotaCounter;
        this.billingProperties = billingProperties;
    }

    @GetMapping("/billing/me")
    public MeResponse me(@RequestParam("userId") UUID userId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Account account = planService.getOrCreate(userId);
        int limit = planService.getDailyLimit(account.getPlan());
        int used = dailyUsageRepository.findById(
                        new com.picturesAI.billing_service.entity.DailyUsageId(userId, today))
                .map(u -> u.getUsedCount())
                .orElse(0);
        long counterValue = quotaCounter.current(userId, today);
        int usedEffective = (int) Math.max(used, counterValue);
        int remaining = Math.max(0, limit - usedEffective);

        List<QuotaReservation> active = reservationRepository
                .findByUserIdAndReservedDateAndStatus(userId, today, ReservationStatus.RESERVED);

        List<MeResponse.ActiveReservation> activeDtos = active.stream()
                .map(r -> new MeResponse.ActiveReservation(r.getGenerationId(), r.getExpiresAt().toString()))
                .collect(Collectors.toList());

        return new MeResponse(userId, account.getPlan().name(), limit, usedEffective, remaining, today, activeDtos);
    }

    @GetMapping("/billing/plans")
    public PlanResponse plans() {
        List<PlanResponse.PlanItem> items = billingProperties.getPlans().entrySet().stream()
                .map(e -> new PlanResponse.PlanItem(e.getKey(), e.getValue().getDailyLimit()))
                .collect(Collectors.toList());
        return new PlanResponse(items);
    }

    @PostMapping("/internal/billing/plan/{userId}")
    public MeResponse changePlan(@PathVariable("userId") UUID userId,
                                 @RequestParam("plan") Plan plan) {
        planService.changePlan(userId, plan);
        return me(userId);
    }
}