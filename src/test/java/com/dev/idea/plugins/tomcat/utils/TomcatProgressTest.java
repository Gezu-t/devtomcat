package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.progress.ProcessCanceledException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** checkCanceled / setDetail never throw without a platform; rethrowIfControlFlow throws only control flow, unchanged. */
@DisplayName("TomcatProgress")
class TomcatProgressTest {

    @Test
    @DisplayName("checkCanceled is a no-op without an indicator")
    void checkCanceledIsNoOpWithoutIndicator() {
        assertDoesNotThrow(TomcatProgress::checkCanceled);
    }

    @Test
    @DisplayName("setDetail is a no-op without an indicator")
    void setDetailIsNoOpWithoutIndicator() {
        assertDoesNotThrow(() -> TomcatProgress.setDetail("any detail text"));
    }

    @Test
    @DisplayName("rethrowIfControlFlow rethrows ProcessCanceledException unchanged")
    void rethrowsProcessCanceled() {
        ProcessCanceledException pce = new ProcessCanceledException();
        assertSame(pce, assertThrows(ProcessCanceledException.class, () -> TomcatProgress.rethrowIfControlFlow(pce)));
    }

    @Test
    @DisplayName("rethrowIfControlFlow rethrows a plain CancellationException unchanged")
    void rethrowsCancellation() {
        CancellationException ce = new CancellationException();
        assertSame(ce, assertThrows(CancellationException.class, () -> TomcatProgress.rethrowIfControlFlow(ce)));
    }

    @Test
    @DisplayName("rethrowIfControlFlow rethrows a non-cancellation ControlFlowException unchanged")
    void rethrowsOtherControlFlow() {
        RuntimeException cfe = new ControlFlow();
        assertSame(cfe, assertThrows(ControlFlow.class, () -> TomcatProgress.rethrowIfControlFlow(cfe)));
    }

    @Test
    @DisplayName("rethrowIfControlFlow ignores ordinary failures")
    void ignoresOrdinaryFailures() {
        assertDoesNotThrow(() -> TomcatProgress.rethrowIfControlFlow(new IOException("disk")));
        assertDoesNotThrow(() -> TomcatProgress.rethrowIfControlFlow(new IllegalStateException("state")));
    }

    private static final class ControlFlow extends RuntimeException implements ControlFlowException {}
}
