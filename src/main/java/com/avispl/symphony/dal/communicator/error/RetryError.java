package com.avispl.symphony.dal.communicator.error;

import java.io.IOException;

public class RetryError extends IOException {
    private Long retryAfter;
    public RetryError(Long retryAfter) {
        this.retryAfter = retryAfter;
    }

    /**
     * Retrieves {@link #retryAfter}
     *
     * @return value of {@link #retryAfter}
     */
    public Long getRetryAfter() {
        return retryAfter;
    }
}
