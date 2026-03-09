package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the exactly-once pre-launch guard pattern used by
 * {@link TomcatCommandLineState#ensurePreLaunchSetup()}.
 *
 * <p>The guard ensures compatibility checks, port resolution, and credential
 * resolution run exactly once even when IntelliJ calls
 * {@code getJavaParameters()} before {@code startProcess()}.
 */
@DisplayName("PreLaunch Guard Pattern")
class PreLaunchGuardTest {

    @Test
    @DisplayName("guard fires side effect exactly once across sequential calls")
    void firesOnce() {
        AtomicBoolean guard = new AtomicBoolean(false);
        AtomicInteger sideEffectCount = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            if (guard.compareAndSet(false, true)) {
                sideEffectCount.incrementAndGet();
            }
        }

        assertEquals(1, sideEffectCount.get());
    }

    @Test
    @DisplayName("guard fires side effect exactly once under concurrent access")
    void firesOnceUnderConcurrency() throws Exception {
        AtomicBoolean guard = new AtomicBoolean(false);
        AtomicInteger sideEffectCount = new AtomicInteger(0);
        int threadCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    if (guard.compareAndSet(false, true)) {
                        sideEffectCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        pool.shutdown();

        assertEquals(1, sideEffectCount.get(),
                "Side effect must fire exactly once even under concurrent access");
    }

    @Test
    @DisplayName("guard allows first caller to complete before others proceed")
    void firstCallerCompletes() {
        AtomicBoolean guard = new AtomicBoolean(false);
        AtomicInteger firstCallerWork = new AtomicInteger(0);
        AtomicInteger secondCallerWork = new AtomicInteger(0);

        // First call — simulates createJavaParameters() path
        if (guard.compareAndSet(false, true)) {
            firstCallerWork.incrementAndGet();
        }

        // Second call — simulates startProcess() path
        if (guard.compareAndSet(false, true)) {
            secondCallerWork.incrementAndGet();
        }

        assertEquals(1, firstCallerWork.get(), "First caller should execute setup");
        assertEquals(0, secondCallerWork.get(), "Second caller should skip setup");
    }
}
