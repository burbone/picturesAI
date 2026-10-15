package com.picturesAI.billing_service.nats;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "nats")
public class NatsProperties {

    private String url;
    private String user;
    private String password;
    private Subjects subjects = new Subjects();

    @Getter
    @Setter
    public static class Subjects {
        private String reserveQuota = "billing.reserveQuota";
        private String confirmQuota = "billing.confirmQuota";
        private String releaseQuota = "billing.releaseQuota";
    }
}