package com.picturesAI.billing_service.web;

import com.picturesAI.billing_service.AbstractPostgresIT;
import com.picturesAI.billing_service.service.QuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MetricsIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QuotaService quotaService;

    @Test
    void reserveConfirmReleaseExposeMetricsInPrometheus() throws Exception {
        UUID userId = UUID.randomUUID();

        UUID g1 = UUID.randomUUID();
        quotaService.reserve(userId, g1);
        quotaService.confirm(g1);

        UUID g2 = UUID.randomUUID();
        quotaService.reserve(userId, g2);
        quotaService.release(g2);

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("billing_quota_reserved_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("billing_quota_confirmed_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("billing_quota_released_total")));
    }

    @Test
    void exceededMetricAppearsAfterDenial() throws Exception {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            quotaService.reserve(userId, UUID.randomUUID());
        }
        quotaService.reserve(userId, UUID.randomUUID());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("billing_quota_exceeded_total")));
    }
}