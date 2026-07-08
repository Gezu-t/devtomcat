package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit coverage for {@code readJavaVersion}, the pure {@code release}-file parser
 * behind the Add/Edit JDK dialog. Runs without a platform fixture — the dialog's
 * Swing wiring is not exercised. Guards the fix that stopped seeding the Version
 * and Name fields with the literal status string {@code "JDK detected"}.
 */
@DisplayName("JdkEditorDialog.readJavaVersion")
class JdkEditorDialogTest {

    private static File writeRelease(Path dir, String contents) throws IOException {
        File release = dir.resolve("release").toFile();
        Files.writeString(release.toPath(), contents, StandardCharsets.UTF_8);
        return release;
    }

    @Test
    @DisplayName("parses a quoted JAVA_VERSION value")
    void parsesQuotedVersion(@TempDir Path dir) throws IOException {
        File release = writeRelease(dir,
                "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"17.0.10\"\nOS_ARCH=\"aarch64\"\n");
        assertEquals("17.0.10", JdkEditorDialog.readJavaVersion(release));
    }

    @Test
    @DisplayName("parses an unquoted JAVA_VERSION value")
    void parsesUnquotedVersion(@TempDir Path dir) throws IOException {
        File release = writeRelease(dir, "JAVA_VERSION=21\n");
        assertEquals("21", JdkEditorDialog.readJavaVersion(release));
    }

    @Test
    @DisplayName("returns null when no JAVA_VERSION line is present")
    void nullWhenAbsent(@TempDir Path dir) throws IOException {
        File release = writeRelease(dir, "IMPLEMENTOR=\"Vendor\"\nOS_NAME=\"Darwin\"\n");
        assertNull(JdkEditorDialog.readJavaVersion(release));
    }

    @Test
    @DisplayName("returns null for an empty quoted value rather than an empty string")
    void nullWhenBlankValue(@TempDir Path dir) throws IOException {
        File release = writeRelease(dir, "JAVA_VERSION=\"\"\n");
        assertNull(JdkEditorDialog.readJavaVersion(release));
    }

    @Test
    @DisplayName("returns null when the release file is missing")
    void nullWhenMissing(@TempDir Path dir) {
        assertNull(JdkEditorDialog.readJavaVersion(dir.resolve("release").toFile()));
    }
}
