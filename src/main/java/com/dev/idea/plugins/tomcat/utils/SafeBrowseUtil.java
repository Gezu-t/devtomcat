package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;

/**
 * Centralizes browse-field wiring for the plugin.
 *
 * <p>Uses a plain Swing {@link JFileChooser} instead of IntelliJ's
 * {@code FileChooser}/{@code MacPathChooserDialog} to avoid the
 * {@code SlowOperations} assertion that fires on macOS when the platform
 * chooser calls {@code LocalFileSystemBase.refreshAndFindFileByPath} on EDT.
 * IntelliJ file completion is still installed for manual typing in the text field.
 */
public final class SafeBrowseUtil {

    private SafeBrowseUtil() {}

    /**
     * Opens a native file/folder chooser that bypasses IntelliJ's VFS refresh path.
     *
     * @return the chosen file's absolute path, or {@code null} if cancelled
     */
    @Nullable
    public static String choosePathNative(@Nullable String currentPath,
                                           @NotNull String title,
                                           boolean directoriesOnly) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(directoriesOnly
                ? JFileChooser.DIRECTORIES_ONLY
                : JFileChooser.FILES_AND_DIRECTORIES);

        if (!StringUtil.isEmptyOrSpaces(currentPath)) {
            File current = new File(currentPath.trim());
            if (current.exists()) {
                chooser.setCurrentDirectory(current.isDirectory() ? current : current.getParentFile());
                chooser.setSelectedFile(current);
            }
        }

        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            return chooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    /**
     * Drop-in replacement for {@code FileChooser.chooseFile} that returns a {@link VirtualFile}.
     * Uses native chooser internally, then resolves via {@link LocalFileSystem}.
     *
     * @return the chosen file, or {@code null} if cancelled
     */
    @Nullable
    public static VirtualFile chooseFile(@NotNull FileChooserDescriptor descriptor,
                                          @Nullable Project project,
                                          @Nullable VirtualFile toSelect) {
        String currentPath = toSelect != null ? toSelect.getPath() : null;
        String title = descriptor.getTitle() != null ? descriptor.getTitle() : "Select";
        boolean dirsOnly = descriptor.isChooseFolders() && !descriptor.isChooseFiles();

        String chosen = choosePathNative(currentPath, title, dirsOnly);
        if (chosen == null) return null;

        return LocalFileSystem.getInstance().refreshAndFindFileByPath(chosen);
    }

    /**
     * Wires a browse button to a native file chooser with IntelliJ file completion
     * for manual typing. Avoids the {@code MacPathChooserDialog} EDT assertion entirely.
     */
    public static void addBrowseFolderListener(@NotNull TextFieldWithBrowseButton field,
                                                @NotNull String title,
                                                @NotNull String description,
                                                @Nullable Project project,
                                                @NotNull FileChooserDescriptor descriptor) {
        descriptor.setTitle(title);
        descriptor.setDescription(description);

        boolean dirsOnly = descriptor.isChooseFolders() && !descriptor.isChooseFiles();

        field.addActionListener(event -> {
            String chosen = choosePathNative(field.getText(), title, dirsOnly);
            if (chosen != null) {
                field.setText(chosen);
            }
        });

        FileChooserFactory.getInstance().installFileCompletion(
                field.getTextField(), descriptor, true, null);
    }
}
