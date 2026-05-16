package com.dev.idea.plugins.tomcat.runner;

import com.intellij.execution.ConsoleFolding;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Collapses consecutive Tomcat-internal and Servlet-API stack frames into a
 * single foldable summary, the same way the platform folds JDK frames.
 *
 * <p>Tomcat stack traces tend to look like:
 * <pre>
 * java.lang.NullPointerException
 *   at com.myapp.UserService.lookup(UserService.java:42)         &lt;-- user code, keep visible
 *   at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:529)
 *   at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(...)
 *   at org.apache.catalina.core.ApplicationFilterChain.doFilter(...)
 *   at org.apache.catalina.core.StandardWrapperValve.invoke(...)
 *   ... 15 more catalina/coyote frames
 *   at java.base/java.lang.Thread.run(Thread.java:842)
 * </pre>
 * The user's frame is what they want to see; the next ~20 catalina frames are
 * noise. This folding collapses the run so the trace reads as:
 * <pre>
 *   at com.myapp.UserService.lookup(UserService.java:42)
 *   &lt;17 Tomcat / servlet frames&gt;
 *   at java.base/java.lang.Thread.run(Thread.java:842)
 * </pre>
 *
 * <p>Packages covered: {@code org.apache.catalina.*}, {@code org.apache.coyote.*},
 * {@code org.apache.tomcat.*}, {@code org.apache.jasper.*} (JSP runtime),
 * {@code org.apache.el.*} (EL impl), {@code org.apache.naming.*} (JNDI),
 * {@code org.apache.juli.*} (logging), and the servlet API namespaces
 * {@code jakarta.servlet.*} / {@code javax.servlet.*} since servlet-API
 * frames are almost always followed by container-internal frames and folding
 * them together gives a cleaner result.
 *
 * <p>Deliberately <b>not</b> covered: {@code org.apache.commons.*}
 * (third-party utility), {@code org.apache.http.*} (HTTP client), and any
 * other {@code org.apache.*} prefix that is not part of Tomcat. False
 * positives on those would silently hide user-relevant frames.
 */
public final class TomcatStackFrameFolding extends ConsoleFolding {

    private static final Pattern TOMCAT_FRAME = Pattern.compile(
            "^\\s*at\\s+(?:"
                    + "org\\.apache\\.(?:catalina|coyote|tomcat|jasper|el|naming|juli)\\."
                    + "|jakarta\\.servlet\\."
                    + "|javax\\.servlet\\."
                    + ").*");

    @Override
    public boolean shouldFoldLine(@NotNull Project project, @NotNull String line) {
        return TOMCAT_FRAME.matcher(line).matches();
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull Project project, @NotNull List<String> lines) {
        return "  <" + lines.size() + " Tomcat / servlet frames>";
    }
}
