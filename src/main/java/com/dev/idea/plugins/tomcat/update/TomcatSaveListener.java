package com.dev.idea.plugins.tomcat.update;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level document-save listener that drives the opt-in
 * "Update on save" trigger. Registered declaratively in plugin.xml so the class
 * loads only when the first document is saved.
 *
 * <p>For each save it checks, per open project, whether a Tomcat is running with
 * update-on-save enabled and whether the saved file lives in that project's
 * content; if so it asks {@link TomcatAutoUpdateService} to debounce-schedule an
 * update. Every guard and all heavy lifting live in the service — this class
 * only decides, as cheaply as possible, whether a save is even a candidate,
 * because it runs on every document save in the IDE.
 */
public final class TomcatSaveListener implements FileDocumentManagerListener {

    @Override
    public void beforeDocumentSaving(@NotNull Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isInLocalFileSystem()) return;

        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed()) continue;

            // Cheapest discriminator first: is there even a running server that
            // wants save-triggered updates? Avoids touching the service otherwise.
            if (!TomcatAutoUpdateService.hasRunningUpdateOnSaveServer(project)) continue;

            TomcatAutoUpdateService service = TomcatAutoUpdateService.getInstance(project);
            // The update pipeline saves documents itself — ignore that reentrant save.
            if (service.isSuppressingSaveTriggers()) continue;

            // Only react to files inside this project's content; editing a library
            // source or scratch file should not trigger a deployment update.
            boolean inContent;
            try {
                inContent = ReadAction.compute(() ->
                        ProjectFileIndex.getInstance(project).getContentRootForFile(file) != null);
            } catch (Throwable t) {
                inContent = false;
            }
            if (!inContent) continue;

            service.scheduleSaveSweep();
        }
    }
}
