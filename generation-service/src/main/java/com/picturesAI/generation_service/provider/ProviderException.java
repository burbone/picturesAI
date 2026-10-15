package com.picturesAI.generation_service.provider;

public class ProviderException extends RuntimeException {

    private final int httpStatus;
    private final boolean retryable;

    public ProviderException(int httpStatus, String message) {
        this(httpStatus, message, isRetryableStatus(httpStatus), null);
    }

    public ProviderException(int httpStatus, String message, Throwable cause) {
        this(httpStatus, message, isRetryableStatus(httpStatus), cause);
    }

    public ProviderException(int httpStatus, String message, boolean retryable) {
        this(httpStatus, message, retryable, null);
    }

    public ProviderException(int httpStatus, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }
}