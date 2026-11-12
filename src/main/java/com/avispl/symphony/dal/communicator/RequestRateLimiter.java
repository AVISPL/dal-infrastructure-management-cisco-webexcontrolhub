/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import java.util.concurrent.TimeUnit;

/**
 * Request budget shared by all collection threads of one adapter instance.
 * Requests are spaced evenly to stay within the configured requests-per-minute rate, and
 * {@link #pause(long)} holds every thread back when the API answers 429 with Retry-After,
 * instead of each thread retrying on its own.
 *
 * @author Maksym Rossiitsev
 * @since 1.0.4
 */
public class RequestRateLimiter {
    private long intervalNanos;
    private long nextSlot = System.nanoTime();
    private long pausedUntil = System.nanoTime();

    /**
     * @param requestsPerMinute requests allowed per minute, 0 or less for no limit
     */
    public RequestRateLimiter(int requestsPerMinute) {
        setRequestsPerMinute(requestsPerMinute);
    }

    /**
     * Change the request rate
     *
     * @param requestsPerMinute requests allowed per minute, 0 or less for no limit
     */
    public synchronized void setRequestsPerMinute(int requestsPerMinute) {
        intervalNanos = requestsPerMinute > 0 ? TimeUnit.MINUTES.toNanos(1) / requestsPerMinute : 0L;
    }

    /**
     * Block until the calling thread may send its next request
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        while (true) {
            long waitNanos;
            boolean reserved;
            synchronized (this) {
                long now = System.nanoTime();
                if (pausedUntil - now > 0) {
                    waitNanos = pausedUntil - now;
                    reserved = false;
                } else {
                    long slot = nextSlot - now > 0 ? nextSlot : now;
                    nextSlot = slot + intervalNanos;
                    waitNanos = slot - now;
                    reserved = true;
                }
            }
            if (waitNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            }
            if (reserved && !isPaused()) {
                return;
            }
        }
    }

    /**
     * Hold all requests back for the given time, e.g. the Retry-After value of a 429 response
     *
     * @param seconds pause duration in seconds
     */
    public synchronized void pause(long seconds) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(seconds, 0L));
        if (until - pausedUntil > 0) {
            pausedUntil = until;
        }
        if (pausedUntil - nextSlot > 0) {
            nextSlot = pausedUntil;
        }
    }

    /**
     * @return true while a {@link #pause(long)} is in effect
     */
    public synchronized boolean isPaused() {
        return pausedUntil - System.nanoTime() > 0;
    }
}
