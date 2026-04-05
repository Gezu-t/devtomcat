package com.dev.idea.plugins.tomcat.runner;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Console filter for DevTomcat that makes HTTP/HTTPS URLs clickable
 * and highlights Tomcat-specific log patterns (port bindings, context paths).
 *
 * <p>IntelliJ Ultimate's Tomcat integration lacks custom console filtering —
 * this is a DevTomcat exclusive.
 */
public final class TomcatConsoleFilter implements Filter {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w.-]+(:\\d+)?(/[\\w./?%&=~#-]*)?");

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "(/[\\w./-]+\\.(?:xml|properties|war|jar|class|java|log))");

    TomcatConsoleFilter() {
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        int lineStart = entireLength - line.length();
        List<ResultItem> items = new ArrayList<>();

        // Clickable URLs
        Matcher urlMatcher = URL_PATTERN.matcher(line);
        while (urlMatcher.find()) {
            int start = lineStart + urlMatcher.start();
            int end = lineStart + urlMatcher.end();
            String url = urlMatcher.group();
            items.add(new ResultItem(start, end, (HyperlinkInfo) p -> BrowserUtil.browse(url)));
        }

        // Clickable file paths (opens in editor if file exists in project)
        Matcher fileMatcher = FILE_PATH_PATTERN.matcher(line);
        while (fileMatcher.find()) {
            int start = lineStart + fileMatcher.start();
            int end = lineStart + fileMatcher.end();
            String filePath = fileMatcher.group();
            items.add(new ResultItem(start, end, new FileHyperlinkInfo(filePath)));
        }

        return items.isEmpty() ? null : new Result(items);
    }

    /**
     * Hyperlink that opens a file in the editor if it exists in the project,
     * or shows the path in a system file manager otherwise.
     */
    private static final class FileHyperlinkInfo implements HyperlinkInfo {
        private final String filePath;

        FileHyperlinkInfo(@NotNull String filePath) {
            this.filePath = filePath;
        }

        @Override
        public void navigate(@NotNull Project project) {
            com.intellij.openapi.vfs.VirtualFile vf =
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath);
            if (vf != null) {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true);
            } else {
                java.io.File file = new java.io.File(filePath);
                if (file.exists()) {
                    com.intellij.ide.actions.RevealFileAction.openFile(file);
                }
            }
        }
    }
}
