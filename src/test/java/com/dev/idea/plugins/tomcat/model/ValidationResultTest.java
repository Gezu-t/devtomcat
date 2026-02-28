package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationResult")
class ValidationResultTest {

    @Nested
    @DisplayName("empty result")
    class EmptyResult {

        @Test
        @DisplayName("no errors by default")
        void noErrors() {
            ValidationResult result = new ValidationResult();
            assertFalse(result.hasErrors());
            assertEquals(0, result.getErrorCount());
            assertTrue(result.getErrors().isEmpty());
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("no warnings by default")
        void noWarnings() {
            ValidationResult result = new ValidationResult();
            assertFalse(result.hasWarnings());
            assertEquals(0, result.getWarningCount());
            assertTrue(result.getWarnings().isEmpty());
        }

        @Test
        @DisplayName("no suggestions by default")
        void noSuggestions() {
            ValidationResult result = new ValidationResult();
            assertFalse(result.hasSuggestions());
            assertEquals(0, result.getSuggestionCount());
            assertTrue(result.getSuggestions().isEmpty());
        }
    }

    @Nested
    @DisplayName("addError")
    class AddError {

        @Test
        @DisplayName("adds error and updates state")
        void addsError() {
            ValidationResult result = new ValidationResult();
            result.addError("Port out of range");
            assertTrue(result.hasErrors());
            assertEquals(1, result.getErrorCount());
            assertFalse(result.isValid());
            assertEquals("Port out of range", result.getErrors().get(0));
        }

        @Test
        @DisplayName("rejects null message")
        void rejectsNull() {
            ValidationResult result = new ValidationResult();
            assertThrows(Exception.class, () -> result.addError(null));
        }

        @Test
        @DisplayName("multiple errors joined by newline")
        void multipleErrors() {
            ValidationResult result = new ValidationResult();
            result.addError("Error 1");
            result.addError("Error 2");
            assertEquals(2, result.getErrorCount());
            assertTrue(result.getErrorMessage().contains("Error 1"));
            assertTrue(result.getErrorMessage().contains("Error 2"));
        }
    }

    @Nested
    @DisplayName("addWarning")
    class AddWarning {

        @Test
        @DisplayName("adds warning and updates state")
        void addsWarning() {
            ValidationResult result = new ValidationResult();
            result.addWarning("Port in use");
            assertTrue(result.hasWarnings());
            assertEquals(1, result.getWarningCount());
            assertTrue(result.isValid()); // warnings don't affect validity
        }

        @Test
        @DisplayName("rejects null message")
        void rejectsNull() {
            assertThrows(Exception.class, () -> new ValidationResult().addWarning(null));
        }
    }

    @Nested
    @DisplayName("addSuggestion")
    class AddSuggestion {

        @Test
        @DisplayName("adds suggestion and updates state")
        void addsSuggestion() {
            ValidationResult result = new ValidationResult();
            result.addSuggestion("Use port 8081");
            assertTrue(result.hasSuggestions());
            assertEquals(1, result.getSuggestionCount());
        }

        @Test
        @DisplayName("rejects null message")
        void rejectsNull() {
            assertThrows(Exception.class, () -> new ValidationResult().addSuggestion(null));
        }
    }

    @Nested
    @DisplayName("message formatting")
    class MessageFormatting {

        @Test
        @DisplayName("error message joins with newline")
        void errorMessageJoins() {
            ValidationResult result = new ValidationResult();
            result.addError("A");
            result.addError("B");
            assertEquals("A\nB", result.getErrorMessage());
        }

        @Test
        @DisplayName("warning message joins with newline")
        void warningMessageJoins() {
            ValidationResult result = new ValidationResult();
            result.addWarning("W1");
            result.addWarning("W2");
            assertEquals("W1\nW2", result.getWarningMessage());
        }

        @Test
        @DisplayName("suggestion message joins with newline")
        void suggestionMessageJoins() {
            ValidationResult result = new ValidationResult();
            result.addSuggestion("S1");
            result.addSuggestion("S2");
            assertEquals("S1\nS2", result.getSuggestionMessage());
        }

        @Test
        @DisplayName("empty messages return empty string")
        void emptyMessages() {
            ValidationResult result = new ValidationResult();
            assertEquals("", result.getErrorMessage());
            assertEquals("", result.getWarningMessage());
            assertEquals("", result.getSuggestionMessage());
        }
    }

    @Nested
    @DisplayName("unmodifiable lists")
    class UnmodifiableLists {

        @Test
        @DisplayName("getErrors returns unmodifiable list")
        void errorsUnmodifiable() {
            ValidationResult result = new ValidationResult();
            result.addError("test");
            assertThrows(UnsupportedOperationException.class, () -> result.getErrors().add("extra"));
        }

        @Test
        @DisplayName("getWarnings returns unmodifiable list")
        void warningsUnmodifiable() {
            ValidationResult result = new ValidationResult();
            result.addWarning("test");
            assertThrows(UnsupportedOperationException.class, () -> result.getWarnings().add("extra"));
        }

        @Test
        @DisplayName("getSuggestions returns unmodifiable list")
        void suggestionsUnmodifiable() {
            ValidationResult result = new ValidationResult();
            result.addSuggestion("test");
            assertThrows(UnsupportedOperationException.class, () -> result.getSuggestions().add("extra"));
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("includes counts")
        void includesCounts() {
            ValidationResult result = new ValidationResult();
            result.addError("e");
            result.addWarning("w");
            result.addSuggestion("s");
            String str = result.toString();
            assertTrue(str.contains("errors=1"));
            assertTrue(str.contains("warnings=1"));
            assertTrue(str.contains("suggestions=1"));
        }
    }
}
