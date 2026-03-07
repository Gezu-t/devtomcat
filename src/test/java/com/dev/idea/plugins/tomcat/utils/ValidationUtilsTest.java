package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationUtils")
class ValidationUtilsTest {

    @Nested
    @DisplayName("isValidFile")
    class IsValidFile {

        @Test
        @DisplayName("null returns false")
        void nullPath() {
            assertFalse(ValidationUtils.isValidFile(null));
        }

        @Test
        @DisplayName("empty returns false")
        void emptyPath() {
            assertFalse(ValidationUtils.isValidFile(""));
        }

        @Test
        @DisplayName("nonexistent path returns false")
        void nonexistent() {
            assertFalse(ValidationUtils.isValidFile("/no/such/file/xyz123.txt"));
        }

        @Test
        @DisplayName("existing file returns true")
        void existingFile(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("test.txt"));
            assertTrue(ValidationUtils.isValidFile(file.toString()));
        }

        @Test
        @DisplayName("directory is not a valid file")
        void directoryNotFile(@TempDir Path tempDir) {
            assertFalse(ValidationUtils.isValidFile(tempDir.toString()));
        }
    }

    @Nested
    @DisplayName("isValidDirectory")
    class IsValidDirectory {

        @Test
        @DisplayName("null returns false")
        void nullPath() {
            assertFalse(ValidationUtils.isValidDirectory(null));
        }

        @Test
        @DisplayName("empty returns false")
        void emptyPath() {
            assertFalse(ValidationUtils.isValidDirectory(""));
        }

        @Test
        @DisplayName("nonexistent path returns false")
        void nonexistent() {
            assertFalse(ValidationUtils.isValidDirectory("/no/such/dir/xyz123"));
        }

        @Test
        @DisplayName("existing directory returns true")
        void existingDir(@TempDir Path tempDir) {
            assertTrue(ValidationUtils.isValidDirectory(tempDir.toString()));
        }

        @Test
        @DisplayName("file is not a valid directory")
        void fileNotDir(@TempDir Path tempDir) throws IOException {
            Path file = Files.createFile(tempDir.resolve("test.txt"));
            assertFalse(ValidationUtils.isValidDirectory(file.toString()));
        }
    }

    @Nested
    @DisplayName("isWritableDirectory")
    class IsWritableDirectory {

        @Test
        @DisplayName("null returns false")
        void nullPath() {
            assertFalse(ValidationUtils.isWritableDirectory(null));
        }

        @Test
        @DisplayName("nonexistent returns false")
        void nonexistent() {
            assertFalse(ValidationUtils.isWritableDirectory("/no/such/dir"));
        }

        @Test
        @DisplayName("writable temp dir returns true")
        void writableDir(@TempDir Path tempDir) {
            assertTrue(ValidationUtils.isWritableDirectory(tempDir.toString()));
        }
    }

    @Nested
    @DisplayName("isValidPort")
    class IsValidPort {

        @Test
        @DisplayName("0 is invalid")
        void zeroInvalid() {
            assertFalse(ValidationUtils.isValidPort(0));
        }

        @Test
        @DisplayName("negative is invalid")
        void negativeInvalid() {
            assertFalse(ValidationUtils.isValidPort(-1));
        }

        @Test
        @DisplayName("1 is valid")
        void oneValid() {
            assertTrue(ValidationUtils.isValidPort(1));
        }

        @Test
        @DisplayName("65535 is valid")
        void maxValid() {
            assertTrue(ValidationUtils.isValidPort(65535));
        }

        @Test
        @DisplayName("65536 is invalid")
        void overMaxInvalid() {
            assertFalse(ValidationUtils.isValidPort(65536));
        }

        @Test
        @DisplayName("8080 is valid")
        void typicalPort() {
            assertTrue(ValidationUtils.isValidPort(8080));
        }
    }
}
