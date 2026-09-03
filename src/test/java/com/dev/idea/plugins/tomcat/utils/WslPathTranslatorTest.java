package com.dev.idea.plugins.tomcat.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive rules of the pure host→distro translator. Neutral placeholder
 * distros and paths only; nothing here touches a filesystem or the platform.
 */
@DisplayName("WslPathTranslator")
class WslPathTranslatorTest {

    private static WslPathTranslator ubuntu() {
        return new WslPathTranslator("Ubuntu");
    }

    @Nested
    @DisplayName("WSL UNC paths")
    class Unc {

        @Test
        @DisplayName("\\\\wsl$\\<distro>\\a\\b becomes /a/b")
        void dollarPrefix() {
            assertEquals("/opt/apache-tomcat",
                    ubuntu().toTarget("\\\\wsl$\\Ubuntu\\opt\\apache-tomcat"));
        }

        @Test
        @DisplayName("\\\\wsl.localhost\\<distro>\\a\\b becomes /a/b")
        void localhostPrefix() {
            assertEquals("/opt/apache-tomcat",
                    ubuntu().toTarget("\\\\wsl.localhost\\Ubuntu\\opt\\apache-tomcat"));
        }

        @Test
        @DisplayName("prefix and distro match case-insensitively")
        void caseInsensitive() {
            assertEquals("/opt/jdk", ubuntu().toTarget("\\\\WSL$\\ubuntu\\opt\\jdk"));
            assertEquals("/opt/jdk", ubuntu().toTarget("\\\\Wsl.LocalHost\\UBUNTU\\opt\\jdk"));
        }

        @Test
        @DisplayName("forward-slash and mixed separators are accepted")
        void forwardAndMixedSeparators() {
            assertEquals("/opt/apache-tomcat/bin/bootstrap.jar",
                    ubuntu().toTarget("//wsl$/Ubuntu/opt/apache-tomcat/bin/bootstrap.jar"));
            assertEquals("/opt/apache-tomcat/bin/bootstrap.jar",
                    ubuntu().toTarget("\\\\wsl$\\Ubuntu\\opt\\apache-tomcat/bin/bootstrap.jar"));
        }

        @Test
        @DisplayName("distro root with no tail is /")
        void distroRoot() {
            assertEquals("/", ubuntu().toTarget("\\\\wsl$\\Ubuntu"));
            assertEquals("/", ubuntu().toTarget("\\\\wsl$\\Ubuntu\\"));
        }

        @Test
        @DisplayName("surrounding whitespace is trimmed")
        void trimmed() {
            assertEquals("/opt/jdk", ubuntu().toTarget("  \\\\wsl$\\Ubuntu\\opt\\jdk  "));
        }

        @Test
        @DisplayName("a UNC naming another distro still translates but reports a warning")
        void crossDistroWarns() {
            List<String> warnings = new ArrayList<>();
            WslPathTranslator t = new WslPathTranslator("Ubuntu", "/mnt/", warnings::add);

            assertEquals("/opt/jdk", t.toTarget("\\\\wsl$\\Debian\\opt\\jdk"));

            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("Debian"), warnings.get(0));
            assertTrue(warnings.get(0).contains("Ubuntu"), warnings.get(0));
        }

