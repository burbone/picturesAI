package com.picturesAI.generation_service.worker.retry;

import com.picturesAI.generation_service.provider.ProviderException;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ProviderErrorClassifier {

    public RetryAction classify(Throwable t) {
        if (t instanceof ProviderException pe) {
            if (!pe.retryable()) {
                return RetryAction.DLQ;
            }
            int status = pe.httpStatus();
            if (status == 429) {
                return RetryAction.RETRY_RATE_LIMIT;
            }
            return RetryAction.RETRY_SERVER;
        }
        if (t instanceof IOException) {
            return RetryAction.RETRY_SERVER;
        }
        return RetryAction.DLQ;
    }
}