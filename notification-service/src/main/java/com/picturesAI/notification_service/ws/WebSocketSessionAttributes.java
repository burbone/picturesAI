package com.picturesAI.notification_service.ws;

public final class WebSocketSessionAttributes {

    public static final String USER_ID = "userId";
    public static final String USERNAME = "username";
    public static final String ROLES = "roles";
    public static final String SUBSCRIPTION_TYPE = "subscriptionType";
    public static final String GENERATION_ID = "generationId";
    public static final String CLOSE_REASON = "closeReason";
    public static final String OUTBOUND_SINK = "outboundSink";

    public static final String SUBSCRIPTION_TYPE_GENERATION = "generation";
    public static final String SUBSCRIPTION_TYPE_USER = "user";

    public static final String CLOSE_REASON_NORMAL = "normal";
    public static final String CLOSE_REASON_ACCOUNT_DELETED = "account_deleted";
    public static final String CLOSE_REASON_SEND_ERROR = "send_error";

    private WebSocketSessionAttributes() {
    }
}