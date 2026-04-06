# DevTomcat Changelog

## [1.0.2]

### Added
- **Update Application on re-run** — Run/Debug while Tomcat is running shows the Update dialog (Update Resources, Redeploy, Restart) instead of starting a duplicate process
- **Services toolbar actions** — Update, Redeploy, and Restart available as one-click toolbar buttons in the Services panel
- **Debug Tomcat action** — restart a running Tomcat in Debug mode from the Services panel
- **Atomic port registry** — prevents port collisions when multiple Tomcat instances launch simultaneously
- **Debug port field** — per-configuration JDWP debug port in Server Settings

### Fixed
- **Redeploy preserves multi-module classpath** — generates full context XML with PreResources/PostResources matching initial deployment
- **Thread safety** — fixed race conditions in deployment notifications, artifact count tracking, console debounce, lifecycle history, and status service state transitions
- **Security** — URL scheme validation on remote deploy, path traversal protection, file size limit on config import, manager URL validation
- **Resource leaks** — listener cleanup on editor disposal, ProcessListener self-removal, port release on build failure
- **Debug architecture** — single JDWP agent ownership, resolved debug port as single source of truth, remote debug reads from Startup/Connection tab
- **Redeploy loop** — disabled autoDeploy in server.xml; reloadable=false in context XML
- **Browser launch** — opens only after target context is deployed, port matches auto-resolved HTTP port

### Changed
- Runner deduplication — extracted shared re-run interception into TomcatRunnerDelegate (composition)
- Symlink protection in CATALINA_BASE file operations
- Credential resolution tracks completion to avoid redundant PasswordSafe lookups

## [1.0.0]

### Added
- Initial release of DevTomcat
- Free Tomcat integration for IntelliJ IDEA Community and Ultimate
- Run, Debug, and Coverage configurations for Tomcat 7-11
- Multi-artifact deployment with independent context paths
- Smart Diagnostics — 16+ Tomcat error patterns with actionable suggestions
- Auto-port conflict resolution and CATALINA_BASE isolation
- Live deployment status, history, and startup trends in Services panel
- Update Running Application (Ctrl+F10) with frame deactivation support
- Remote deployment via Tomcat Manager API
- Configuration export/import for team sharing
