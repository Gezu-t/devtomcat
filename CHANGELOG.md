# DevTomcat Changelog

## [Unreleased]

## [1.0.7]

### Fixed
- **Debug architecture**: Single JDWP agent ownership — `GenericDebuggerRunner` injects the agent, `TomcatDebugger` only resolves the port. Eliminates duplicate-agent port mismatch
- **Debug port race**: Resolved debug port flows through `TomcatCommandLineState.resolvedDebugPort` as single source of truth — no config mutation, no ordering dependency
- **Debug port conflicts**: JDWP port (5005) included in pre-launch conflict detection and auto-resolution alongside Tomcat ports
- **Remote debug**: `TomcatDebugger` now reads host/port from `RunnerSettings` (Startup/Connection tab) in remote mode instead of hardcoding localhost
- **Custom script + debug**: `TOMCAT_DEBUG_PORT` and `TOMCAT_JDWP_OPTS` env vars propagated to custom startup scripts in debug mode
- **Duplicate JDWP warning**: Pre-launch detection warns if user manually adds `-agentlib:jdwp` in VM options during local debug (scoped to local mode only, precise matching)
- **Catalina log spam**: `reloadable="false"` in all context XML — Tomcat's background class scanner no longer floods logs with `NoSuchFileException` for missing Maven cache JARs
- **Remote deploy cancel vs failure**: `DeployResult` tri-state enum (SUCCESS/FAILED/CANCELLED) — user cancellation no longer recorded as deployment failure in status/history
- **Spring Boot properties**: Removed `-Dserver.port`, `-Dspring.profiles.active`, `-Dserver.ssl.enabled` from `CATALINA_OPTS` — these are Spring Boot properties with no effect on standalone Tomcat
- **Deprecated API cleanup**: Replaced `ProcessAdapter`, `Comparing.equal`, `new URL(String)`, `ConfigurationException.getMessage()`, `RawCommandLineEditor`, `FileSaverDescriptor`, `createSingleLocalFileDescriptor`
- **Internal API removal**: Replaced `SlowOperations.knownIssue` (internal) with `SlowOperations.allowSlowOperations` (public)

### Added
- **Debug Tomcat action**: Services tool window context menu action to restart a running Tomcat in Debug mode
- **Remote WAR upload progress**: `ProgressIndicator` with per-chunk progress, file size display, and cancel support
- **Searchable settings**: `TomcatServersConfigurable` implements `SearchableConfigurable` — Tomcat settings discoverable via IDE search
- **DashboardCompat**: Compatibility layer for `RunDashboardRunConfigurationNode.getConfigurationSettings()` API migration — reflection-first with cached fallback

### Changed
- Application updates (Ctrl+F10) handled entirely by `TomcatApplicationUpdater` — no dependency on Tomcat's `reloadable` background scanner
- `JREConfigurationDialog` split: 665 → 230 lines — extracted `JdkEditorDialog` (unified Add/Edit) and `AutoDetectJdkDialog`
- `StartupConnectionTab`: extracted shared `createScriptSection()` eliminating startup/shutdown duplication
- `ExpandableTextField` replaces `RawCommandLineEditor` in VM options section
- Plugin verifier: 0 errors, 0 internal API, 0 deprecated API across IntelliJ 2024.1 through 2025.2

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