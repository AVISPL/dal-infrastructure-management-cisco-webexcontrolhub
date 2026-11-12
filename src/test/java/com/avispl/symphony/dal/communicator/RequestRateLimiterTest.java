/*
 * Copyright (c) 2026 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RequestRateLimiter}: pacing, shared pause on 429 Retry-After, rate changes
 */
public class RequestRateLimiterTest {

    @Test
    public void testPacesRequestsEvenly() throws Exception {
        // 600 req/min -> one request every 100 ms: 6 acquires take ~500 ms (first one is immediate)
        RequestRateLimiter limiter = new RequestRateLimiter(600);
        long start = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            limiter.acquire();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Assertions.assertTrue(elapsedMs >= 450, "Requests were not paced, elapsed " + elapsedMs + " ms");
        Assertions.assertTrue(elapsedMs < 1500, "Pacing was too slow, elapsed " + elapsedMs + " ms");
    }

    @Test
    public void testBudgetIsSharedBetweenThreads() throws Exception {
        // 4 threads x 3 requests at 600 req/min: 12 requests share one budget -> ~1.1 s, not ~0.2 s
        RequestRateLimiter limiter = new RequestRateLimiter(600);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            long start = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < 3; i++) {
                        limiter.acquire();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Assertions.assertTrue(elapsedMs >= 1000, "Threads did not share the budget, elapsed " + elapsedMs + " ms");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testPauseHoldsAllThreads() throws Exception {
        RequestRateLimiter limiter = new RequestRateLimiter(0);
        limiter.pause(1);
        Assertions.assertTrue(limiter.isPaused());

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            long start = System.nanoTime();
            List<Future<Long>> futures = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                futures.add(executor.submit(() -> {
                    limiter.acquire();
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                }));
            }
            for (Future<Long> future : futures) {
                long waitedMs = future.get(10, TimeUnit.SECONDS);
                Assertions.assertTrue(waitedMs >= 900, "A thread was not held by the pause, waited " + waitedMs + " ms");
            }
            Assertions.assertFalse(limiter.isPaused());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testShorterPauseDoesNotShortenLongerOne() throws Exception {
        RequestRateLimiter limiter = new RequestRateLimiter(0);
        limiter.pause(2);
        limiter.pause(0);
        long start = System.nanoTime();
        limiter.acquire();
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Assertions.assertTrue(waitedMs >= 1800, "Shorter pause cut the longer one, waited " + waitedMs + " ms");
    }

    @Test
    public void testPauseDuringReservedSlotIsRespected() throws Exception {
        // A thread already waiting for its slot must not go through once a pause starts
        RequestRateLimiter limiter = new RequestRateLimiter(60); // one request per second
        limiter.acquire(); // takes the immediate slot; next slot is ~1 s away
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            long start = System.nanoTime();
            Future<Long> waiter = executor.submit(() -> {
                limiter.acquire();
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            });
            Thread.sleep(200);
            limiter.pause(2);
            long waitedMs = waiter.get(10, TimeUnit.SECONDS);
            Assertions.assertTrue(waitedMs >= 2000, "Reserved request ignored the pause, waited " + waitedMs + " ms");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testNoLimitDoesNotWait() throws Exception {
        RequestRateLimiter limiter = new RequestRateLimiter(0);
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            limiter.acquire();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Assertions.assertTrue(elapsedMs < 500, "Unlimited limiter waited " + elapsedMs + " ms");
    }

    @Test
    public void testRateCanBeChanged() throws Exception {
        RequestRateLimiter limiter = new RequestRateLimiter(60);
        limiter.setRequestsPerMinute(0);
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Assertions.assertTrue(elapsedMs < 1500, "New rate was not applied, elapsed " + elapsedMs + " ms");
    }

    @Test
    public void testInterruptStopsWaiting() throws Exception {
        RequestRateLimiter limiter = new RequestRateLimiter(0);
        limiter.pause(30);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> waiter = executor.submit(() -> {
            limiter.acquire();
            return null;
        });
        Thread.sleep(100);
        executor.shutdownNow();
        Assertions.assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "acquire() did not stop on interrupt");
        Assertions.assertTrue(waiter.isDone());
    }
}
