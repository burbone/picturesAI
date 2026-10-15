package com.picturesAI.billing_service.web;

import com.picturesAI.billing_service.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("dev")
class BillingDevControllerIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void meReturnsFreePlanByDefault() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/billing/me").param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.dailyLimit").value(10))
                .andExpect(jsonPath("$.usedToday").value(0))
                .andExpect(jsonPath("$.remaining").value(10))
                .andExpect(jsonPath("$.activeReservations").isArray());
    }

    @Test
    void plansReturnsConfiguredPlans() throws Exception {
        mockMvc.perform(get("/api/v1/billing/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[?(@.name == 'FREE')].dailyLimit").value(10))
                .andExpect(jsonPath("$.plans[?(@.name == 'PRO')].dailyLimit").value(100));
    }

    @Test
    void changePlanUpdatesAccountAndReflectsInMe() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/internal/billing/plan/" + userId).param("plan", "PRO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.dailyLimit").value(100));

        mockMvc.perform(get("/api/v1/billing/me").param("userId", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PRO"))
                .andExpect(jsonPath("$.dailyLimit").value(100));
    }
}