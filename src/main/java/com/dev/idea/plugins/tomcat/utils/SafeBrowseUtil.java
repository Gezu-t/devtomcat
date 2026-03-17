package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Centralizes browse-field wiring for the plugin.
 *
 * <p>The platform's built-in browse-folder listener is preferred over custom
 * wrappers around {@code FileChooser.chooseFile()}, because modal chooser work
 * can continue after the initial callback returns and still trip EDT assertions.
 */
public final class SafeBrowseUtil {

    private SafeBrowseUtil() {}

    /**
     * Drop-in replacement for {@code FileChooser.chooseFile}.
     *
     * @return the chosen file, or {@code null} if cancelled
     */
    @Nullable
    public static VirtualFile chooseFile(@NotNull FileChooserDescriptor descriptor,
                                          @Nullable Project project,
                                          @Nullable VirtualFile toSelect) {
        return FileChooser.chooseFile(descriptor, project, toSelect);
    }

    /**
     * Configures the platform's built-in browse-folder listener with consistent
     * title/description setup and file completion.
     */
    public static void addBrowseFolderListener(@NotNull TextFieldWithBrowseButton field,
                                                @NotNull String title,
                                                @NotNull String description,
                                                @Nullable Project project,
                                                @NotNull FileChooserDescriptor descriptor) {
        descriptor.setTitle(title);
        descriptor.setDescription(description);
        field.addBrowseFolderListener(new TextBrowseFolderListener(descriptor, project));
        FileChooserFactory.getInstance().installFileCompletion(
                field.getTextField(), descriptor, true, null);
    }
}
