package com.picturesAI.generation_service.worker.consumer;

import com.picturesAI.generation_service.worker.userdeleted.UserDeletedConsumer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ConsumerLifecycle {

    private final ConsumerPro pro;
    private final ConsumerFree free;
    private final UserDeletedConsumer userDeleted;

    public ConsumerLifecycle(ConsumerPro pro,
                             ConsumerFree free,
                             UserDeletedConsumer userDeleted) {
        this.pro = pro;
        this.free = free;
        this.userDeleted = userDeleted;
    }

    @PostConstruct
    void start() {
        pro.start();
        free.start();
        userDeleted.start();
    }

    @PreDestroy
    void stop() {
        userDeleted.shutdown();
        pro.shutdown();
        free.shutdown();
    }
}