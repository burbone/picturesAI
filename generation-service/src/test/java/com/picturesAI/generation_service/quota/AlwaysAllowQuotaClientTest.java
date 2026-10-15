package com.picturesAI.generation_service.quota;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlwaysAllowQuotaClientTest {

    private final AlwaysAllowQuotaClient client = new AlwaysAllowQuotaClient();

    @Test
    void reserveAlwaysAllows() {
        assertThat(client.reserve(UUID.randomUUID(), UUID.randomUUID())).isTrue();
    }

    @Test
    void confirmAndReleaseAreNoop() {
        UUID id = UUID.randomUUID();
        client.confirm(id);
        client.release(id);
    }
}