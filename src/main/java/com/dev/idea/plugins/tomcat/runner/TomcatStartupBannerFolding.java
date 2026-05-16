package com.dev.idea.plugins.tomcat.runner;

import com.intellij.execution.ConsoleFolding;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Collapses Tomcat's system-info startup banner — the ~10–15 boilerplate lines
 * emitted by {@code org.apache.catalina.startup.VersionLoggerListener} on every
 * startup: server version, OS name/version, architecture, Java home, JVM version,
 * CATALINA_BASE/HOME paths, APR/OpenSSL detection notes, and the one-per-arg
 * JVM command-line dump.
 *
 * <p><b>Intentionally not folded</b>:
 * <ul>
 *   <li>"Starting service [Catalina]" / "Starting Servlet engine: …" — short and
 *       useful as a visual cue that startup is progressing.</li>
 *   <li>"Deploying web application directory/archive [...]" / "Deployment of
 *       […] has finished in [N] ms" — per-artifact deployment progress is
 *       exactly what the user wants to see.</li>
 *   <li>"Server startup in [N] milliseconds" — the canonical "Tomcat is up"
 *       line. Folding it would be a usability regression.</li>
 * </ul>
 *
 * <p><b>Pattern conservatism</b>: every alternative requires a literal trailing
 * colon (or other unambiguous syntactic anchor) so a user webapp that happens
 * to log a string containing "Java Home" or "Server version" in some other
 * context does not get folded by accident. The risk profile is asymmetric —
 * missing a banner line is invisible, mis-folding a user line hides
 * information the user wrote on purpose.
 */
public final class TomcatStartupBannerFolding extends ConsoleFolding {

    private static final Pattern BANNER_LINE = Pattern.compile(
            ".*\\b(?:"
                    + "Server version (?:name|number|built|major):"
                    + "|OS Name:"
                    + "|OS Version:"
                    + "|Architecture:"
                    + "|Java Home:"
                    + "|JVM Version:"
                    + "|JVM Vendor:"
                    + "|CATALINA_BASE:"
                    + "|CATALINA_HOME:"
                    + "|CATALINA_TMPDIR:"
                    + "|Loaded Apache Tomcat Native library"
                    + "|APR capabilities:"
                    + "|OpenSSL successfully initialized"
                    + "|Command line argument: -"
                    + ").*");

    @Override
    public boolean shouldFoldLine(@NotNull Project project, @NotNull String line) {
        return BANNER_LINE.matcher(line).matches();
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull Project project, @NotNull List<String> lines) {
        return "  <" + lines.size() + " Tomcat startup info lines>";
    }
}
