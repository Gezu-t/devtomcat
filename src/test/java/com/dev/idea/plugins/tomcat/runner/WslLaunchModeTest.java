package com.dev.idea.plugins.tomcat.runner;

import com.dev.idea.plugins.tomcat.TomcatConstants;
import com.dev.idea.plugins.tomcat.utils.LaunchPathMapper;
import com.dev.idea.plugins.tomcat.utils.WslPathDetector;
import com.dev.idea.plugins.tomcat.utils.WslPathTranslator;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.wsl.WSLDistribution;
import com.intellij.openapi.projectRoots.Sdk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pure decision core of WSL mode — distribution matching, the JDK guard,
 * launch-shape refusals, the distro-side java executable, and neutralisation of
 * the platform's host-only launcher hook. The platform lookups are injected, so
 * none of this needs Windows or a WSL install. Neutral placeholder names only.
 */
@DisplayName("WslLaunchMode")
class WslLaunchModeTest {

    private static final String WSL_HOME = "\\\\wsl$\\Ubuntu\\opt\\apache-tomcat";

    private static WSLDistribution distro(String msId) {
        WSLDistribution d = mock(WSLDistribution.class);
        when(d.getMsId()).thenReturn(msId);
        when(d.getId()).thenReturn(msId.toLowerCase());
        return d;
    }

    private static Sdk sdk(String home) {
        Sdk sdk = mock(Sdk.class);
        when(sdk.getHomePath()).thenReturn(home);
        when(sdk.getVersionString()).thenReturn("17.0.10");
        return sdk;
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("a host Tomcat is not WSL mode (null), whatever is installed")
        void hostLaunchIsNull() throws ExecutionException {
            assertNull(WslLaunchMode.resolve("C:\\apache-tomcat-9", List.of(distro("Ubuntu")), d -> "/mnt/"));
            assertNull(WslLaunchMode.resolve("/opt/apache-tomcat", List.of(distro("Ubuntu")), d -> "/mnt/"));
        }

        @Test
        @DisplayName("a WSL home whose distribution is installed resolves with a translator for it")
        void resolvesInstalledDistro() throws ExecutionException {
            WslLaunchMode mode = WslLaunchMode.resolve(WSL_HOME, List.of(distro("Debian"), distro("Ubuntu")), d -> "/mnt/");

            assertNotNull(mode);
            assertEquals("Ubuntu", mode.distroName());
            assertEquals("/opt/apache-tomcat", mode.mapper().toTarget(WSL_HOME));
            assertEquals("/mnt/c/Users/dev", mode.mapper().toTarget("C:\\Users\\dev"));
            assertEquals(":", mode.mapper().classpathSeparator());
        }

        @Test
        @DisplayName("distribution match is case-insensitive and adopts the installed spelling")
        void caseInsensitiveMatch() throws ExecutionException {
            WslLaunchMode mode = WslLaunchMode.resolve(
                    "\\\\wsl.localhost\\UBUNTU\\opt\\apache-tomcat", List.of(distro("Ubuntu")), d -> "/mnt/");

            assertNotNull(mode);
            assertEquals("Ubuntu", mode.distroName());
        }

        @Test
        @DisplayName("the distribution's mount root is honoured; null/empty falls back to /mnt/")
        void mntRootFromDistribution() throws ExecutionException {
            WslLaunchMode custom = WslLaunchMode.resolve(WSL_HOME, List.of(distro("Ubuntu")), d -> "/custom/");
            WslLaunchMode missing = WslLaunchMode.resolve(WSL_HOME, List.of(distro("Ubuntu")), d -> null);

            assertNotNull(custom);
            assertNotNull(missing);
            assertEquals("/custom/c/x", custom.mapper().toTarget("C:\\x"));
            assertEquals(WslPathTranslator.DEFAULT_MNT_ROOT + "c/x", missing.mapper().toTarget("C:\\x"));
        }

        @Test
        @DisplayName("an unresolvable distribution (name mismatch) fails with the honest guard message")
        void nameMismatchThrows() {
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> WslLaunchMode.resolve(WSL_HOME, List.of(distro("Debian")), d -> "/mnt/"));

            assertEquals(WslPathDetector.unsupportedMessage(WSL_HOME), ex.getMessage());
        }

        @Test
        @DisplayName("no installed distributions at all (non-Windows host) fails with the same message")
        void nothingInstalledThrows() {
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> WslLaunchMode.resolve(WSL_HOME, List.of(), d -> "/mnt/"));

