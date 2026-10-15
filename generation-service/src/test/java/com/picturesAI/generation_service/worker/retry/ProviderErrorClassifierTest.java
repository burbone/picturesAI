package com.picturesAI.generation_service.worker.retry;

import com.picturesAI.generation_service.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderErrorClassifierTest {

    private final ProviderErrorClassifier classifier = new ProviderErrorClassifier();

    @Test
    void classifies429AsRateLimit() {
        assertThat(classifier.classify(new ProviderException(429, "slow down")))
                .isEqualTo(RetryAction.RETRY_RATE_LIMIT);
    }

    @Test
    void classifies500AsServerError() {
        assertThat(classifier.classify(new ProviderException(500, "boom")))
                .isEqualTo(RetryAction.RETRY_SERVER);
    }

    @Test
    void classifies503AsServerError() {
        assertThat(classifier.classify(new ProviderException(503, "unavailable")))
                .isEqualTo(RetryAction.RETRY_SERVER);
    }

    @Test
    void classifies400AsDlq() {
        assertThat(classifier.classify(new ProviderException(400, "bad")))
                .isEqualTo(RetryAction.DLQ);
    }

    @Test
    void classifies404AsDlq() {
        assertThat(classifier.classify(new ProviderException(404, "not found")))
                .isEqualTo(RetryAction.DLQ);
    }

    @Test
    void classifiesIoAsRetryServer() {
        assertThat(classifier.classify(new IOException("net")))
                .isEqualTo(RetryAction.RETRY_SERVER);
    }

    @Test
    void classifiesGenericAsDlq() {
        assertThat(classifier.classify(new RuntimeException("nope")))
                .isEqualTo(RetryAction.DLQ);
    }

    @Test
    void explicitNotRetryableOverridesStatus() {
        assertThat(classifier.classify(new ProviderException(500, "auth", false)))
                .isEqualTo(RetryAction.DLQ);
    }

    @Test
    void explicitRetryableOn4xxStillRetries() {
        assertThat(classifier.classify(new ProviderException(408, "timeout", true)))
                .isEqualTo(RetryAction.RETRY_SERVER);
    }

    @Test
    void keyErrorIsDlq() {
        assertThat(classifier.classify(new ProviderException(401, "invalid api key", false)))
                .isEqualTo(RetryAction.DLQ);
    }

    @Test
    void forbiddenIsDlq() {
        assertThat(classifier.classify(new ProviderException(403, "forbidden", false)))
                .isEqualTo(RetryAction.DLQ);
    }

    @Test
    void rateLimitIsRetryRateLimit() {
        assertThat(classifier.classify(new ProviderException(429, "slow down", true)))
                .isEqualTo(RetryAction.RETRY_RATE_LIMIT);
    }
}