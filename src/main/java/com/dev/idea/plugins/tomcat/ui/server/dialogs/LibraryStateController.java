package com.dev.idea.plugins.tomcat.ui.server.dialogs;

import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the library state transitions for a single server in the Application Servers dialog.
 *
 * <p>Extracted from TomcatServerConfigurationDialog so the state logic can be tested
 * without a live Swing UI or IntelliJ Project.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Resolve which library paths to display (custom vs. filtered defaults)</li>
 *   <li>Track the confirmed Tomcat Home and reset custom libraries on valid home changes</li>
 *   <li>Persist add/remove edits back to the TomcatInfo model</li>
 * </ul>
 */
public class LibraryStateController {

    private String confirmedHome = "";
    private final Map<String, String> confirmedHomeByServerId = new HashMap<>();

    /**
     * Called when a server is loaded into the detail panel.
     * Restores the last confirmed home for this server if previously tracked,
     * otherwise initializes from the server's current path.
     *
     * <p>This avoids a bug where switching away from a server mid-edit (with an
     * invalid partial path) and switching back would set confirmedHome to the
     * invalid path, causing a subsequent revert to the original valid home to
     * wrongly clear custom libraries.
     */
    public void onServerLoaded(@NotNull TomcatInfo server) {
        String serverId = server.getId();
        confirmedHome = confirmedHomeByServerId.getOrDefault(serverId, server.getPath());
        confirmedHomeByServerId.put(serverId, confirmedHome);
    }

    /**
     * Called when the Tomcat Home field changes and detection completes.
     * Clears custom libraries only when a valid new home is confirmed.
     *
     * @param server            the current server being edited
     * @param newHome           the current home path
     * @param validDetection    true if newHome was detected as a valid Tomcat installation
     */
    public void onHomeChanged(@NotNull TomcatInfo server, @NotNull String newHome, boolean validDetection) {
        if (TomcatInfo.shouldResetLibraries(validDetection, newHome, confirmedHome)) {
            server.setLibraries(null);
            confirmedHome = newHome;
            confirmedHomeByServerId.put(server.getId(), newHome);
        }
    }

    /**
     * Resolve the library paths to display for the given server and home.
     * Returns custom libraries if set, otherwise filtered defaults from disk.
     *
     * @param server     the server whose libraries to resolve
     * @param tomcatHome the Tomcat home directory path
     * @return library paths to display (never null, may be empty)
     */
    @NotNull
    public List<String> resolveLibraries(@NotNull TomcatInfo server, @Nullable String tomcatHome) {
        if (tomcatHome == null || tomcatHome.isBlank()) {
            return List.of();
        }
        if (server.hasCustomLibraries()) {
            List<String> libs = server.getLibraries();
            return libs != null ? libs : List.of();
        }
        return TomcatInfo.filterDefaultLibraries(new File(tomcatHome, "lib"));
    }

    /**
     * Persist added library paths to the server.
     * Merges with the current display list (custom or resolved defaults).
     *
     * @param server     the server being edited
     * @param tomcatHome current Tomcat home (for resolving defaults if needed)
     * @param addedPaths paths to add
     */
    public void addLibraries(@NotNull TomcatInfo server, @Nullable String tomcatHome,
                             @NotNull List<String> addedPaths) {
        List<String> current = new ArrayList<>(resolveLibraries(server, tomcatHome));
        current.addAll(addedPaths);
        server.setLibraries(current);
    }

    /**
     * Persist removal of a library path from the server.
     *
     * @param server     the server being edited
     * @param tomcatHome current Tomcat home (for resolving defaults if needed)
     * @param removePath path to remove
     */
    public void removeLibrary(@NotNull TomcatInfo server, @Nullable String tomcatHome,
                              @NotNull String removePath) {
        List<String> current = new ArrayList<>(resolveLibraries(server, tomcatHome));
        current.remove(removePath);
        server.setLibraries(current);
    }

    /**
     * Persist the full tree contents back to the server.
     * Called after any tree modification.
     */
    public void persistLibraries(@NotNull TomcatInfo server, @NotNull List<String> treePaths) {
        server.setLibraries(treePaths);
    }

    @NotNull
    public String getConfirmedHome() {
        return confirmedHome;
    }
}
