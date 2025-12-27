package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Filesystem operations with proper error handling.
 *
 * @author Gezahegn Lemma
 */
public final class FileSystemUtils {

    private static final Logger LOG = Logger.getInstance(FileSystemUtils.class);

    private FileSystemUtils() {}

    public static void deleteDirectoryContents(@NotNull Path dir) throws IOException {
        Objects.requireNonNull(dir);
        if (!Files.exists(dir)) return;

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> !p.equals(dir))
                 .sorted((a, b) -> b.compareTo(a))
                 .forEach(path -> {
                     try {
                         Files.deleteIfExists(path);
                     } catch (IOException e) {
                         LOG.warn("Could not delete: " + path, e);
                     }
                 });
        }
    }

    public static void cleanWorkDirectory(@NotNull Path workDir) throws IOException {
        Objects.requireNonNull(workDir);
        if (!Files.exists(workDir)) return;

        try (Stream<Path> paths = Files.walk(workDir)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> !p.toString().endsWith(".ser"))
                 .forEach(path -> {
                     try {
                         Files.deleteIfExists(path);
                     } catch (IOException e) {
                         LOG.warn("Could not delete: " + path, e);
                     }
                 });
        }
    }

    public static boolean isEmptyDirectory(@NotNull Path dir) {
        Objects.requireNonNull(dir);
        try {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) return true;
            try (Stream<Path> entries = Files.list(dir)) {
                return !entries.findFirst().isPresent();
            }
        } catch (IOException e) {
            return true;
        }
    }

    public static boolean createDirectoryIfNotExists(@NotNull Path dir) throws IOException {
        Objects.requireNonNull(dir);
        if (Files.exists(dir) && Files.isDirectory(dir)) return false;
        Files.createDirectories(dir);
        return true;
    }

    public static long getDirectorySize(@NotNull Path dir) throws IOException {
        Objects.requireNonNull(dir);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return 0;

        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try { return Files.size(p); }
                            catch (IOException e) { return 0; }
                        })
                        .sum();
        }
    }

    @NotNull
    public static String sanitizeName(@Nullable String name, @NotNull String defaultValue) {
        Objects.requireNonNull(defaultValue);
        if (name == null || name.trim().isEmpty()) return defaultValue;
        return name.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}