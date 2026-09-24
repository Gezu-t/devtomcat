package com.dev.idea.plugins.tomcat.runner;

import com.intellij.execution.ConsoleFolding;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Folds JDK frames in the thread stacks Tomcat prints when a stopped web
 * application left a thread running ("appears to have started a thread named
 * [...] but has failed to stop it. Stack trace of thread:").
 *
 * <p>Tomcat prints those frames as a space plus {@code StackTraceElement.toString()}
 * — no {@code at}, module qualifier first — so the platform's own JDK-frame
 * folding, which keys on {@code "at java."}, never sees them:
 * <pre>
 *  java.base@21/jdk.internal.misc.Unsafe.park(Native Method)
 *  java.base@21/java.util.concurrent.locks.LockSupport.park(LockSupport.java:371)
 *  com.example.jobs.Poller.run(Poller.java:58)                    &lt;-- kept visible
 *  java.base@21/java.lang.Thread.run(Thread.java:1583)
 * </pre>
 * A leaked pool thread is a dozen JDK frames deep; the one frame that says
 * which code started it is the application frame, and that stays visible.
 * Only frames whose class is in a {@code java.}, {@code jdk.} or {@code sun.}
 * package fold, with or without the {@code module@version/} qualifier (Java 8
 * prints the bare form). The frame must end in {@code method(...)} so a log
 * line that merely starts with a JDK class name never folds.
 */
public final class ThreadDumpJdkFrameFolding extends ConsoleFolding {

    private static final Pattern JDK_FRAME = Pattern.compile(
            "^\\s*+(?:platform/)?+(?:(?:java|jdk)\\.[\\w.]++(?:@[^/\\s]++)?+/)?+"
                    + "(?:java|jdk|sun)\\.(?:[\\w$]++\\.)*+[\\w$<>]++\\([^)]*+\\)\\s*+$");

    @Override
    public boolean shouldFoldLine(@NotNull Project project, @NotNull String line) {
        return JDK_FRAME.matcher(line).matches();
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull Project project, @NotNull List<String> lines) {
        return "  <" + lines.size() + " JDK frames>";
    }
}
