package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.SlowOperations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wraps file browse dialogs in {@link SlowOperations#allowSlowOperations}
 * to avoid SEVERE SlowOperations violations on EDT caused by IntelliJ's
 * internal VFS refresh in {@code PathChooserDialogHelper.fileToVirtualFile}.
 */
public final class SafeBrowseUtil {

    private SafeBrowseUtil() {}

    /**
     * Drop-in replacement for {@code FileChooser.chooseFile} that wraps
     * the call in {@link SlowOperations#allowSlowOperations}.
     *
     * @return the chosen file, or {@code null} if cancelled
     */
    @Nullable
    public static VirtualFile chooseFile(@NotNull FileChooserDescriptor descriptor,
                                          @Nullable Project project,
                                          @Nullable VirtualFile toSelect) {
        return SlowOperations.allowSlowOperations(
                () -> FileChooser.chooseFile(descriptor, project, toSelect));
    }

    /**
     * Drop-in replacement for {@code TextFieldWithBrowseButton.addBrowseFolderListener}
     * that wraps the file chooser call to suppress EDT/VFS violations.
     */
    public static void addBrowseFolderListener(@NotNull TextFieldWithBrowseButton field,
                                                @NotNull String title,
                                                @NotNull String description,
                                                @Nullable Project project,
                                                @NotNull FileChooserDescriptor descriptor) {
        descriptor.setTitle(title);
        descriptor.setDescription(description);
        field.addActionListener(e ->
                SlowOperations.allowSlowOperations(() -> {
                    VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
                    if (chosen != null) {
                        field.setText(chosen.getPath());
                    }
                })
        );
        FileChooserFactory.getInstance().installFileCompletion(
                field.getTextField(), descriptor, true, null);
    }
}
