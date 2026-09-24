package com.dev.idea.plugins.tomcat.runner;

import com.intellij.execution.ConsoleFolding;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Folds JDK frames in Tomcat's leaked-thread stacks. Tomcat prints them without
 * {@code at} ({@code java.base@21/java.lang.Thread.run(Thread.java:1583)}), so the
 * platform's "at java." folding never sees them. Application frames stay visible.
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
