package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Detects a Tomcat / JDK home that lives inside a WSL (Windows Subsystem for
 * Linux) distribution, reached from Windows through the distro's UNC mount
 * ({@code \\wsl$\<distro>\...} or the newer {@code \\wsl.localhost\<distro>\...}).
 *
 * <p>DevTomcat launches Tomcat as a <em>local host</em> process, so a WSL-hosted
 * server cannot run: the Linux binaries, classpath and {@code -D} paths are
 * handed to a Windows process and fail (the reported {@code os error 2 — No such
 * file or directory}). Worse, such a UNC path often <em>passes</em> a plain
 * {@code File.isDirectory()} check on Windows (the 9P mount is reachable), so
 * without this guard the config looks valid and dies with a cryptic downstream
 * error. This detector lets both the pre-launch gate and the config validator
 * refuse the path with an honest, specific message instead.
 *
 * <p>Pure string logic — no platform dependency — so the only signal it uses is
 * the unambiguous WSL UNC prefix. Running the JVM inside the distro is a planned
 * enhancement (see LOCAL_NOTES / the WSL support assessment); until then, this is
 * a fail-loud guard, not WSL support.
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
     * The user-facing explanation for refusing a WSL-hosted home. Shared by the
     * pre-launch gate and the validator so both say the same thing. Names the
     * distribution when it can be parsed.
     */
    @NotNull
    public static String unsupportedMessage(@NotNull String path) {
        String distro = distroOf(path);
        return "This Tomcat is installed inside WSL"
                + (distro != null ? " (distribution '" + distro + "')" : "")
                + " at '" + path + "'. DevTomcat launches Tomcat as a local host process and"
                + " cannot yet run a server or JDK located inside a WSL distribution — the Linux"
                + " paths and binaries are handed to a Windows process and fail. Use a Windows-side"
                + " Tomcat and JDK, or IntelliJ Ultimate's WSL application-server support."
                + " Native WSL support is a planned enhancement.";
    }
}
