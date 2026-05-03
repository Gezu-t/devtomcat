package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Catalina-script identification and JDWP/JPDA injection helpers used by the
 * Tomcat launch pipeline.
 *
 * <p>Extracted from {@link TomcatCommandLineState} so the launcher class can
 * concentrate on the lifecycle (pre-launch checks, port resolution, process
 * handler wiring) without owning a parallel set of static command-line
 * primitives. Every method here is a pure function — no instance state,
 * no platform dependencies — which is why this class is package-private,
 * stateless, and exhaustively unit-tested in {@code CatalinaScriptSupportTest}.
 *
 * <p>Functions provided:
 * <ul>
 *   <li>{@link #hasManualJdwpAgent(String)} — detect a user-supplied
 *       {@code -agentlib:jdwp} in VM options so the launcher can warn about
 *       conflicting agents in Debug mode.</li>
 *   <li>{@link #isCatalinaCommand(List)} / {@link #usesCatalinaJpda(List)} /
 *       {@link #enableCatalinaJpda(List)} — recognise and JPDA-enable a
 *       custom startup script that delegates to {@code catalina.sh|bat}.</li>
 *   <li>{@link #appendVmOptIfMissing(String, String)} — dedup-aware appender
 *       for VM-option environment variables that might already include a
 *       JDWP agent (prevents two-agent JVMs).</li>
 *   <li>{@link #applyCustomScriptDebugSupport(GeneralCommandLine, List, int)}
 *       — wires JDWP into the env vars a custom script reads, both the
 *       catalina-specific {@code JPDA_*} family and the universal
 *       {@code CATALINA_OPTS} / {@code JAVA_OPTS} channels.</li>
 * </ul>
 */
final class CatalinaScriptSupport {

    private static final String JDWP_TOKEN = "-agentlib:jdwp";

    private CatalinaScriptSupport() {
        // static utilities only
    }

    /**
     * Returns {@code true} if {@code vmOptions} contains a manually-supplied
     * {@code -agentlib:jdwp} agent argument. Matches the token followed by
     * {@code =}, whitespace, or end-of-string — adjacent identifiers like
     * {@code -agentlib:jdwp_other} are correctly rejected.
     */
    static boolean hasManualJdwpAgent(@Nullable String vmOptions) {
        if (vmOptions == null) return false;
        // Loop instead of single indexOf — a rejected leading match like
        // '-agentlib:jdwp_other' must not mask a real '-agentlib:jdwp=' later
        // in the string.
        int from = 0;
        while (true) {
            int idx = vmOptions.indexOf(JDWP_TOKEN, from);
            if (idx < 0) return false;
            int end = idx + JDWP_TOKEN.length();
            boolean isRealAgent = end >= vmOptions.length()
                    || vmOptions.charAt(end) == '='
                    || Character.isWhitespace(vmOptions.charAt(end));
            if (isRealAgent) return true;
            from = end;
        }
    }

    /**
     * Returns {@code true} if the first token's filename starts with
     * {@code catalina} (case-insensitive). Robust to absolute paths
     * ({@code /usr/local/tomcat/bin/catalina.sh}) and to extension-less
     * invocations.
     */
    static boolean isCatalinaCommand(@NotNull List<String> tokens) {
        if (tokens.isEmpty()) return false;
        String command = Path.of(tokens.get(0)).getFileName().toString().toLowerCase(Locale.ROOT);
        return command.startsWith(TomcatConstants.CATALINA_SCRIPT);
    }

    /**
     * Returns {@code true} if any token is {@code jpda} (case-insensitive),
     * indicating JPDA debug mode is already enabled.
     */
    static boolean usesCatalinaJpda(@NotNull List<String> tokens) {
        return tokens.stream().anyMatch(token -> TomcatConstants.CATALINA_JPDA.equalsIgnoreCase(token));
    }

    /**
     * Returns a copy of {@code tokens} with {@code jpda} inserted before the
     * first {@code run} or {@code start} token. Returns the input unchanged
     * (by reference) when:
     * <ul>
     *   <li>the first token is not a catalina script,</li>
     *   <li>the list already contains {@code jpda},</li>
     *   <li>or the list contains neither {@code run} nor {@code start}.</li>
     * </ul>
     */
    @NotNull
    static List<String> enableCatalinaJpda(@NotNull List<String> tokens) {
        if (!isCatalinaCommand(tokens) || usesCatalinaJpda(tokens)) {
            return tokens;
        }
        ArrayList<String> adjusted = new ArrayList<>(tokens.size() + 1);
        boolean inserted = false;
        for (String token : tokens) {
            if (!inserted && (TomcatConstants.CATALINA_RUN.equalsIgnoreCase(token)
                    || TomcatConstants.CATALINA_START.equalsIgnoreCase(token))) {
                adjusted.add(TomcatConstants.CATALINA_JPDA);
                inserted = true;
            }
            adjusted.add(token);
        }
        return inserted ? List.copyOf(adjusted) : tokens;
    }

    /**
     * Append {@code vmOpt} to {@code currentValue} with a single-space
     * separator. If {@code currentValue} already contains a JDWP agent,
     * returns the trimmed input unchanged — preventing a second agent that
     * the JVM would bind to a different port (defeating the IDE's debug attach).
     */
    @NotNull
    static String appendVmOptIfMissing(@Nullable String currentValue, @NotNull String vmOpt) {
        if (hasManualJdwpAgent(currentValue)) {
            return StringUtil.notNullize(currentValue).trim();
        }
        String existing = StringUtil.notNullize(currentValue).trim();
        return existing.isEmpty() ? vmOpt : existing + " " + vmOpt;
    }

    /**
     * Wire JDWP support into a custom-script command line so debug mode works
     * even when the user opts out of the default catalina launcher. Sets
     * {@code TOMCAT_DEBUG_PORT} and {@code TOMCAT_JDWP_OPTS} unconditionally,
     * the {@code JPDA_*} family only for catalina commands, and appends
     * (without duplicating) JDWP to {@code CATALINA_OPTS} and {@code JAVA_OPTS}.
     */
    static void applyCustomScriptDebugSupport(@NotNull GeneralCommandLine commandLine,
                                              @NotNull List<String> startupTokens,
                                              int debugPort) {
        String jdwpArg = TomcatConstants.JDWP_AGENT_PREFIX
                + String.format(TomcatConstants.JDWP_CONNECTION_FORMAT,
                TomcatConstants.JDWP_TRANSPORT_SOCKET, debugPort);

        commandLine.withEnvironment(TomcatConstants.ENV_DEBUG_PORT, String.valueOf(debugPort));
        commandLine.withEnvironment(TomcatConstants.ENV_JDWP_OPTS, jdwpArg);

        if (isCatalinaCommand(startupTokens)) {
            commandLine.withEnvironment(TomcatConstants.ENV_JPDA_ADDRESS, String.valueOf(debugPort));
            commandLine.withEnvironment(TomcatConstants.ENV_JPDA_TRANSPORT, TomcatConstants.JDWP_TRANSPORT_SOCKET);
            commandLine.withEnvironment(TomcatConstants.ENV_JPDA_SUSPEND, "n");
            commandLine.withEnvironment(TomcatConstants.ENV_JPDA_OPTS, jdwpArg);
        }

        String catalinaOpts = appendVmOptIfMissing(
                commandLine.getEnvironment().get(TomcatConstants.ENV_CATALINA_OPTS), jdwpArg);
        commandLine.withEnvironment(TomcatConstants.ENV_CATALINA_OPTS, catalinaOpts);

        String javaOpts = appendVmOptIfMissing(
                commandLine.getEnvironment().get(TomcatConstants.ENV_JAVA_OPTS), jdwpArg);
        commandLine.withEnvironment(TomcatConstants.ENV_JAVA_OPTS, javaOpts);
    }
}
