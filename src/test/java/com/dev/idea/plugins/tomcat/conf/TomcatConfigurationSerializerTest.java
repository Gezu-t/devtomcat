package com.dev.idea.plugins.tomcat.conf;

import com.dev.idea.plugins.tomcat.model.*;
import com.dev.idea.plugins.tomcat.setting.TomcatInfo;
import com.dev.idea.plugins.tomcat.model.RunnerSettings;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.artifacts.ArtifactModel;
import com.intellij.packaging.artifacts.ArtifactPointer;
import org.jdom.Attribute;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TomcatConfigurationSerializer")
class TomcatConfigurationSerializerTest {

    /**
     * Core round-trip test: write a fully-populated config, read it back,
     * and verify every field survives the trip.
     */
    @Test
    @DisplayName("full round-trip preserves all fields")
    void fullRoundTrip() {
        TomcatConfigurationData original = createFullConfig();
        Element element = new Element("configuration");

        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        // Port config
        assertEquals(original.getPortConfig().getHttp(), restored.getPortConfig().getHttp());
        assertEquals(original.getPortConfig().getShutdown(), restored.getPortConfig().getShutdown());
        assertEquals(original.getPortConfig().getHttps(), restored.getPortConfig().getHttps());
        assertEquals(original.getPortConfig().isHttpsEnabled(), restored.getPortConfig().isHttpsEnabled());
        assertEquals(original.getPortConfig().getJmx(), restored.getPortConfig().getJmx());
        assertEquals(original.getPortConfig().isJmxEnabled(), restored.getPortConfig().isJmxEnabled());
        assertEquals(original.getPortConfig().getAjp(), restored.getPortConfig().getAjp());
        assertEquals(original.getPortConfig().isAjpEnabled(), restored.getPortConfig().isAjpEnabled());

        // Basic settings
        assertEquals(original.getContextPath(), restored.getContextPath());
        assertEquals(original.getServerMode(), restored.getServerMode());
        assertEquals(original.getCatalinaBase(), restored.getCatalinaBase());
        assertEquals(original.getJreSelection(), restored.getJreSelection());
        assertEquals(original.isStoreAsProjectFile(), restored.isStoreAsProjectFile());
        assertEquals(original.isAllowMultipleInstances(), restored.isAllowMultipleInstances());

        // VM config
        assertEquals(original.getVmConfig().getVmOptions(), restored.getVmConfig().getVmOptions());
        assertEquals(original.getRunnerSettings("Run").isPassParentEnvs(), restored.getRunnerSettings("Run").isPassParentEnvs());
        assertEquals(original.getRunnerSettings("Run").getEnvironmentVariables(), restored.getRunnerSettings("Run").getEnvironmentVariables());

        // Browser config
        assertEquals(original.getBrowserConfig().getBrowserUrl(), restored.getBrowserConfig().getBrowserUrl());
        assertEquals(original.getBrowserConfig().isAfterLaunchEnabled(), restored.getBrowserConfig().isAfterLaunchEnabled());
        assertEquals(original.getBrowserConfig().isWithJsDebugger(), restored.getBrowserConfig().isWithJsDebugger());

        // Deployment config
        assertEquals(original.getDeploymentConfig().isHotDeploymentEnabled(), restored.getDeploymentConfig().isHotDeploymentEnabled());
        assertEquals(original.getDeploymentConfig().isUpdateClassesAndResources(), restored.getDeploymentConfig().isUpdateClassesAndResources());
        assertEquals(original.getDeploymentConfig().isPreserveSessions(), restored.getDeploymentConfig().isPreserveSessions());

        // Update config
        assertEquals(original.getUpdateConfig().getOnUpdate(), restored.getUpdateConfig().getOnUpdate());
        assertEquals(original.getUpdateConfig().getOnFrameDeactivation(), restored.getUpdateConfig().getOnFrameDeactivation());
        assertEquals(original.getUpdateConfig().isShowUpdateDialog(), restored.getUpdateConfig().isShowUpdateDialog());
        assertEquals(original.getUpdateConfig().isShowFrameDeactivationDialog(), restored.getUpdateConfig().isShowFrameDeactivationDialog());

        // UI config
        assertEquals(original.getUiConfig().isActivateToolWindow(), restored.getUiConfig().isActivateToolWindow());

        // Debug config
        assertEquals(original.getDebugConfig().getPort(), restored.getDebugConfig().getPort());
        assertEquals(original.getDebugConfig().getTransport(), restored.getDebugConfig().getTransport());
        assertEquals(original.getDebugConfig().isUseModuleClasspath(), restored.getDebugConfig().isUseModuleClasspath());

        // Remote config
        assertEquals(original.getRemoteConfig().getManagerUrl(), restored.getRemoteConfig().getManagerUrl());
        assertEquals(original.getRemoteConfig().getUsername(), restored.getRemoteConfig().getUsername());
        assertTrue(
                restored.getRemoteConfig().getPassword().isEmpty()
                        || original.getRemoteConfig().getPassword().equals(restored.getRemoteConfig().getPassword()),
                "Password may be externalized to PasswordSafe during serialization"
        );
        assertEquals(original.getRemoteConfig().isUseCredentials(), restored.getRemoteConfig().isUseCredentials());

        // TomcatInfo
        assertNotNull(restored.getTomcatInfo());
        assertEquals(original.getTomcatInfo().getName(), restored.getTomcatInfo().getName());
        assertEquals(original.getTomcatInfo().getVersion(), restored.getTomcatInfo().getVersion());
        assertEquals(original.getTomcatInfo().getPath(), restored.getTomcatInfo().getPath());
    }

