package com.picturesAI.billing_service.web;

import com.picturesAI.billing_service.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BillingDevControllerDisabledIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void meEndpointNotAvailableWithoutDevProfile() throws Exception {
        mockMvc.perform(get("/api/v1/billing/me").param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }
}