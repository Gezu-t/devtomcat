package com.dev.idea.plugins.tomcat.update;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cooperative-cancellation contract of the file-tree mirrors: once the
 * indicator the sync runs under is canceled, the walk must abort with
 * {@link ProcessCanceledException} instead of finishing the pass, and must
 * not copy past the cancellation point. This is what makes the Cancel button
 * on the launch-preparation progress (and on the background update task)
 * actually stop a long sync.
 *
 * <p>The test cancels from inside the runnable — after the thread is
 * registered under the indicator — mirroring the production sequence where
 * the user cancels a progress whose work is already running. (A lightweight
 * pre-canceled indicator would not arm the progress manager's global
 * check-canceled behavior, and is not how the launch/update surfaces cancel.)
 */
public class SyncCancellationPlatformTest extends BasePlatformTestCase {

    private File tempDir;
    private Path src;
    private Path dst;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tempDir = FileUtil.createTempDirectory("devtomcat-cancel", null);
        src = tempDir.toPath().resolve("src");
        dst = tempDir.toPath().resolve("dst");
        Files.createDirectories(src);
        Files.writeString(src.resolve("one.txt"), "payload");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (tempDir != null) {
                FileUtil.delete(tempDir);
            }
        } finally {
            super.tearDown();
        }
    }

    public void testCanceledIndicatorAbortsClassMirror() {
        assertMirrorAbortsOnCancel(() -> DeployedClassesSync.mirrorTree(src, dst));
    }

    public void testCanceledIndicatorAbortsWebResourcesMirror() {
        assertMirrorAbortsOnCancel(() -> WebResourcesSync.mirrorTree(src, dst));
    }

    private void assertMirrorAbortsOnCancel(@SuppressWarnings("BoundedWildcard") Runnable mirror) {
        ProgressIndicatorBase indicator = new ProgressIndicatorBase();
        try {
            ProgressManager.getInstance().runProcess(() -> {
                // Cancel while this thread is registered under the indicator —
                // the same shape as a user pressing Cancel mid-sync.
                indicator.cancel();
                mirror.run();
            }, indicator);
            fail("expected ProcessCanceledException from a canceled mirror");
        } catch (ProcessCanceledException expected) {
            // The cancellation must escape unchanged.
        }
        assertFalse("nothing may be copied once the indicator is canceled",
                Files.exists(dst.resolve("one.txt")));
    }
}
