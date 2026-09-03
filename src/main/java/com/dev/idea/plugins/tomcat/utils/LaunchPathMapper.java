package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The single seam between host-side paths and the form the launched JVM (and
 * Catalina, reading the descriptors DevTomcat writes) must see.
 *
 * <p>DevTomcat assembles {@code catalina.base} on the host and launches Tomcat's
 * {@code Bootstrap} directly. Every path string that reaches a JVM argument
 * ({@code -Dcatalina.home}, classpath entries, ...) or a context descriptor
 * ({@code docBase}, {@code <PreResources base=...>}) goes through
 * {@link #toTarget} instead of a raw {@code Path.toString()}. For an ordinary
 * host launch the mapper is {@link #IDENTITY} and the output is byte-identical
 * to before; in WSL mode a {@link WslPathTranslator} rewrites each path into the
 * distribution's Linux view. Passed explicitly — never a static global — so the
 * routing is provable with a recording fake.
 */
public interface LaunchPathMapper {

    /** The path as the JVM / Catalina must see it. */
    @NotNull
    String toTarget(@NotNull String hostPath);

    /** Separator for joining classpath entries on the target. */
    @NotNull
    String classpathSeparator();

    /** Translates every entry and joins with {@link #classpathSeparator()}. */
    @NotNull
    default String joinClasspath(@NotNull List<String> hostEntries) {
        return hostEntries.stream().map(this::toTarget)
                .collect(Collectors.joining(classpathSeparator()));
    }

    /** Host launch: paths unchanged, host classpath separator. */
    LaunchPathMapper IDENTITY = new LaunchPathMapper() {
        @Override
        public @NotNull String toTarget(@NotNull String hostPath) {
            return hostPath;
        }

        @Override
        public @NotNull String classpathSeparator() {
            return File.pathSeparator;
        }

        @Override
        public String toString() {
            return "LaunchPathMapper.IDENTITY";
        }
    };
}