            assertEquals(WslPathDetector.unsupportedMessage(WSL_HOME), ex.getMessage());
        }

        @Test
        @DisplayName("a UNC with no distro segment cannot be resolved")
        void noDistroSegmentThrows() {
            assertThrows(ExecutionException.class,
                    () -> WslLaunchMode.resolve("\\\\wsl$\\", List.of(distro("Ubuntu")), d -> "/mnt/"));
        }

        @Test
        @DisplayName("findDistribution prefers the Microsoft id, then the platform id")
        void findDistributionOrder() {
            WSLDistribution byMs = distro("Ubuntu");
            WSLDistribution byId = mock(WSLDistribution.class);
            when(byId.getMsId()).thenReturn("Ubuntu-22.04");
            when(byId.getId()).thenReturn("ubuntu2204");

            assertEquals(byMs, WslLaunchMode.findDistribution(List.of(byId, byMs), "ubuntu"));
            assertEquals(byId, WslLaunchMode.findDistribution(List.of(byMs, byId), "UBUNTU2204"));
            assertNull(WslLaunchMode.findDistribution(List.of(byMs, byId), "Debian"));
        }
    }

    @Nested
    @DisplayName("JDK guard")
    class JdkGuard {

        private WslLaunchMode mode() throws ExecutionException {
            return WslLaunchMode.resolve(WSL_HOME, List.of(distro("Ubuntu")), d -> "/mnt/");
        }

        @Test
        @DisplayName("a Windows-side JDK is refused with the WSL-side JDK message")
        void windowsJdkRefused() throws ExecutionException {
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> mode().requireWslSideJdk(sdk("C:\\Program Files\\Java\\jdk-17")));

            assertEquals(WslLaunchMode.jdkMessage("Ubuntu"), ex.getMessage());
            assertTrue(ex.getMessage().contains("inside the WSL distribution 'Ubuntu'"));
        }

        @Test
        @DisplayName("a JDK reached through the distro UNC, or given in Linux form, passes")
        void wslJdkAccepted() throws ExecutionException {
            WslLaunchMode mode = mode();
            assertDoesNotThrow(() -> mode.requireWslSideJdk(sdk("\\\\wsl$\\Ubuntu\\opt\\jdk-17")));
            assertDoesNotThrow(() -> mode.requireWslSideJdk(sdk("//wsl.localhost/Ubuntu/opt/jdk-17")));
            assertDoesNotThrow(() -> mode.requireWslSideJdk(sdk("/opt/jdk-17")));
        }

        @Test
        @DisplayName("a missing JDK is left to the existing 'no JDK configured' gates")
        void nullJdkPasses() throws ExecutionException {
            assertDoesNotThrow(() -> mode().requireWslSideJdk(null));
        }

        @Test
        @DisplayName("isWslSideJdkHome predicate")
        void predicate() {
            assertTrue(WslLaunchMode.isWslSideJdkHome("\\\\wsl$\\Ubuntu\\opt\\jdk"));
            assertTrue(WslLaunchMode.isWslSideJdkHome("/usr/lib/jvm/java-17"));
            assertFalse(WslLaunchMode.isWslSideJdkHome("C:\\Program Files\\Java\\jdk-17"));
            assertFalse(WslLaunchMode.isWslSideJdkHome("\\\\fileserver\\share\\jdk"));
            assertFalse(WslLaunchMode.isWslSideJdkHome(null));
            assertFalse(WslLaunchMode.isWslSideJdkHome(""));
        }
    }

    @Nested
    @DisplayName("launch-shape guards")
    class LaunchShape {

        @Test
        @DisplayName("a custom startup script is refused")
        void customStartupRefused() {
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> WslLaunchMode.requireSupportedLaunchShape(false, "bin/catalina.sh run", "Run"));

            assertEquals(WslLaunchMode.customStartupMessage(), ex.getMessage());
            assertTrue(ex.getMessage().contains("default startup"));
        }

        @Test
        @DisplayName("custom startup enabled with a blank script falls back to default and is accepted")
        void blankScriptAccepted() {
            assertDoesNotThrow(() -> WslLaunchMode.requireSupportedLaunchShape(false, "   ", "Run"));
            assertDoesNotThrow(() -> WslLaunchMode.requireSupportedLaunchShape(false, null, "Run"));
        }

        @Test
        @DisplayName("default startup under Run and Debug is accepted")
        void defaultStartupAccepted() {
            assertDoesNotThrow(() -> WslLaunchMode.requireSupportedLaunchShape(true, "bin/catalina.sh run", "Run"));
            assertDoesNotThrow(() -> WslLaunchMode.requireSupportedLaunchShape(true, null, "Debug"));
        }

        @Test
        @DisplayName("the Coverage executor is refused")
        void coverageRefused() {
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> WslLaunchMode.requireSupportedLaunchShape(true, null, TomcatConstants.COVERAGE_MODE));

            assertEquals(WslLaunchMode.coverageMessage(), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("distro-side java and console message")
    class ExecutableAndMessage {

        @Test
        @DisplayName("java executable is <translated JDK home>/bin/java, never java.exe")
        void javaExecutable() {
            LaunchPathMapper t = new WslPathTranslator("Ubuntu");
            assertEquals("/opt/jdk-17/bin/java", WslLaunchMode.javaExecutable("\\\\wsl$\\Ubuntu\\opt\\jdk-17", t));
            assertEquals("/opt/jdk-17/bin/java", WslLaunchMode.javaExecutable("\\\\wsl$\\Ubuntu\\opt\\jdk-17\\", t));
            assertEquals("/usr/lib/jvm/java-17/bin/java",
                    WslLaunchMode.javaExecutable("/usr/lib/jvm/java-17/", t));
        }

        @Test
        @DisplayName("console message names the distro, the translated base, and the unverified areas")
        void consoleMessage() throws ExecutionException {
            WslLaunchMode mode = WslLaunchMode.resolve(WSL_HOME, List.of(distro("Ubuntu")), d -> "/mnt/");
            assertNotNull(mode);

            String msg = mode.consoleMessage("C:\\Users\\dev\\devtomcat\\base");

            assertTrue(msg.contains("WSL mode (experimental)"), msg);
            assertTrue(msg.contains("'Ubuntu'"), msg);
            assertTrue(msg.contains("/mnt/c/Users/dev/devtomcat/base"), msg);
            assertTrue(msg.contains("not yet verified"), msg);
        }
    }

    @Nested
    @DisplayName("neutralizeHostLauncherProxy")
    class LauncherProxy {

        @Test
        @DisplayName("drops the IDE's idea_rt.jar -javaagent, keeps every other VM parameter in order")
        void dropsLauncherAgent() {
            JavaParameters params = new JavaParameters();
            params.setMainClass("org.apache.catalina.startup.Bootstrap");
            params.getVMParametersList().addAll(
                    "-Dcatalina.home=/opt/apache-tomcat",
                    "-javaagent:C:\\IDE\\lib\\idea_rt.jar=51234:C:\\IDE\\bin",
                    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
                    "-javaagent:/opt/agents/other-agent.jar=opt");

            WslLaunchMode.neutralizeHostLauncherProxy(params);

            assertEquals(List.of(
                    "-Dcatalina.home=/opt/apache-tomcat",
                    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
                    "-javaagent:/opt/agents/other-agent.jar=opt"),
                    params.getVMParametersList().getList());
            assertEquals("org.apache.catalina.startup.Bootstrap", params.getMainClass());
        }

        @Test
        @DisplayName("undoes the AppMainV2 main-class swap: main class, program args, classpath, properties")
        void undoesMainClassSwap() {
            JavaParameters params = new JavaParameters();
            params.setMainClass(WslLaunchMode.LAUNCHER_MAIN_CLASS);
            params.getProgramParametersList().addAll("org.apache.catalina.startup.Bootstrap", "start");
            // Linux-form fixture: PathsList.add splits on the HOST separator, so a
            // drive-letter path would be torn apart on a ':' host.
            params.getClassPath().add("/ide/lib/idea_rt.jar");
            params.getClassPath().add("/opt/apache-tomcat/bin/bootstrap.jar");
            params.getVMParametersList().addAll(
                    "-Didea.launcher.port=51234", "-Dcatalina.base=/mnt/c/base", "-Didea.launcher.use.21.preview=true");

            WslLaunchMode.neutralizeHostLauncherProxy(params);

            assertEquals("org.apache.catalina.startup.Bootstrap", params.getMainClass());
            assertEquals(List.of("start"), params.getProgramParametersList().getList());
            assertEquals(List.of("/opt/apache-tomcat/bin/bootstrap.jar"), params.getClassPath().getPathList());
            assertEquals(List.of("-Dcatalina.base=/mnt/c/base"), params.getVMParametersList().getList());
        }

        @Test
        @DisplayName("parameters without a launcher hook are left untouched")
        void noHookNoChange() {
            JavaParameters params = new JavaParameters();
            params.setMainClass("org.apache.catalina.startup.Bootstrap");
            params.getProgramParametersList().add("start");
            params.getVMParametersList().addAll("-Dcatalina.home=/opt/apache-tomcat", "-classpath", "/a.jar:/b.jar");

            WslLaunchMode.neutralizeHostLauncherProxy(params);

            assertEquals(List.of("-Dcatalina.home=/opt/apache-tomcat", "-classpath", "/a.jar:/b.jar"),
                    params.getVMParametersList().getList());
            assertEquals(List.of("start"), params.getProgramParametersList().getList());
        }
    }
}
