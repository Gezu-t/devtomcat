package com.dev.idea.plugins.tomcat.utils;

import org.jetbrains.annotations.NotNull;

import static com.dev.idea.plugins.tomcat.TomcatConstants.EXT_JAR;

/**
 * Pure, version-independent identity for a library JAR filename. Shared by the
 * deployment pipelines that must reconcile a runtime-classpath library against
 * the JARs a build packaged into a deployed {@code WEB-INF/lib/} — the context-XML
 * overlay ({@code LocalDeploymentStrategy}) and the class-output mirror
 * ({@code DeployedClassesSync}) — so both compare names on exactly the same basis.
 */
public final class LibraryArtifactNames {

    private LibraryArtifactNames() {}

    /**
     * Reduces a library JAR filename to its <em>artifact key</em>: the
     * artifactId stem with the trailing version (and any version/classifier
     * suffix) removed. Two filenames that are the same library at different
     * versions share a key, so a dedup can treat {@code WEB-INF/lib} as
     * authoritative for a library regardless of the exact version on disk.
     *
     * <p>JAR names follow {@code <artifactId>[-<classifier>]-<version>.jar},
     * and the version segment starts with a digit while artifactId segments do
     * not — e.g. {@code commons-lang3-3.12.0.jar} keys to {@code commons-lang3}
     * (the {@code 3} in {@code lang3} is mid-segment, not a leading digit),
     * {@code log4j-api-2.20.0.jar} to {@code log4j-api}. The key is every
     * {@code '-'}-delimited segment up to the first one that begins with a
     * digit; with no such segment the whole base name is the key. The first
     * segment is never treated as a version, so an artifactId that itself
     * begins with a digit still yields a non-empty key.
     *
     * <p>Trade-off: two artifacts that share an artifactId across different
     * groups ({@code org.a:utils} vs {@code org.b:utils}) collapse to the same
     * key. JAR filenames carry no groupId, so that case is already
     * indistinguishable here and is vanishingly rare; avoiding the
     * duplicate-resource failure is the better bias.
     */
    @NotNull
    public static String libraryArtifactKey(@NotNull String jarFileName) {
        String name = jarFileName;
        if (name.length() >= EXT_JAR.length()
                && name.regionMatches(true, name.length() - EXT_JAR.length(),
                                      EXT_JAR, 0, EXT_JAR.length())) {
            name = name.substring(0, name.length() - EXT_JAR.length());
        }
        String[] segments = name.split("-");
        StringBuilder stem = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i > 0 && !segment.isEmpty() && Character.isDigit(segment.charAt(0))) {
                break;
            }
            if (stem.length() > 0) stem.append('-');
            stem.append(segment);
        }
        return stem.length() == 0 ? name : stem.toString();
    }
}