    @Test
    @DisplayName("round-trip preserves deployment name and context path")
    void roundTripArtifacts() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.getDeploymentConfig().setDeployments(List.of(
                new ArtifactBackedDeployment(
                        DeploymentPointers.detachedArtifactPointer("myapp"), "/myapp", "/path/to/myapp.war", false),
                new ArtifactBackedDeployment(
                        DeploymentPointers.detachedArtifactPointer("api"), "/api", "/path/to/api.war", false)));

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        List<Deployment> deployments = restored.getDeploymentConfig().getDeployments();
        assertEquals(2, deployments.size());
        assertEquals("myapp", deployments.get(0).getDisplayName());
        assertEquals("/myapp", deployments.get(0).getContextPath());
        assertEquals("api", deployments.get(1).getDisplayName());
        assertEquals("/api", deployments.get(1).getContextPath());
    }

    @Test
    @DisplayName("round-trip preserves deployment provenance (artifact / module / external)")
    void roundTripDeploymentKinds() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.getDeploymentConfig().setDeployments(List.of(
                new ArtifactBackedDeployment(
                        DeploymentPointers.detachedArtifactPointer("app-war"), "/a", "/out/app.war", false),
                new ModuleBackedDeployment(
                        DeploymentPointers.detachedModulePointer("detected"),
                        Path.of("/build/detected"), "/b", true),
                new ExternalFileDeployment(Path.of("/tmp/my.war"), "/c", false)));

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        List<Deployment> deployments = restored.getDeploymentConfig().getDeployments();
        assertEquals(3, deployments.size());
        assertEquals(DeploymentKind.ARTIFACT, deployments.get(0).getKind());
        assertEquals(DeploymentKind.MODULE,   deployments.get(1).getKind());
        assertEquals(DeploymentKind.EXTERNAL, deployments.get(2).getKind());
    }

    @Test
    @DisplayName("legacy config with type='external' deserializes to an external, war-packaged deployment")
    void legacyTypeExternalMigrates() {
        // Simulates a config written by an older plugin version that only knew
        // about type and treated 'external' as a packaging value. The new reader
        // must route that to an external deployment (so the validator and
        // refresher skip it), and the packaging falls back to WAR so downstream
        // deployment code has a sensible default.
        Element element = new Element("configuration");
        Element deployments = new Element("deployments");
        Element art = new Element("artifact");
        art.setAttribute("name", "legacy.war");
        art.setAttribute("path", "/tmp/legacy.war");
        art.setAttribute("type", "external");
        art.setAttribute("contextPath", "/legacy");
        deployments.addContent(art);
        element.addContent(deployments);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        List<Deployment> restoredDeployments = restored.getDeploymentConfig().getDeployments();
        assertEquals(1, restoredDeployments.size());
        Deployment only = restoredDeployments.get(0);
        assertEquals(DeploymentKind.EXTERNAL, only.getKind(),
                "legacy type='external' must map to an external deployment on read");
        assertFalse(only.isExploded(),
                "packaging must default to WAR (not exploded) when the legacy config gave no real packaging");
    }

    @Test
    @DisplayName("legacy config without source attribute defaults to an artifact-backed deployment")
    void legacyMissingSourceDefaultsIntelliJArtifact() {
        Element element = new Element("configuration");
        Element deployments = new Element("deployments");
        Element art = new Element("artifact");
        art.setAttribute("name", "classic");
        art.setAttribute("path", "/out/classic.war");
        art.setAttribute("type", "war");
        art.setAttribute("contextPath", "/classic");
        // No source attribute at all.
        deployments.addContent(art);
        element.addContent(deployments);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        Deployment only = restored.getDeploymentConfig().getDeployments().get(0);
        assertEquals(DeploymentKind.ARTIFACT, only.getKind(),
                "absent source attribute must default to INTELLIJ_ARTIFACT (artifact-backed) so "
                + "legacy configs keep their pre-split behaviour (validator still enforces "
                + "IntelliJ-artifact presence, refresher still rename-tracks them).");
    }

    @Test
    @DisplayName("round-trip preserves environment variables")
    void roundTripEnvironmentVariables() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.getRunnerSettings("Run").setEnvironmentVariables(Map.of(
                "JAVA_HOME", "/usr/lib/jvm/java-17",
                "CATALINA_OPTS", "-Xmx1024m"
        ));

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        Map<String, String> env = restored.getRunnerSettings("Run").getEnvironmentVariables();
        assertEquals("/usr/lib/jvm/java-17", env.get("JAVA_HOME"));
        assertEquals("-Xmx1024m", env.get("CATALINA_OPTS"));
    }

    @Test
    @DisplayName("round-trip preserves coverage config")
    void roundTripCoverageConfig() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.getCoverageConfig().setIncludePatterns(List.of("com.example.*", "com.app.*"));
        original.getCoverageConfig().setExcludePatterns(List.of("com.example.test.*"));

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        assertEquals(List.of("com.example.*", "com.app.*"), restored.getCoverageConfig().getIncludePatterns());
        assertEquals(List.of("com.example.test.*"), restored.getCoverageConfig().getExcludePatterns());
    }

    @Test
    @DisplayName("read empty element produces defaults")
    void readEmptyElement() {
        TomcatConfigurationData data = new TomcatConfigurationData();
        Element element = new Element("configuration");

        TomcatConfigurationSerializer.read(data, element);

        // Should have defaults, not crash
        assertEquals("/", data.getContextPath());
        assertNull(data.getTomcatInfo());
        assertEquals(0, data.getDeploymentConfig().getDeployments().size());
    }

    @Test
    @DisplayName("write-read with default config produces equivalent defaults")
    void defaultConfigRoundTrip() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        Element element = new Element("configuration");

        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        assertEquals(original.getPortConfig(), restored.getPortConfig());
        assertEquals(original.getContextPath(), restored.getContextPath());
        assertEquals(original.getServerMode(), restored.getServerMode());
    }

    @Test
    @DisplayName("round-trip preserves per-runner settings (startup/shutdown scripts, env vars)")
    void roundTripRunnerSettings() {
        TomcatConfigurationData original = new TomcatConfigurationData();

        RunnerSettings runSettings = original.getRunnerSettings("Run");
        runSettings.setUseDefaultStartup(false);
        runSettings.setStartupScript("/opt/scripts/start.sh");
        runSettings.setUseDefaultShutdown(false);
        runSettings.setShutdownScript("/opt/scripts/stop.sh");
        runSettings.setPassParentEnvs(false);
        runSettings.setEnvironmentVariables(Map.of("CATALINA_OPTS", "-Xmx2g"));
        runSettings.setComputedEnvironmentKeys(java.util.Set.of("JAVA_OPTS"));
        runSettings.setDeletedComputedEnvironmentKeys(java.util.Set.of("CATALINA_OPTS"));

        RunnerSettings debugSettings = original.getRunnerSettings("Debug");
        debugSettings.setUseDefaultStartup(true);
        debugSettings.setStartupScript("");
        debugSettings.setPassParentEnvs(true);
        debugSettings.setEnvironmentVariables(Map.of("DEBUG", "true"));

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        // Verify Run profile
        RunnerSettings restoredRun = restored.getRunnerSettings("Run");
        assertFalse(restoredRun.isUseDefaultStartup());
        assertEquals("/opt/scripts/start.sh", restoredRun.getStartupScript());
        assertFalse(restoredRun.isUseDefaultShutdown());
        assertEquals("/opt/scripts/stop.sh", restoredRun.getShutdownScript());
        assertFalse(restoredRun.isPassParentEnvs());
        assertEquals("-Xmx2g", restoredRun.getEnvironmentVariables().get("CATALINA_OPTS"));
        assertTrue(restoredRun.getComputedEnvironmentKeys().contains("JAVA_OPTS"));
        assertTrue(restoredRun.getDeletedComputedEnvironmentKeys().contains("CATALINA_OPTS"));

        // Verify Debug profile
        RunnerSettings restoredDebug = restored.getRunnerSettings("Debug");
        assertTrue(restoredDebug.isUseDefaultStartup());
        assertTrue(restoredDebug.isPassParentEnvs());
        assertEquals("true", restoredDebug.getEnvironmentVariables().get("DEBUG"));
    }

    @Test
    @DisplayName("backward compat: old JS debugger attribute name is read correctly")
    void backwardCompatJsDebugger() {
        Element element = new Element("configuration");
        // Old attribute name (pre-rename)
        element.setAttribute("withJavaScriptDebugger", "true");
        element.setAttribute("afterLaunchEnabled", "true");

        TomcatConfigurationData data = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(data, element);

        assertTrue(data.getBrowserConfig().isWithJsDebugger());
    }

    @Test
    @DisplayName("backward compat: old updateAction attribute maps to onUpdate")
    void backwardCompatUpdateAction() {
        Element element = new Element("configuration");
        element.setAttribute("updateAction", "redeploy");

        TomcatConfigurationData data = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(data, element);

        assertEquals("redeploy", data.getUpdateConfig().getOnUpdate());
    }

    @Test
    @DisplayName("write then read with null TomcatInfo does not crash")
    void nullTomcatInfoRoundTrip() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.setTomcatInfo(null);

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        assertNull(restored.getTomcatInfo());
    }

    @Test
    @DisplayName("write with special characters in context path survives round-trip")
    void specialCharsInContextPath() {
        TomcatConfigurationData original = new TomcatConfigurationData();
        original.setContextPath("/my-app_v2.0");
        original.getVmConfig().setVmOptions("-Dfoo=\"bar & baz\"");

        Element element = new Element("configuration");
        TomcatConfigurationSerializer.write(original, element);

        TomcatConfigurationData restored = new TomcatConfigurationData();
        TomcatConfigurationSerializer.read(restored, element);

        assertEquals("/my-app_v2.0", restored.getContextPath());
        assertEquals("-Dfoo=\"bar & baz\"", restored.getVmConfig().getVmOptions());
    }

    @Nested
    @DisplayName("typed deployment persistence")
    class TypedDeploymentPersistence {

        // ------------------------------------------------------------------
        // a. Legacy XML (no kind attribute) → typed subclasses
        // ------------------------------------------------------------------

        @Test
        @DisplayName("legacy full attrs without source maps to ArtifactBackedDeployment")
        void legacyFullAttrsToArtifactBacked() {
            Element element = configWith(legacyArtifact(
                    "app-1.0.0", "/projects/X/out/app-1.0.0.war", "war", "/app", null));

            List<Deployment> deployments = read(element);

            assertEquals(1, deployments.size());
            ArtifactBackedDeployment a = assertInstanceOf(ArtifactBackedDeployment.class, deployments.get(0));
            assertEquals("app-1.0.0", a.getArtifactName());
            assertEquals("/projects/X/out/app-1.0.0.war", a.getLastKnownPath());
            assertEquals("/app", a.getContextPath());
            assertFalse(a.isExploded());
        }

        @Test
        @DisplayName("legacy type='external' maps to ExternalFileDeployment with war packaging")
        void legacyTypeExternalToExternal() {
            Element element = configWith(legacyArtifact(
                    "app-1.0.0.war", "/projects/X/app-1.0.0.war", "external", "/app", null));

            List<Deployment> deployments = read(element);

            ExternalFileDeployment e = assertInstanceOf(ExternalFileDeployment.class, deployments.get(0));
            assertEquals(Path.of("/projects/X/app-1.0.0.war"), e.getExternalPath());
            assertEquals("/app", e.getContextPath());
            assertFalse(e.isExploded(), "lost legacy packaging must recover as war");
        }

        @Test
        @DisplayName("legacy source attribute drives the typed subclass for each enum value")
        void legacySourcePerEnumValue() {
            Element element = configWith(
                    legacyArtifact("app-1.0.0", "/projects/X/out/app-1.0.0.war", "war", "/a", "INTELLIJ_ARTIFACT"),
                    legacyArtifact("web-module.war", "/projects/X/target/web-module", "exploded", "/b", "AUTO_DETECTED"),
                    legacyArtifact("app-1.0.0.war", "/projects/Y/app-1.0.0.war", "war", "/c", "EXTERNAL"));

            List<Deployment> deployments = read(element);

            assertEquals(3, deployments.size());
            assertInstanceOf(ArtifactBackedDeployment.class, deployments.get(0));

            ModuleBackedDeployment m = assertInstanceOf(ModuleBackedDeployment.class, deployments.get(1));
            assertEquals("web-module.war", m.getLegacyName(), "stored name becomes the legacy name");
            assertEquals(Path.of("/projects/X/target/web-module"), m.getOutputPath());
            assertTrue(m.isExploded());

            assertInstanceOf(ExternalFileDeployment.class, deployments.get(2));
        }

        @Test
        @DisplayName("legacy missing type defaults to war, missing contextPath defaults to /")
        void legacyMissingTypeAndContextDefaults() {
            Element element = configWith(legacyArtifact(
                    "app-1.0.0", "/projects/X/out/app-1.0.0.war", null, null, null));

            List<Deployment> deployments = read(element);

            ArtifactBackedDeployment a = assertInstanceOf(ArtifactBackedDeployment.class, deployments.get(0));
            assertFalse(a.isExploded());
            assertEquals("/", a.getContextPath());
        }

        // ------------------------------------------------------------------
        // b. New-shape round trip is attribute-stable
        // ------------------------------------------------------------------

        @Test
        @DisplayName("write -> read -> write keeps every attribute stable (new + legacy)")
        void newShapeRoundTripAttributeStable() {
            TomcatConfigurationData original = typedConfig();

            Element first = new Element("configuration");
            TomcatConfigurationSerializer.write(original, first);

            TomcatConfigurationData reread = new TomcatConfigurationData();
            TomcatConfigurationSerializer.read(reread, first);
            Element second = new Element("configuration");
            TomcatConfigurationSerializer.write(reread, second);

            List<Element> a = first.getChild("deployments").getChildren("artifact");
            List<Element> b = second.getChild("deployments").getChildren("artifact");
            assertEquals(a.size(), b.size());
            for (int i = 0; i < a.size(); i++) {
                assertEquals(attrsOf(a.get(i)), attrsOf(b.get(i)),
                        "entry " + i + " must round-trip attribute-identical");
            }
        }

        // ------------------------------------------------------------------
        // c. Upgrade: legacy in, kind-tagged + legacy attrs out, idempotent
        // ------------------------------------------------------------------

        @Test
        @DisplayName("upgrade emits kind (+module) plus correct legacy attrs, and re-reading is idempotent")
        void upgradeEmitsKindAndStaysIdempotent() {
            Element legacyXml = configWith(
                    legacyArtifact("app-1.0.0", "/projects/X/out/app-1.0.0.war", "war", "/a", null),
                    legacyArtifact("web-module.war", "/projects/X/target/web-module", "exploded", "/b", "AUTO_DETECTED"),
                    legacyArtifact("app-1.0.0.war", "/projects/Y/app-1.0.0.war", "war", "/c", "EXTERNAL"));

            TomcatConfigurationData upgraded = new TomcatConfigurationData();
            TomcatConfigurationSerializer.read(upgraded, legacyXml);
            Element written = new Element("configuration");
            TomcatConfigurationSerializer.write(upgraded, written);

            List<Element> arts = written.getChild("deployments").getChildren("artifact");
            assertEquals(3, arts.size());

            Element artifactBacked = arts.get(0);
            assertEquals("artifact", artifactBacked.getAttributeValue("kind"));
            assertNull(artifactBacked.getAttributeValue("module"));
            assertEquals("app-1.0.0", artifactBacked.getAttributeValue("name"));
            assertEquals("/projects/X/out/app-1.0.0.war", artifactBacked.getAttributeValue("path"));
            assertEquals("war", artifactBacked.getAttributeValue("type"));
            assertEquals("/a", artifactBacked.getAttributeValue("contextPath"));
            assertEquals("INTELLIJ_ARTIFACT", artifactBacked.getAttributeValue("source"));

            Element moduleBacked = arts.get(1);
            assertEquals("module", moduleBacked.getAttributeValue("kind"));
            assertEquals("web-module.war", moduleBacked.getAttributeValue("module"));
            assertEquals("web-module.war", moduleBacked.getAttributeValue("name"));
            assertEquals("/projects/X/target/web-module", moduleBacked.getAttributeValue("path"));
            assertEquals("exploded", moduleBacked.getAttributeValue("type"));
            assertEquals("/b", moduleBacked.getAttributeValue("contextPath"));
            assertEquals("AUTO_DETECTED", moduleBacked.getAttributeValue("source"));

            Element external = arts.get(2);
            assertEquals("external", external.getAttributeValue("kind"));
            assertNull(external.getAttributeValue("module"));
            assertEquals("app-1.0.0.war", external.getAttributeValue("name"));
            assertEquals("/projects/Y/app-1.0.0.war", external.getAttributeValue("path"));
            assertEquals("war", external.getAttributeValue("type"));
            assertEquals("/c", external.getAttributeValue("contextPath"));
            assertEquals("EXTERNAL", external.getAttributeValue("source"));

            // Idempotence: reading the upgraded XML yields an equal typed list.
            assertEquals(read(legacyXml), read(written));
        }

        // ------------------------------------------------------------------
        // d. Downgrade simulation: old reader ignores kind/module
        // ------------------------------------------------------------------

        @Test
        @DisplayName("stripping kind and module from new-shape XML lands each entry on the same subtype and fields")
        void downgradeViaLegacyAttrsIsLossless() {
            Element written = new Element("configuration");
            TomcatConfigurationSerializer.write(typedConfig(), written);

            for (Element art : written.getChild("deployments").getChildren("artifact")) {
                art.removeAttribute("kind");
                art.removeAttribute("module");
            }

            List<Deployment> typed = typedConfig().getDeploymentConfig().getDeployments();
            List<Deployment> downgraded = read(written);
            assertEquals(typed.size(), downgraded.size());
            for (int i = 0; i < typed.size(); i++) {
                Deployment expected = typed.get(i);
                Deployment actual = downgraded.get(i);
                assertEquals(expected.getClass(), actual.getClass(), "entry " + i + " subtype");
                assertEquals(expected.getContextPath(), actual.getContextPath(), "entry " + i + " context");
                assertEquals(expected.isExploded(), actual.isExploded(), "entry " + i + " packaging");
            }
            ArtifactBackedDeployment a = (ArtifactBackedDeployment) downgraded.get(0);
            assertEquals("/projects/X/out/app-1.0.0.war", a.getLastKnownPath());
            ModuleBackedDeployment m = (ModuleBackedDeployment) downgraded.get(1);
            assertEquals(Path.of("/projects/X/target/web-module"), m.getOutputPath());
            ExternalFileDeployment e = (ExternalFileDeployment) downgraded.get(2);
            assertEquals(Path.of("/projects/Y/app-1.0.0.war"), e.getExternalPath());
        }

        // ------------------------------------------------------------------
        // e. Macro behaviour without a project
        // ------------------------------------------------------------------

        @Test
        @DisplayName("null project passes macro-carrying paths through unchanged in both directions")
        void nullProjectPassesMacrosThrough() {
            String raw = "$PROJECT_DIR$/target/web-module";
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getDeploymentConfig().setDeployments(List.of(new ModuleBackedDeployment(
                    DeploymentPointers.detachedModulePointer("web-module"),
                    Path.of(raw), "/app", true)));

            Element element = new Element("configuration");
            TomcatConfigurationSerializer.write(data, element);
            Element art = element.getChild("deployments").getChildren("artifact").get(0);
            assertEquals(raw, art.getAttributeValue("path"), "write must not touch the raw path");

            ModuleBackedDeployment reread =
                    assertInstanceOf(ModuleBackedDeployment.class, read(element).get(0));
            assertEquals(Path.of(raw), reread.getOutputPath(), "read must not touch the raw path");
        }

        // ------------------------------------------------------------------
        // f. Forward compatibility: unknown kind uses the legacy attrs
        // ------------------------------------------------------------------

        @Test
        @DisplayName("unknown kind value falls through to the source-based legacy mapping")
        void unknownKindFallsBackToLegacyMapping() {
            Element artifactShaped = legacyArtifact(
                    "app-1.0.0", "/projects/X/out/app-1.0.0.war", "war", "/a", "INTELLIJ_ARTIFACT");
            artifactShaped.setAttribute("kind", "something-else");
            Element moduleShaped = legacyArtifact(
                    "web-module.war", "/projects/X/target/web-module", "exploded", "/b", "AUTO_DETECTED");
            moduleShaped.setAttribute("kind", "something-else");

            List<Deployment> deployments = read(configWith(artifactShaped, moduleShaped));

            assertEquals(2, deployments.size());
            ArtifactBackedDeployment a = assertInstanceOf(ArtifactBackedDeployment.class, deployments.get(0));
            assertEquals("app-1.0.0", a.getArtifactName());
            assertEquals("/projects/X/out/app-1.0.0.war", a.getLastKnownPath());
            assertEquals("/a", a.getContextPath());
            assertFalse(a.isExploded());
            ModuleBackedDeployment m = assertInstanceOf(ModuleBackedDeployment.class, deployments.get(1));
            assertEquals(Path.of("/projects/X/target/web-module"), m.getOutputPath());
            assertEquals("/b", m.getContextPath());
            assertTrue(m.isExploded());
        }

        @Test
        @DisplayName("kind=module without (or with empty) module attr falls back to the name attr for the pointer")
        void moduleKindMissingOrEmptyModuleAttrFallsBackToName() {
            Element missingModuleAttr = legacyArtifact(
                    "web-module", "/projects/X/target/web-module", "exploded", "/a", "AUTO_DETECTED");
            missingModuleAttr.setAttribute("kind", "module");
            Element emptyModuleAttr = legacyArtifact(
                    "web-module-2", "/projects/X/target/web-module-2", "exploded", "/b", "AUTO_DETECTED");
            emptyModuleAttr.setAttribute("kind", "module");
            emptyModuleAttr.setAttribute("module", "");

            List<Deployment> deployments = read(configWith(missingModuleAttr, emptyModuleAttr));

            ModuleBackedDeployment m1 = assertInstanceOf(ModuleBackedDeployment.class, deployments.get(0));
            assertEquals("web-module", m1.getModuleName(), "missing module attr must fall back to name");
            ModuleBackedDeployment m2 = assertInstanceOf(ModuleBackedDeployment.class, deployments.get(1));
            assertEquals("web-module-2", m2.getModuleName(), "empty module attr must fall back to name");
        }

        // ------------------------------------------------------------------
        // g. Write path never dereferences the artifact pointer
        // ------------------------------------------------------------------

        @Test
        @DisplayName("artifact-backed write uses lastKnownExploded — a pointer that throws on deref still serializes")
        void writeDoesNotDereferenceArtifactPointer() {
            ArtifactPointer explosive = new ArtifactPointer() {
                @Override public @NotNull String getArtifactName() { return "app-1.0.0"; }
                @Override public Artifact getArtifact() {
                    throw new AssertionError("write path must not resolve the pointer");
                }
                @Override public @NotNull String getArtifactName(@NotNull ArtifactModel model) { return "app-1.0.0"; }
                @Override public Artifact findArtifact(@NotNull ArtifactModel model) {
                    throw new AssertionError("write path must not resolve the pointer");
                }
            };
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getDeploymentConfig().setDeployments(List.of(new ArtifactBackedDeployment(
                    explosive, "/app", "/projects/X/out", true)));

            Element element = new Element("configuration");
            TomcatConfigurationSerializer.write(data, element);

            Element art = element.getChild("deployments").getChildren("artifact").get(0);
            assertEquals("exploded", art.getAttributeValue("type"), "type must come from lastKnownExploded");
            assertEquals("/projects/X/out", art.getAttributeValue("path"));
            assertEquals("app-1.0.0", art.getAttributeValue("name"));
        }

        // ------------------------------------------------------------------
        // helpers
        // ------------------------------------------------------------------

        private TomcatConfigurationData typedConfig() {
            TomcatConfigurationData data = new TomcatConfigurationData();
            data.getDeploymentConfig().setDeployments(List.of(
                    new ArtifactBackedDeployment(
                            DeploymentPointers.detachedArtifactPointer("app-1.0.0"),
                            "/a", "/projects/X/out/app-1.0.0.war", false),
                    new ModuleBackedDeployment(
                            DeploymentPointers.detachedModulePointer("web-module"),
                            Path.of("/projects/X/target/web-module"), "/b", true, "web-module.war"),
                    new ExternalFileDeployment(
                            Path.of("/projects/Y/app-1.0.0.war"), "/c", false)));
            return data;
        }

        private List<Deployment> read(Element element) {
            TomcatConfigurationData data = new TomcatConfigurationData();
            TomcatConfigurationSerializer.read(data, element);
            return data.getDeploymentConfig().getDeployments();
        }

        private Element configWith(Element... artifacts) {
            Element element = new Element("configuration");
            Element deployments = new Element("deployments");
            for (Element art : artifacts) deployments.addContent(art);
            element.addContent(deployments);
            return element;
        }

        private Element legacyArtifact(String name, String path, String type, String context, String source) {
            Element art = new Element("artifact");
            art.setAttribute("name", name);
            art.setAttribute("path", path);
            if (type != null) art.setAttribute("type", type);
            if (context != null) art.setAttribute("contextPath", context);
            if (source != null) art.setAttribute("source", source);
            return art;
        }

        private Map<String, String> attrsOf(Element element) {
            Map<String, String> out = new HashMap<>();
            for (Attribute attribute : element.getAttributes()) {
                out.put(attribute.getName(), attribute.getValue());
            }
            return out;
        }
    }

    private TomcatConfigurationData createFullConfig() {
        TomcatConfigurationData data = new TomcatConfigurationData();

        // Ports
        PortConfig pc = data.getPortConfig();
        pc.setHttp(9090);
        pc.setShutdown(9005);
        pc.setHttps(9443);
        pc.setHttpsEnabled(true);
        pc.setJmx(9099);
        pc.setJmxEnabled(true);
        pc.setAjp(9009);
        pc.setAjpEnabled(true);

        // Basic
        data.setContextPath("/myapp");
        data.setServerMode("Remote");
        data.setCatalinaBase("/opt/catalina-base");
        data.setJreSelection("/usr/lib/jvm/java-17");
        data.setStoreAsProjectFile(true);
        data.setAllowMultipleInstances(true);

        // VM
        data.getVmConfig().setVmOptions("-Xmx1024m -Xms512m");
        data.getRunnerSettings("Run").setPassParentEnvs(false);
        data.getRunnerSettings("Run").setEnvironmentVariables(Map.of("JAVA_HOME", "/usr/lib/jvm/java-17"));

        // Browser
        data.getBrowserConfig().setBrowserUrl("http://localhost:9090/myapp");
        data.getBrowserConfig().setAfterLaunchEnabled(false);
        data.getBrowserConfig().setWithJsDebugger(true);

        // Deployment
        data.getDeploymentConfig().setHotDeploymentEnabled(true);
        data.getDeploymentConfig().setUpdateClassesAndResources(true);
        data.getDeploymentConfig().setPreserveSessions(true);

        // Update
        data.getUpdateConfig().setOnUpdate("redeploy");
        data.getUpdateConfig().setOnFrameDeactivation("update_resources");
        data.getUpdateConfig().setShowUpdateDialog(false);
        data.getUpdateConfig().setShowFrameDeactivationDialog(false);

        // UI
        data.getUiConfig().setActivateToolWindow(false);

        // Debug
        data.getDebugConfig().setPort(5006);
        data.getDebugConfig().setTransport("Socket");
        data.getDebugConfig().setUseModuleClasspath(true);

        // Remote
        data.getRemoteConfig().setManagerUrl("http://remote:8080/manager");
        data.getRemoteConfig().setUsername("deployer");
        data.getRemoteConfig().setPassword("secret123");
        data.getRemoteConfig().setUseCredentials(true);

        // TomcatInfo
        TomcatInfo info = new TomcatInfo("Tomcat 9", "9.0.56", "/opt/tomcat9");
        data.setTomcatInfo(info);

        // Coverage
        data.getCoverageConfig().setIncludePatterns(List.of("com.example.*"));
        data.getCoverageConfig().setExcludePatterns(List.of("com.example.test.*"));

        return data;
    }
}
