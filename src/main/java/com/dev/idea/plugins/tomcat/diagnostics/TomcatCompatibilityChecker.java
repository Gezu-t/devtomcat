package com.dev.idea.plugins.tomcat.diagnostics;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates compatibility between Tomcat version and JDK version before launch.
 * Prevents cryptic runtime errors by catching mismatches early.
 *
 * <p>Compatibility matrix:
 * <ul>
 *   <li>Tomcat 7.x  → Java 6+</li>
 *   <li>Tomcat 8.0.x → Java 7+</li>
 *   <li>Tomcat 8.5.x → Java 7+</li>
 *   <li>Tomcat 9.x  → Java 8+</li>
 *   <li>Tomcat 10.0.x → Java 8+</li>
 *   <li>Tomcat 10.1.x → Java 11+</li>
 *   <li>Tomcat 11.x → Java 17+</li>
 * </ul>
 */
public final class TomcatCompatibilityChecker {

    private TomcatCompatibilityChecker() {}

    public static final class CompatibilityIssue {
        private final String message;
        private final boolean blocking;

        CompatibilityIssue(@NotNull String message, boolean blocking) {
            this.message = message;
            this.blocking = blocking;
        }

        public @NotNull String getMessage() { return message; }
        public boolean isBlocking() { return blocking; }
    }

    /**
     * Checks compatibility between the configured Tomcat version and the JDK.
     *
     * @param tomcatInfo the Tomcat server info
     * @param jdk        the configured JDK (null if not set)
     * @return list of compatibility issues (empty if all OK)
     */
    @NotNull
    public static List<CompatibilityIssue> check(@Nullable TomcatInfo tomcatInfo, @Nullable Sdk jdk) {
        List<CompatibilityIssue> issues = new ArrayList<>();

        if (tomcatInfo == null) {
            issues.add(new CompatibilityIssue("No Tomcat server configured", true));
            return issues;
        }

        int tomcatMajor = tomcatInfo.getMajorVersion();
        if (tomcatMajor == 0) {
            return issues; // Can't determine version — skip check
        }

        if (jdk == null) {
            issues.add(new CompatibilityIssue(
                    "No JDK configured. Tomcat " + tomcatMajor + " requires Java " + getMinJavaVersion(tomcatInfo) + "+.",
                    true));
            return issues;
        }

        String versionString = jdk.getVersionString();
        if (versionString == null || versionString.isEmpty()) {
            return issues; // Can't determine JDK version — skip check
        }
        JavaSdkVersion sdkVersion = JavaSdkVersion.fromVersionString(versionString);
        if (sdkVersion == null) {
            return issues;
        }

        int javaFeature = getJavaFeatureVersion(sdkVersion);
        int minJava = getMinJavaVersion(tomcatInfo);

        if (javaFeature > 0 && javaFeature < minJava) {
            issues.add(new CompatibilityIssue(
                    "Tomcat " + tomcatInfo.getVersion() + " requires Java " + minJava
                            + "+, but the configured JDK is Java " + javaFeature
                            + " (" + jdk.getName() + "). Change the JRE in Server tab.",
                    true));
        }

        // Warn about Tomcat 10+ Jakarta namespace change
        if (tomcatMajor >= 10) {
            issues.add(new CompatibilityIssue(
                    "Tomcat " + tomcatMajor + " uses Jakarta EE (jakarta.* namespace). "
                            + "Ensure your application uses jakarta.servlet instead of javax.servlet.",
                    false));
        }

        return issues;
    }

    /**
     * Returns {@code true} when the given Tomcat install belongs to an
     * end-of-life branch that no longer receives security updates from the
     * Apache Tomcat project. Used by the "Tomcat EOL" notification prompt
     * to recommend an upgrade.
     *
     * <p>Branches treated as EOL (sourced from the Apache Tomcat
     * <i>Which Version</i> page):
     * <ul>
     *   <li><b>Tomcat 7.x</b>: EOL March 2021.</li>
     *   <li><b>Tomcat 8.0.x</b>: EOL June 2018.</li>
     *   <li><b>Tomcat 8.5.x</b>: EOL March 2024.</li>
     *   <li><b>Tomcat 10.0.x</b>: EOL October 2023 (replaced by 10.1.x).</li>
     * </ul>
     *
     * <p>Tomcat 9.0.x, 10.1.x, and 11.x are not flagged. Returns
     * {@code false} when {@code tomcatInfo} is null or its version cannot
     * be parsed (conservative default; never warns for an unknown shape).
     */
    public static boolean isEndOfLifeTomcat(@Nullable TomcatInfo tomcatInfo) {
        if (tomcatInfo == null) return false;
        int major = tomcatInfo.getMajorVersion();
        if (major <= 0) return false;
        String version = tomcatInfo.getVersion();

        if (major == 7 || major == 8) return true; // 7.x, 8.0.x, 8.5.x all EOL
        if (major == 10 && version.startsWith("10.0")) return true;
        return false;
    }

    /**
     * Returns a short human-readable EOL date string for the given Tomcat
     * branch, or {@code null} if the branch is not flagged EOL. Used in
     * notification copy.
     */
    @Nullable
    public static String endOfLifeDateOrNull(@Nullable TomcatInfo tomcatInfo) {
        if (!isEndOfLifeTomcat(tomcatInfo)) return null;
        int major = tomcatInfo.getMajorVersion();
        String version = tomcatInfo.getVersion();
        if (major == 7) return "March 2021";
        if (major == 8 && version.startsWith("8.0")) return "June 2018";
        if (major == 8) return "March 2024"; // 8.5.x
        if (major == 10 && version.startsWith("10.0")) return "October 2023";
        return null;
    }

    /**
     * Returns the minimum Java feature version required for the given Tomcat version.
     */
    public static int getMinJavaVersion(@NotNull TomcatInfo tomcatInfo) {
        int major = tomcatInfo.getMajorVersion();
        String version = tomcatInfo.getVersion();

        if (major >= 11) return 17;
        if (major == 10) {
            // Tomcat 10.1.x needs Java 11, 10.0.x needs Java 8
            if (version.startsWith("10.1")) return 11;
            return 8;
        }
        if (major == 9) return 8;
        if (major == 8) return 7;
        if (major == 7) return 6;
        return 8; // safe default
    }

    /**
     * Extracts the Java feature version number (e.g., 8, 11, 17, 21) from an SDK version.
     */
    private static int getJavaFeatureVersion(@NotNull JavaSdkVersion sdkVersion) {
        // JavaSdkVersion names follow: JDK_1_8, JDK_11, JDK_17, JDK_21, etc.
        String name = sdkVersion.name();
        if (name.startsWith("JDK_1_")) {
            try { return Integer.parseInt(name.substring("JDK_1_".length())); } catch (NumberFormatException e) { return 0; }
        }
        if (name.startsWith("JDK_")) {
            // Extract first numeric part after JDK_
            String num = name.substring("JDK_".length()).split("_")[0];
            try { return Integer.parseInt(num); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    /**
     * Formats issues for display in a notification or console.
     */
    @NotNull
    public static String formatIssues(@NotNull List<CompatibilityIssue> issues) {
        if (issues.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (CompatibilityIssue issue : issues) {
            sb.append(issue.isBlocking() ? "[ERROR] " : "[WARN] ");
            sb.append(issue.getMessage()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Returns true if any issue is blocking (should prevent launch).
     */
    public static boolean hasBlockingIssues(@NotNull List<CompatibilityIssue> issues) {
        return issues.stream().anyMatch(CompatibilityIssue::isBlocking);
    }
}
