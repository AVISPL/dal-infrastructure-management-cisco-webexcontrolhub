/*
 * Copyright (c) 2024 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.error;

import java.io.IOException;

/**
 * RetryError to indicate that the request needs to be retried after the {@link #retryAfter} seconds
 *
 * @author Maksym Rossiitsev
 * @since 1.0.0
 */
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
