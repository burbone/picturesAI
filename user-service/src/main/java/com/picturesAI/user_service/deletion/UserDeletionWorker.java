package com.picturesAI.user_service.deletion;

import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.keycloak.KeycloakAdminClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "user.deletion.enabled", havingValue = "true", matchIfMissing = true)
public class UserDeletionWorker {

    private static final Logger log = LoggerFactory.getLogger(UserDeletionWorker.class);

    private final UserDeletionService userDeletionService;
    private final KeycloakAdminClient keycloakAdminClient;
    private final int batchSize;
    private final AtomicLong pendingGauge = new AtomicLong();
    private final Counter failedCounter;
    private final Timer deletionTimer;

    public UserDeletionWorker(UserDeletionService userDeletionService,
                              KeycloakAdminClient keycloakAdminClient,
                              UserDeletionProperties properties,
                              MeterRegistry meterRegistry) {
        this.userDeletionService = userDeletionService;
        this.keycloakAdminClient = keycloakAdminClient;
        this.batchSize = properties.getBatchSize();
        meterRegistry.gauge("user_deletion_pending_total", pendingGauge);
        this.failedCounter = meterRegistry.counter("user_deletion_failed_total");
        this.deletionTimer = meterRegistry.timer("user_deletion_duration_seconds");
    }

    @Scheduled(
            fixedDelayString = "${user.deletion.poll-interval-ms:30000}",
            initialDelayString = "${user.deletion.initial-delay-ms:30000}")
    public void scheduledProcess() {
        processPending();
    }

    public void processPending() {
        List<User> pending = userDeletionService.listPendingDeletions(batchSize);
        for (User user : pending) {
            boolean claimed = userDeletionService.markAttempt(user.getId());
            if (!claimed) {
                continue;
            }
            try {
                deletionTimer.record(() -> keycloakAdminClient.deleteUser(user.getKeycloakId()));
                userDeletionService.finalizeDeletion(user.getId());
            } catch (Exception e) {
                failedCounter.increment();
                log.warn("Keycloak deletion failed for userId={}, will retry next cycle",
                        user.getId(), e);
            }
        }
        pendingGauge.set(userDeletionService.countPending());
    }
}