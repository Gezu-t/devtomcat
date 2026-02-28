package com.dev.idea.plugins.tomcat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BrowserConfig")
class BrowserConfigTest {

    @Nested
    @DisplayName("no-arg constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("browser open enabled by default")
        void openBrowserDefault() {
            BrowserConfig config = new BrowserConfig();
            assertTrue(config.isOpenBrowser());
            assertTrue(config.isAfterLaunchEnabled());
        }

        @Test
        @DisplayName("default URL is empty")
        void defaultUrl() {
            assertEquals("", new BrowserConfig().getUrl());
        }

        @Test
        @DisplayName("JS debugger disabled by default")
        void jsDebuggerDefault() {
            assertFalse(new BrowserConfig().isWithJsDebugger());
        }

        @Test
        @DisplayName("default browser name is System Default")
        void defaultBrowserName() {
            assertEquals("System Default", new BrowserConfig().getBrowserName());
        }
    }

    @Nested
    @DisplayName("3-arg constructor")
    class ThreeArgConstructor {

        @Test
        @DisplayName("sets open browser, url, and js debugger")
        void setsFields() {
            BrowserConfig config = new BrowserConfig(false, "http://localhost:8080", true);
            assertFalse(config.isOpenBrowser());
            assertEquals("http://localhost:8080", config.getUrl());
            assertTrue(config.isWithJsDebugger());
        }

        @Test
        @DisplayName("browser name defaults to System Default")
        void browserNameDefault() {
            BrowserConfig config = new BrowserConfig(true, "http://localhost", false);
            assertEquals("System Default", config.getBrowserName());
        }
    }

    @Nested
    @DisplayName("copy constructor")
    class CopyConstructor {

        @Test
        @DisplayName("copies all fields")
        void copiesAll() {
            BrowserConfig original = new BrowserConfig(false, "http://test.com", true);
            original.setBrowserName("Firefox");

            BrowserConfig copy = new BrowserConfig(original);
            assertEquals(original.isOpenBrowser(), copy.isOpenBrowser());
            assertEquals(original.getUrl(), copy.getUrl());
            assertEquals(original.isWithJsDebugger(), copy.isWithJsDebugger());
            assertEquals(original.getBrowserName(), copy.getBrowserName());
        }
    }

    @Nested
    @DisplayName("URL handling")
    class UrlHandling {

        @Test
        @DisplayName("setUrl trims whitespace")
        void trimsWhitespace() {
            BrowserConfig config = new BrowserConfig();
            config.setUrl("  http://localhost  ");
            assertEquals("http://localhost", config.getUrl());
        }

        @Test
        @DisplayName("getBrowserUrl is alias for getUrl")
        void browserUrlAlias() {
            BrowserConfig config = new BrowserConfig();
            config.setUrl("http://test.com");
            assertEquals(config.getUrl(), config.getBrowserUrl());
        }

        @Test
        @DisplayName("setBrowserUrl is alias for setUrl")
        void setBrowserUrlAlias() {
            BrowserConfig config = new BrowserConfig();
            config.setBrowserUrl("http://test.com");
            assertEquals("http://test.com", config.getUrl());
        }
    }

    @Nested
    @DisplayName("after launch aliases")
    class AfterLaunchAliases {

        @Test
        @DisplayName("isAfterLaunchEnabled matches isOpenBrowser")
        void afterLaunchMatchesOpen() {
            BrowserConfig config = new BrowserConfig();
            config.setOpenBrowser(false);
            assertFalse(config.isAfterLaunchEnabled());
        }

        @Test
        @DisplayName("setAfterLaunchEnabled sets openBrowser")
        void setAfterLaunchSetsOpen() {
            BrowserConfig config = new BrowserConfig();
            config.setAfterLaunchEnabled(false);
            assertFalse(config.isOpenBrowser());
        }
    }

    @Nested
    @DisplayName("isValid")
    class IsValid {

        @Test
        @DisplayName("default config is valid")
        void defaultIsValid() {
            assertTrue(new BrowserConfig().isValid());
        }

        @Test
        @DisplayName("short URL is valid")
        void shortUrlValid() {
            BrowserConfig config = new BrowserConfig();
            config.setUrl("http://localhost:8080/app");
            assertTrue(config.isValid());
        }
    }

    @Nested
    @DisplayName("browserName")
    class BrowserName {

        @Test
        @DisplayName("setBrowserName trims whitespace")
        void trimsWhitespace() {
            BrowserConfig config = new BrowserConfig();
            config.setBrowserName("  Chrome  ");
            assertEquals("Chrome", config.getBrowserName());
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("clone equals original")
        void cloneEquals() {
            BrowserConfig original = new BrowserConfig(false, "http://test.com", true);
            original.setBrowserName("Chrome");
            BrowserConfig cloned = original.clone();
            assertEquals(original, cloned);
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("equal configs")
        void equalConfigs() {
            BrowserConfig a = new BrowserConfig(true, "http://test.com", false);
            BrowserConfig b = new BrowserConfig(true, "http://test.com", false);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different URL not equal")
        void differentUrlNotEqual() {
            BrowserConfig a = new BrowserConfig(true, "http://a.com", false);
            BrowserConfig b = new BrowserConfig(true, "http://b.com", false);
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("different open browser not equal")
        void differentOpenNotEqual() {
            BrowserConfig a = new BrowserConfig(true, "http://test.com", false);
            BrowserConfig b = new BrowserConfig(false, "http://test.com", false);
            assertNotEquals(a, b);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("includes URL")
        void includesUrl() {
            BrowserConfig config = new BrowserConfig();
            config.setUrl("http://localhost:8080");
            assertTrue(config.toString().contains("http://localhost:8080"));
        }
    }
}
