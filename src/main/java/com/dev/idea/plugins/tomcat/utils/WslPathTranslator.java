package com.dev.idea.plugins.tomcat.utils;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Pure host→distro path translation for WSL launch mode.
 *
 * <p>Inside a WSL2 distribution every Windows drive is mounted under the drvfs
 * root ({@code C:\Users\x} → {@code /mnt/c/Users/x}) and the distribution's own
 * tree, which Windows reaches through {@code \\wsl$\<distro>\...} or
 * {@code \\wsl.localhost\<distro>\...}, is simply {@code /...}. This class
 * applies exactly those two rules and nothing else — no filesystem access, no
 * platform services — so it is exhaustively unit-testable on any OS.
 *
 * <p>Rules ({@code \} and {@code /} accepted interchangeably, input trimmed):
 * <ul>
 *   <li>WSL UNC path → {@code /a/b}. A UNC naming a <em>different</em>
 *       distribution still translates but is reported through the warning sink
 *       (it is unreachable from the launch distro).</li>
 *   <li>Drive path {@code X:\a\b} → {@code <mntRoot><x>/a/b}, drive letter
 *       lower-cased; a bare root {@code X:\} → {@code <mntRoot><x>}.</li>
 *   <li>Already Linux-form (leading {@code /}) → unchanged.</li>
 *   <li>Anything else (relative, empty) → returned unchanged.</li>
 * </ul>
 */
public final class WslPathTranslator implements LaunchPathMapper {

    private static final Logger LOG = Logger.getInstance(WslPathTranslator.class);

    /** The drvfs mount root WSL uses unless {@code /etc/wsl.conf} overrides it. */
    public static final String DEFAULT_MNT_ROOT = "/mnt/";

    private final String distroName;
    private final String mntRoot;
    private final Consumer<String> warningSink;

    public WslPathTranslator(@NotNull String distroName) {
        this(distroName, DEFAULT_MNT_ROOT);
    }

    public WslPathTranslator(@NotNull String distroName, @NotNull String mntRoot) {
        this(distroName, mntRoot, LOG::warn);
    }

    /** {@code warningSink} receives cross-distribution reference warnings. */
    public WslPathTranslator(@NotNull String distroName, @NotNull String mntRoot,
                             @NotNull Consumer<String> warningSink) {
        this.distroName = distroName;
        String root = mntRoot.isEmpty() ? DEFAULT_MNT_ROOT : mntRoot;
        this.mntRoot = root.endsWith("/") ? root : root + "/";
        this.warningSink = warningSink;
    }

    @NotNull
    public String getDistroName() {
        return distroName;
    }

    @NotNull
    public String getMntRoot() {
        return mntRoot;
    }

    @Override
    public @NotNull String toTarget(@NotNull String hostPath) {
        String p = hostPath.trim();
        if (p.isEmpty()) return hostPath;

        if (WslPathDetector.isWslPath(p)) {
            return translateUnc(p);
        }
        if (isDrivePath(p)) {
            return translateDrive(p);
        }
        // Linux form, relative, or anything else: hand through untouched.
        return p;
    }

    @Override
    public @NotNull String classpathSeparator() {
        return ":";
    }

    private String translateUnc(@NotNull String p) {
        String n = p.replace('/', '\\');
        int hostSep = n.indexOf('\\', 2);          // '\' after "wsl$" / "wsl.localhost"
        int distroStart = hostSep + 1;
        int distroEnd = n.indexOf('\\', distroStart);
        String distro = distroEnd < 0 ? n.substring(distroStart) : n.substring(distroStart, distroEnd);
        if (!distro.equalsIgnoreCase(distroName)) {
            warningSink.accept("Path '" + p + "' refers to WSL distribution '" + distro
                    + "' but Tomcat runs inside '" + distroName
                    + "'; it is not reachable from there.");
        }
        if (distroEnd < 0) return "/";
        return "/" + stripLeadingSeparators(n.substring(distroEnd + 1)).replace('\\', '/');
    }

    private String translateDrive(@NotNull String p) {
        String letter = String.valueOf(Character.toLowerCase(p.charAt(0)));
        String rest = stripLeadingSeparators(p.substring(2)).replace('\\', '/');
        return rest.isEmpty() ? mntRoot + letter : mntRoot + letter + "/" + rest;
    }

    private static boolean isDrivePath(@NotNull String p) {
        if (p.length() < 2 || p.charAt(1) != ':') return false;
        char c = Character.toLowerCase(p.charAt(0));
        if (c < 'a' || c > 'z') return false;
        return p.length() == 2 || p.charAt(2) == '\\' || p.charAt(2) == '/';
    }

    private static String stripLeadingSeparators(@NotNull String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '\\' || s.charAt(i) == '/')) i++;
        return s.substring(i);
    }

    @Override
    public String toString() {
        return "WslPathTranslator[" + distroName + ", mntRoot=" + mntRoot + "]";
    }
}