        @Test
        @DisplayName("a same-distro UNC reports nothing")
        void sameDistroSilent() {
            List<String> warnings = new ArrayList<>();
            WslPathTranslator t = new WslPathTranslator("Ubuntu", "/mnt/", warnings::add);

            t.toTarget("\\\\wsl$\\ubuntu\\opt\\jdk");

            assertTrue(warnings.isEmpty(), () -> "unexpected: " + warnings);
        }
    }

    @Nested
    @DisplayName("Windows drive paths")
    class Drive {

        @Test
        @DisplayName("C:\\Users\\x becomes /mnt/c/Users/x")
        void backslashDrive() {
            assertEquals("/mnt/c/Users/dev/app", ubuntu().toTarget("C:\\Users\\dev\\app"));
        }

        @Test
        @DisplayName("drive letter is lower-cased; the rest keeps its case")
        void lowercaseLetterOnly() {
            assertEquals("/mnt/d/Projects/App", ubuntu().toTarget("D:\\Projects\\App"));
            assertEquals("/mnt/d/Projects/App", ubuntu().toTarget("d:\\Projects\\App"));
        }

        @Test
        @DisplayName("forward-slash drive form C:/a/b is accepted")
        void forwardSlashDrive() {
            assertEquals("/mnt/c/projects/app", ubuntu().toTarget("C:/projects/app"));
        }

        @Test
        @DisplayName("mixed separators are normalised")
        void mixedSeparators() {
            assertEquals("/mnt/c/Users/dev/base/temp",
                    ubuntu().toTarget("C:\\Users\\dev\\base/temp"));
        }

        @Test
        @DisplayName("bare drive root C:\\ becomes /mnt/c")
        void bareRoot() {
            assertEquals("/mnt/c", ubuntu().toTarget("C:\\"));
            assertEquals("/mnt/c", ubuntu().toTarget("C:/"));
            assertEquals("/mnt/c", ubuntu().toTarget("C:"));
        }

        @Test
        @DisplayName("custom mount root is honoured and normalised to a trailing slash")
        void customMntRoot() {
            assertEquals("/custom/c/Users/dev",
                    new WslPathTranslator("Ubuntu", "/custom/").toTarget("C:\\Users\\dev"));
            assertEquals("/custom/c/Users/dev",
                    new WslPathTranslator("Ubuntu", "/custom").toTarget("C:\\Users\\dev"));
            assertEquals("/custom/", new WslPathTranslator("Ubuntu", "/custom").getMntRoot());
        }

        @Test
        @DisplayName("empty mount root falls back to the default")
        void emptyMntRootDefault() {
            assertEquals(WslPathTranslator.DEFAULT_MNT_ROOT,
                    new WslPathTranslator("Ubuntu", "").getMntRoot());
        }

        @Test
        @DisplayName("a UNC-inside-drive is not a drive path (a plain host share stays unchanged)")
        void plainShareUnchanged() {
            assertEquals("\\\\fileserver\\share\\app",
                    ubuntu().toTarget("\\\\fileserver\\share\\app"));
        }
    }

    @Nested
    @DisplayName("pass-through")
    class PassThrough {

        @Test
        @DisplayName("already-Linux path is unchanged")
        void linuxUnchanged() {
            assertEquals("/opt/apache-tomcat", ubuntu().toTarget("/opt/apache-tomcat"));
        }

        @Test
        @DisplayName("relative path is unchanged")
        void relativeUnchanged() {
            assertEquals("webapps/ROOT", ubuntu().toTarget("webapps/ROOT"));
            assertEquals("conf\\server.xml", ubuntu().toTarget("conf\\server.xml"));
        }

        @Test
        @DisplayName("empty and blank are returned as given")
        void emptyUnchanged() {
            assertEquals("", ubuntu().toTarget(""));
            assertEquals("   ", ubuntu().toTarget("   "));
        }

        @Test
        @DisplayName("something that merely starts with a letter and colon but no separator is unchanged")
        void notADrive() {
            assertEquals("ab:cd", ubuntu().toTarget("ab:cd"));
            assertEquals("1:\\x", ubuntu().toTarget("1:\\x"));
        }
    }

    @Nested
    @DisplayName("classpath")
    class Classpath {

        @Test
        @DisplayName("separator is ':'")
        void separator() {
            assertEquals(":", ubuntu().classpathSeparator());
        }

        @Test
        @DisplayName("join translates every entry and uses ':' regardless of host separator")
        void joinTranslatesEach() {
            String joined = ubuntu().joinClasspath(List.of(
                    "\\\\wsl$\\Ubuntu\\opt\\apache-tomcat\\bin\\bootstrap.jar",
                    "\\\\wsl$\\Ubuntu\\opt\\apache-tomcat\\bin\\tomcat-juli.jar",
                    "C:\\projects\\app\\lib\\extra.jar"));

            assertEquals("/opt/apache-tomcat/bin/bootstrap.jar"
                    + ":/opt/apache-tomcat/bin/tomcat-juli.jar"
                    + ":/mnt/c/projects/app/lib/extra.jar", joined);
            assertFalse(joined.contains(";"));
            assertFalse(joined.contains("\\"));
        }
    }

    @Nested
    @DisplayName("IDENTITY mapper")
    class Identity {

        @Test
        @DisplayName("returns the input unchanged (same instance)")
        void unchanged() {
            String host = "C:\\Users\\dev\\devtomcat\\base";
            assertSame(host, LaunchPathMapper.IDENTITY.toTarget(host));
            assertSame(host, LaunchPathMapper.IDENTITY.toTarget(host));
        }

        @Test
        @DisplayName("uses the host classpath separator")
        void hostSeparator() {
            assertEquals(File.pathSeparator, LaunchPathMapper.IDENTITY.classpathSeparator());
            assertEquals("a" + File.pathSeparator + "b",
                    LaunchPathMapper.IDENTITY.joinClasspath(List.of("a", "b")));
        }
    }
}
