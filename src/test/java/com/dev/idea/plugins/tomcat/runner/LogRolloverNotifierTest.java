package com.dev.idea.plugins.tomcat.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-math piece of {@link LogRolloverNotifier} —
 * {@code millisUntilNextMidnight()}. The actual alarm scheduling and
 * balloon emission rely on the platform's {@code Alarm} and
 * {@code TomcatNotifier} bus, which are integration-tested elsewhere.
 *
 * <p>These tests pin the contract that the notifier never schedules an
 * alarm at a non-positive delay (would fire immediately) and never
 * schedules past 24 hours (would skip a day on a wall-clock edge case).
 */
class LogRolloverNotifierTest {

    @Test
    @DisplayName("millisUntilNextMidnight is strictly positive")
    void positive() {
        long ms = LogRolloverNotifier.millisUntilNextMidnight();
        assertTrue(ms > 0, "delay must never be zero or negative; got " + ms);
    }

    @Test
    @DisplayName("millisUntilNextMidnight is at most 24 hours")
    void atMost24Hours() {
        long ms = LogRolloverNotifier.millisUntilNextMidnight();
        long oneDayMs = 24L * 60 * 60 * 1000;
        assertTrue(ms <= oneDayMs, "delay must be within 24 hours; got " + ms);
    }

    @Test
    @DisplayName("now + delay lands within one second of midnight")
    void landsAtMidnight() {
        // Snapshot before reading the delay so the assertion is tight against
        // the same wall-clock moment the notifier observed.
        LocalDateTime before = LocalDateTime.now();
        long ms = LogRolloverNotifier.millisUntilNextMidnight();
        LocalDateTime target = before.plus(ms, ChronoUnit.MILLIS);

        // The target should be tomorrow's 00:00:00 — give a 2-second slack
        // for the clock advancing between the two now() calls inside the
        // method and inside this test.
        LocalTime targetTime = target.toLocalTime();
        long secondsFromMidnight = Math.min(
                targetTime.toSecondOfDay(),
                86400 - targetTime.toSecondOfDay());
        assertTrue(secondsFromMidnight <= 2,
                "target time should land at or near midnight; got " + targetTime
                        + " (drift " + secondsFromMidnight + "s)");
    }

    @Test
    @DisplayName("repeated calls return a monotonically decreasing delay within the same second")
    void monotonicWithinSecond() {
        long first = LogRolloverNotifier.millisUntilNextMidnight();
        long second = LogRolloverNotifier.millisUntilNextMidnight();
        // Second call happens after the first, so its delay must be <= first.
        // Use <= because the two calls may share the same millisecond.
        assertTrue(second <= first,
                "second call's delay should not exceed the first's; first=" + first
                        + " second=" + second);
    }
}
