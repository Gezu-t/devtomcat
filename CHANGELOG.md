# DevTomcat Changelog

## [Unreleased]

## [1.0.2]

### Fixed
- **Debug architecture**: Single JDWP agent ownership — `GenericDebuggerRunner` injects the agent, `TomcatDebugger` only resolves the port. Eliminates duplicate-agent port mismatch
- **Debug port race**: Resolved debug port flows through `TomcatCommandLineState.resolvedDebugPort` as single source of truth — no config mutation, no ordering dependency
- **Debug port conflicts**: JDWP port (5005) included in pre-launch conflict detection and auto-resolution alongside Tomcat ports
- **Remote debug**: `TomcatDebugger` now reads host/port from `RunnerSettings` (Startup/Connection tab) in remote mode instead of hardcoding localhost
- **Custom script + debug**: `TOMCAT_DEBUG_PORT` and `TOMCAT_JDWP_OPTS` env vars propagated to custom startup scripts in debug mode
- **Duplicate JDWP warning**: Pre-launch detection warns if user manually adds `-agentlib:jdwp` in VM options during local debug (scoped to local mode only, precise matching)
- **Duplicate deployment guard**: Validator warns on duplicate module variants or multiple entries pointing to the same artifact output path
- **Remote deploy cancel vs failure**: `DeployResult` tri-state enum (SUCCESS/FAILED/CANCELLED) — user cancellation no longer recorded as deployment failure in status/history
- **Deprecated API cleanup**: Replaced `ProcessAdapter`, `Comparing.equal`, `new URL(String)`, `ConfigurationException.getMessage()`, `RawCommandLineEditor`, `FileSaverDescriptor`, `createSingleLocalFileDescriptor`, `ReadAction.compute`, `addBrowseFolderListener`
- **Internal API removal**: Replaced `SlowOperations.knownIssue` (internal), removed all `SlowOperations` workarounds

### Added
- **Debug Tomcat action**: Services tool window context menu action to restart a running Tomcat in Debug mode
- **Remote WAR upload progress**: `ProgressIndicator` with per-chunk progress, file size display, and cancel support
- **Searchable settings**: `TomcatServersConfigurable` implements `SearchableConfigurable` — Tomcat settings discoverable via IDE search
- **DashboardCompat**: Compatibility layer for `RunDashboardRunConfigurationNode.getConfigurationSettings()` API migration — reflection-first with cached fallback

### Changed
- Application updates (Ctrl+F10) handled entirely by `TomcatApplicationUpdater` — no dependency on Tomcat's `reloadable` or `autoDeploy` background scanning
- `JREConfigurationDialog` split: 665 → 230 lines — extracted `JdkEditorDialog` (unified Add/Edit) and `AutoDetectJdkDialog`
- `StartupConnectionTab`: extracted shared `createScriptSection()` eliminating startup/shutdown duplication
- `ExpandableTextField` replaces `RawCommandLineEditor` in VM options section
- Plugin verifier: 0 errors, 0 internal API across IntelliJ 2024.1 through 2025.3

## [1.0.1]

### Fixed
- **Redeploy loop**: Disabled `autoDeploy` in generated and copied `server.xml` — Tomcat's HostConfig background scanner no longer triggers undeploy/redeploy cycles when IDE/build tasks modify exploded artifact output
- **Catalina log spam**: `reloadable="false"` in all context XML — Tomcat's background class scanner no longer floods logs with `NoSuchFileException` for missing Maven cache JARs
- **Browser port mismatch**: Browser URL port now rewritten to match auto-resolved runtime HTTP port
- **Browser 404 on launch**: Browser opens only after target context is deployed, not on generic server startup message
- **EDT assertion**: Replaced IntelliJ `FileChooser`/`MacPathChooserDialog` with native `JFileChooser` — eliminates `SlowOperations` assertion and plugin blame on macOS
- **Spring Boot properties**: Removed `-Dserver.port`, `-Dspring.profiles.active`, `-Dserver.ssl.enabled` from `CATALINA_OPTS` — these are Spring Boot properties with no effect on standalone Tomcat
- **Duplicate classpath**: Module `target/classes` directory no longer added as PostResource when the same module is already packaged as a JAR in `WEB-INF/lib` — eliminates duplicate class loading (e.g. Liquibase `duplicateFileMode` errors)

## [1.0.0]

### Added
- Initial release of DevTomcat
- Free Tomcat integration for IntelliJ IDEA Community Edition
- Tomcat server configuration and management
- Web application deployment support
- Run/Debug configurations for Tomcat
- Support for Tomcat 6, 7, 8, 9, and 10
- Custom context path configuration
- SSL port configuration
- Catalina base directory configuration
- Console log redirection
- Graceful server shutdown
