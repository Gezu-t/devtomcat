package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.ValidationResult;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Utility methods for Tomcat server management and configuration.
 *
 * Provides functionality for:
 * - Selecting and validating Tomcat installations
 * - Generating unique server names
 * - Creating TomcatInfo instances from user selections
 *
 * @author Gezahegn Lemma (Gezu)
 * @version 1.0
 */
public final class TomcatServerUtils {

    private static final Logger LOG = Logger.getInstance(TomcatServerUtils.class);

    /** Directory-name prefixes that mark a folder as a Tomcat installation root — used to decide whether to reuse the directory's own name as the server display name vs. synthesize {@code "Tomcat <version>"}. */
    private static final List<String> TOMCAT_DIR_PREFIXES = List.of("tomcat", "apache-tomcat");

    /** Fallback display name used by {@link #generateUniqueName(Collection, String)} when the caller's preferred name is blank. */
    private static final String DEFAULT_TOMCAT_SERVER_NAME = "Tomcat Server";

    /** Generic fallback display name used by the formatter-driven overload of {@link #generateUniqueName(Collection, String, Function)} when the supplied base name is blank. */
    private static final String DEFAULT_SERVER_NAME = "Server";

    /** Prefix for synthesized display names of the form {@code "Tomcat <version>"}. */
    private static final String TOMCAT_DISPLAY_NAME_PREFIX = "Tomcat ";

    private TomcatServerUtils() {
        // Utility class - prevent instantiation
    }

    public static void selectTomcatInstallation(
            @NotNull Function<String, String> nameGenerator,
            @NotNull Consumer<TomcatInfo> onSuccess) {

        Objects.requireNonNull(nameGenerator, "Name generator cannot be null");
        Objects.requireNonNull(onSuccess, "Success callback cannot be null");

        LOG.debug("Opening Tomcat installation selection dialog");

        // Create file chooser descriptor for directories
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select Tomcat Installation Directory");
        descriptor.setDescription("Choose the root directory of your Apache Tomcat installation (e.g., /opt/tomcat, C:\\apache-tomcat-9.0.56)");

        // Show file chooser dialog
        VirtualFile selectedFile = SafeBrowseUtil.chooseFile(descriptor, null, null);

        if (selectedFile == null) {
            LOG.debug("Tomcat installation selection cancelled by user");
            return;
        }

        String installPath = selectedFile.getPath();
        LOG.info("User selected Tomcat installation path: " + installPath);

        // Validate the selected directory
        ValidationResult validationResult = TomcatServerValidator.validateInstallation(installPath);

        if (!validationResult.isValid()) {
            LOG.warn("Invalid Tomcat installation selected: " + installPath);

            StringBuilder errorMessage = new StringBuilder("The selected directory is not a valid Tomcat installation.\n\n");
            errorMessage.append("Errors:\n");
            for (String error : validationResult.getErrors()) {
                errorMessage.append("  - ").append(error).append("\n");
            }

            if (!validationResult.getWarnings().isEmpty()) {
                errorMessage.append("\nWarnings:\n");
                for (String warning : validationResult.getWarnings()) {
                    errorMessage.append("  - ").append(warning).append("\n");
                }
            }

            Messages.showErrorDialog(
                    errorMessage.toString(),
                    "Invalid Tomcat Installation"
            );
            return;
        }

        // Detect Tomcat version
        String version = TomcatServerValidator.detectVersion(installPath);
        LOG.info("Detected Tomcat version: " + version);

        // Generate a default name based on the directory name
        String directoryName = selectedFile.getName();
        String preferredName = startsWithAny(directoryName, TOMCAT_DIR_PREFIXES)
                ? directoryName
                : TOMCAT_DISPLAY_NAME_PREFIX + version;

        // Generate unique name
        String uniqueName = nameGenerator.apply(preferredName);
        LOG.debug("Generated unique name: " + uniqueName);

        // Create TomcatInfo
        TomcatInfo tomcatInfo = new TomcatInfo(uniqueName, version, installPath);
        LOG.info("Created TomcatInfo: " + tomcatInfo);

        // Invoke success callback
        onSuccess.accept(tomcatInfo);
    }

    @NotNull
    public static String generateUniqueName(
            @NotNull Collection<String> existingNames,
            @NotNull String preferredName) {

        Objects.requireNonNull(existingNames, "Existing names collection cannot be null");
        Objects.requireNonNull(preferredName, "Preferred name cannot be null");

        String baseName = preferredName.trim();
        if (baseName.isEmpty()) {
            baseName = DEFAULT_TOMCAT_SERVER_NAME;
        }

        // If the preferred name doesn't exist, use it
        if (!existingNames.contains(baseName)) {
            LOG.debug("Preferred name is available: " + baseName);
            return baseName;
        }

        // Otherwise, append a number to make it unique
        int counter = 1;
        String candidateName;

        do {
            candidateName = baseName + " (" + counter + ")";
            counter++;
        } while (existingNames.contains(candidateName));

        LOG.debug("Generated unique name: " + candidateName + " (from: " + baseName + ")");
        return candidateName;
    }

    @NotNull
    public static String generateUniqueName(
            @NotNull Collection<String> existingNames,
            @NotNull String baseName,
            @NotNull Function<Integer, String> formatter) {

        Objects.requireNonNull(existingNames, "Existing names collection cannot be null");
        Objects.requireNonNull(baseName, "Base name cannot be null");
        Objects.requireNonNull(formatter, "Formatter function cannot be null");

        String normalizedBase = baseName.trim();
        if (normalizedBase.isEmpty()) {
            normalizedBase = DEFAULT_SERVER_NAME;
        }

        // Try base name first
        if (!existingNames.contains(normalizedBase)) {
            return normalizedBase;
        }

        // Use formatter for numbered versions
        int counter = 1;
        String candidateName;

        do {
            candidateName = formatter.apply(counter);
            counter++;
        } while (existingNames.contains(candidateName));

        return candidateName;
    }

    @NotNull
    public static String detectVersion(@NotNull String installPath) {
        Objects.requireNonNull(installPath, "Install path cannot be null");
        return TomcatServerValidator.detectVersion(installPath);
    }

    /** Returns true iff {@code value} starts with any of {@code prefixes}. */
    private static boolean startsWithAny(@NotNull String value, @NotNull List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }
}
