package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.setting.TomcatServersConfigurable;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * UI dialog utilities for consistent user interactions.
 *
 * @author Gezahegn Lemma
 */
public final class DialogUtils {

    private DialogUtils() {}

    @Nullable
    public static String chooseTomcatDirectory(@NotNull Project project) {
        Objects.requireNonNull(project);

        var descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select Tomcat Installation");
        descriptor.setDescription("Choose the root directory of your Tomcat installation");

        VirtualFile chosen = FileChooser.chooseFile(descriptor, project, null);
        return chosen != null ? chosen.getPath() : null;
    }

    public static void openTomcatSettings(@NotNull Project project) {
        Objects.requireNonNull(project);
        ShowSettingsUtil.getInstance().showSettingsDialog(project, TomcatServersConfigurable.class);
    }

    public static void showError(@NotNull String title, @NotNull String message, @Nullable Project project) {
        Messages.showErrorDialog(project, message, title);
    }

    public static void showWarning(@NotNull String title, @NotNull String message) {
        Messages.showWarningDialog(message, title);
    }

    public static void showInfo(@NotNull String title, @NotNull String message) {
        Messages.showInfoMessage(message, title);
    }

    public static boolean showConfirm(@NotNull String title, @NotNull String message, @Nullable Project project) {
        return Messages.showYesNoDialog(project, message, title, "OK", "Cancel", null) == Messages.YES;
    }
}
