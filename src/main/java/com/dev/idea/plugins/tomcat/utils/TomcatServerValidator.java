package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TomcatServerValidator {
    private static final Logger LOG = Logger.getInstance(TomcatServerValidator.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

    private TomcatServerValidator() {}

    @NotNull
    public static ValidationUtils.Result validateInstallation(@NotNull String installPath) {
        Objects.requireNonNull(installPath);
        ValidationUtils.Result result = new ValidationUtils.Result();

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

    private static void validateBinDirectory(Path root, ValidationUtils.Result result) {
        Path bin = root.resolve("bin");
        if (!ValidationUtils.isValidDirectory(bin.toString())) {
            result.addError("Missing 'bin' directory");
        } else if (!hasCatalinaScript(bin)) {
            result.addError("Missing catalina.sh or catalina.bat");
        }
    }

    private static boolean hasCatalinaScript(Path bin) {
        return ValidationUtils.isValidFile(bin.resolve("catalina.sh").toString())
            || ValidationUtils.isValidFile(bin.resolve("catalina.bat").toString());
    }

    private static void validateConfDirectory(Path root, ValidationUtils.Result result) {
        Path conf = root.resolve("conf");
        if (!ValidationUtils.isValidDirectory(conf.toString())) {
            result.addError("Missing 'conf' directory");
        } else if (!ValidationUtils.isValidFile(conf.resolve("server.xml").toString())) {
            result.addError("Missing server.xml");
        }
    }

    private static void validateOptionalDirectories(Path root, ValidationUtils.Result result) {
        if (!ValidationUtils.isValidDirectory(root.resolve("webapps").toString())) {
            result.addWarning("Missing 'webapps' directory");
        }
        if (!ValidationUtils.isValidDirectory(root.resolve("logs").toString())) {
            result.addWarning("Missing 'logs' directory");
        }
    }

    public static boolean isValidInstallation(@NotNull String installPath) {
        return validateInstallation(installPath).isValid();
    }

    @NotNull
    public static String detectVersion(@NotNull String installPath) {
        Path bin = Paths.get(installPath, "bin");
        for (String script : new String[]{"catalina.sh", "catalina.bat"}) {
            String version = extractVersion(bin.resolve(script));
            if (version != null) return version;
        }
        String version = extractVersion(Paths.get(installPath, "RELEASE-NOTES"));
        return version != null ? version : "Unknown";
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