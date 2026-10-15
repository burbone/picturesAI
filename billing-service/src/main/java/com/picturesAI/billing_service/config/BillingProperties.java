package com.picturesAI.billing_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    private Map<String, PlanLimit> plans;
    private int reservationTtlMinutes;
    private long cleanupIntervalMs;

    @Getter
    @Setter
    public static class PlanLimit {
        private int dailyLimit;
    }
}