package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wall-clock breakdown of a multi-phase operation, for the run console.
 *
 * <p>A single "finished in N ms" total says a launch was slow; it does not say
 * where. Each phase records {@code System.nanoTime()} at its start and calls
 * {@link #record} when it ends, and {@link #summary()} renders one line such as
 * {@code ports 12 ms · catalina.base 340 ms · deployments 1,900 ms}, so a slow
 * launch is diagnosable from the console rather than by guesswork. Single-threaded
 * by design — one instance per operation, on the thread that runs it.
 */
public final class PhaseTimings {

    private final Map<String, Long> millisByPhase = new LinkedHashMap<>();

    /** Records {@code phase} as having taken the time since {@code startNanos}; repeats accumulate. */
    public void record(@NotNull String phase, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        millisByPhase.merge(phase, ms, Long::sum);
    }

    public boolean isEmpty() {
        return millisByPhase.isEmpty();
    }

    public long totalMs() {
        long total = 0;
        for (long ms : millisByPhase.values()) total += ms;
        return total;
    }

    /** {@code "a 12 ms · b 340 ms"} in recording order; empty string when nothing was recorded. */
    @NotNull
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : millisByPhase.entrySet()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(e.getKey()).append(' ').append(String.format("%,d", e.getValue())).append(" ms");
        }
        return sb.toString();
    }
}
