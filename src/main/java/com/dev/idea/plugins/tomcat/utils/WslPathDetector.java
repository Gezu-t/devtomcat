package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Detects a Tomcat / JDK home that lives inside a WSL (Windows Subsystem for
 * Linux) distribution, reached from Windows through the distro's UNC mount
 * ({@code \\wsl$\<distro>\...} or the newer {@code \\wsl.localhost\<distro>\...}).
 *
 * <p>Such a home selects the experimental WSL launch mode (see
 * {@code runner.WslLaunchMode}): paths are translated by {@link WslPathTranslator}
 * and the JVM runs inside the distribution. A UNC path often <em>passes</em> a
 * plain {@code File.isDirectory()} check on Windows (the 9P mount is reachable),
 * so this detector — not the filesystem — is the signal both the pre-launch gate
 * and the config validator key on. When the distribution cannot be resolved the
 * launch refuses with {@link #unsupportedMessage} instead of emitting a Linux
 * command a Windows process cannot exec ({@code os error 2}).
 *
 * <p>Pure string logic — no platform dependency — so the only signal it uses is
 * the unambiguous WSL UNC prefix.
 */
public final class WslPathDetector {

    private WslPathDetector() {}

    /** {@code true} when {@code path} is a Windows UNC path into a WSL distribution. */
    public static boolean isWslPath(@Nullable String path) {
        if (path == null) return false;
        String p = path.trim().replace('/', '\\').toLowerCase(Locale.ROOT);
        return p.startsWith("\\\\wsl$\\") || p.startsWith("\\\\wsl.localhost\\");
    }

    /**
     * The distribution name embedded in a WSL UNC path (the segment after the
     * {@code \\wsl$\} / {@code \\wsl.localhost\} prefix), or {@code null} when the
     * path is not a WSL path or carries no distro segment.
     */
    @Nullable
    public static String distroOf(@Nullable String path) {
        if (!isWslPath(path)) return null;
        String p = path.trim().replace('/', '\\');
        int hostSep = p.indexOf('\\', 2);   // the '\' after "wsl$" / "wsl.localhost"
        if (hostSep < 0) return null;
        int start = hostSep + 1;
        if (start >= p.length()) return null;
        int end = p.indexOf('\\', start);
        String distro = end < 0 ? p.substring(start) : p.substring(start, end);
        return distro.isEmpty() ? null : distro;
    }

    /**
     * The user-facing explanation for refusing a WSL-hosted home whose
     * distribution cannot be resolved (not Windows, not installed, or the UNC
     * names a distribution WSL does not list). Names the distribution when it
     * can be parsed.
     */
    @NotNull
    public static String unsupportedMessage(@NotNull String path) {
        String distro = distroOf(path);
        return "This Tomcat is installed inside WSL"
                + (distro != null ? " (distribution '" + distro + "')" : "")
                + " at '" + path + "', but DevTomcat could not resolve that WSL distribution"
                + " on this machine, so it cannot run Tomcat inside it. WSL mode (experimental)"
                + " needs Windows with the distribution installed and wsl.exe available;"
                + " a WSL-hosted Tomcat cannot run as a local host process (its Linux paths and"
                + " binaries would be handed to a Windows process and fail). Use a Windows-side"
                + " Tomcat and JDK, or IntelliJ Ultimate's WSL application-server support.";
    }
}
