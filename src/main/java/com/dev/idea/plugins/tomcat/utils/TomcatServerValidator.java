package com.dev.idea.plugins.tomcat.utils;

import com.dev.idea.plugins.tomcat.model.ValidationResult;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dev.idea.plugins.tomcat.TomcatConstants.*;

/**
 * Validates Tomcat server installations by checking required directories,
 * scripts, and configuration files.
 */
public final class TomcatServerValidator {

    private static final Logger LOG = Logger.getInstance(TomcatServerValidator.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

    // Tomcat directory structure
    private static final String DIR_BIN = "bin";

    // Required files
    private static final String CATALINA_SH = "catalina.sh";
    private static final String CATALINA_BAT = "catalina.bat";
    private static final String SERVER_XML = "server.xml";
    private static final String RELEASE_NOTES = "RELEASE-NOTES";

    private static final String VERSION_UNKNOWN = "Unknown";

    private TomcatServerValidator() {}

    @NotNull
    public static ValidationResult validateInstallation(@NotNull String installPath) {
        Objects.requireNonNull(installPath);
        ValidationResult result = new ValidationResult();

        if (!ValidationUtils.isValidDirectory(installPath)) {
            result.addError("Directory does not exist: " + installPath);
            return result;
        }

        Path root = Paths.get(installPath);
        validateBinDirectory(root, result);
        validateConfDirectory(root, result);
        validateOptionalDirectories(root, result);

        return result;
    }

    private static void validateBinDirectory(Path root, ValidationResult result) {
        Path bin = root.resolve(DIR_BIN);
        if (!ValidationUtils.isValidDirectory(bin.toString())) {
            result.addError("Missing '" + DIR_BIN + "' directory");
        } else if (!hasCatalinaScript(bin)) {
            result.addError("Missing " + CATALINA_SH + " or " + CATALINA_BAT);
        }
    }

    private static boolean hasCatalinaScript(Path bin) {
        return ValidationUtils.isValidFile(bin.resolve(CATALINA_SH).toString())
            || ValidationUtils.isValidFile(bin.resolve(CATALINA_BAT).toString());
    }

    private static void validateConfDirectory(Path root, ValidationResult result) {
        Path conf = root.resolve(DIR_CONF);
        if (!ValidationUtils.isValidDirectory(conf.toString())) {
            result.addError("Missing '" + DIR_CONF + "' directory");
        } else if (!ValidationUtils.isValidFile(conf.resolve(SERVER_XML).toString())) {
            result.addError("Missing " + SERVER_XML);
        }
    }

    private static void validateOptionalDirectories(Path root, ValidationResult result) {
        if (!ValidationUtils.isValidDirectory(root.resolve(DIR_WEBAPPS).toString())) {
            result.addWarning("Missing '" + DIR_WEBAPPS + "' directory");
        }
        if (!ValidationUtils.isValidDirectory(root.resolve(DIR_LOGS).toString())) {
            result.addWarning("Missing '" + DIR_LOGS + "' directory");
        }
    }

    public static boolean isValidInstallation(@NotNull String installPath) {
        return validateInstallation(installPath).isValid();
    }

    @NotNull
    public static String detectVersion(@NotNull String installPath) {
        Path bin = Paths.get(installPath, DIR_BIN);
        for (String script : new String[]{CATALINA_SH, CATALINA_BAT}) {
            String version = extractVersion(bin.resolve(script));
            if (version != null) return version;
        }
        String version = extractVersion(Paths.get(installPath, RELEASE_NOTES));
        return version != null ? version : VERSION_UNKNOWN;
    }

    @Nullable
    private static String extractVersion(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            String content = Files.readString(file);
            Matcher matcher = VERSION_PATTERN.matcher(content);
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception e) {
            LOG.debug("Could not extract version from: " + file, e);
            return null;
        }
    }
}