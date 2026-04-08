# DevTomcat Changelog

## [1.0.4]

### Changed
- **JRE Configuration dialog** — split into focused sub-dialogs (`JdkEditorDialog`, `AutoDetectJdkDialog`) for add/edit and auto-detection flows; main dialog reduced to ~255 lines
- **Startup/Connection tab** — env var table, actions, computed-key tracking, and Add/Edit dialog extracted to a new `EnvVarPanel` class; `StartupConnectionTab` reduced from 1003 → 542 lines
- **TomcatJavaParametersBuilder** — inline 44-line port resolution block extracted to `resolvePortsIfNeeded()`, which returns a `PortConfig` or throws; `build()` reduced to a clean 10-step sequence; `setupVmOptions()` signature simplified from 8 params to 5
- **VM Options field** — replaced with IntelliJ's `ExpandableTextField` for proper handling of long option strings
- **Services panel focus** — `maybeActivateConsole()` now only activates the Run/Debug tool window when it is already visible, preventing it from stealing focus from the Services panel while scrolling

### Fixed
- **Context path empty-string edge case** — `TomcatConfigurationData.setContextPath("")` now normalizes to `"/"`. Previously `StringUtil.notNullize` only handled null; an empty string from XML deserialization would pass through as `""`, causing downstream context name resolution to silently fall back to ROOT.

### Refactored — Duplicate Code Elimination
- **`ContextPathUtils.resolveContextNameSafe()`** — single source for try/catch fallback to ROOT on invalid context paths; replaced 3 private wrappers in TomcatProcessHandler, TomcatApplicationUpdater, and TomcatManagerDeployer
- **`TomcatNotifier`** — single source for balloon notifications; replaced 3 inline `NotificationGroupManager` blocks in TomcatApplicationUpdater, TomcatRunnerDelegate, and TomcatCommandLineState
- **`CompilerSupport.compileAndThen()`** — single source for the compile-and-then pattern; replaced 4 `CompilerManager.make()` blocks in TomcatApplicationUpdater
- **`ProcessStopSupport`** — single source for the stop-clean-relaunch pattern; `findDescriptor()` replaces 2 inline descriptor lookup loops, `stopCleanAndThen()` replaces 3 identical ProcessListener/processTerminated/invokeLater/removeRunContent/destroyProcess blocks in TomcatRunnerDelegate, DebugTomcatAction, and TomcatApplicationUpdater
- **`ServiceActionUtils.tryInvokeMethod()`** — single source for reflection method invocation; simplified 2 nested try/catch/loop blocks in extractProcessHandler and extractViaReflection
- **`TomcatProjectUtils.safeDelete()`** — single source for safe file deletion; replaced 3 inline deleteIfExists blocks in atomicCopy and LocalDeploymentStrategy
- **`ConfigurationSection.addLabelAndField()`** — single source for the label+field GridBagLayout row pattern; replaced identical GBC boilerplate in ApplicationServerSection, JreConfigurationSection, and UpdateActionsSection
- **`TomcatSettingsSection.addPortRow()`/`addCheckBoxColumn()`** — extracted from 6 identical port-field row blocks

### Tests
- Added `TomcatDebuggerTest` — covers runner ID stability
- Added `TomcatApplicationUpdaterTest` — covers `mapActionToDisplay()` for all four update actions
- Added `TomcatProcessHandlerTest` — covers `extractContextNameFromBrowserUrl` and `rewritePortIfNeeded` helpers
- Added `LocalDeploymentStrategyTest` — covers `stripJarVersion()` and `extractModuleName()` static helpers

## [1.0.3]

### Added
- **Multi-module Maven/Gradle deployment** — Plugin understands the IntelliJ module dependency graph. When a shared module (e.g. `common`) is already packaged as a JAR in `WEB-INF/lib`, the plugin no longer adds a conflicting `<PreResources>` overlay. Eliminates Liquibase, CDI, and similar duplicate-classpath errors in multi-module projects.
- **Duplicate deployment guard** — Pre-launch validator warns when two artifacts share the same context path or the same physical deployment path, preventing silent 404s and double-startup overhead.
- **Restart/relaunch failure notification** — When a restart or cross-executor relaunch fails after the old process has already been stopped, a prominent balloon notification is shown so the user knows Tomcat is no longer running and must be restarted manually.

### Fixed
- **Context path normalization** — `setContextPath("")` now correctly stores `"/"` instead of an empty string. `StringUtil.notNullize("", "/")` only substitutes `null`; empty strings were silently stored as `""`, causing incorrect duplicate detection and browser URL generation.
- **Threading violations (multiple call sites)** — Fixed `Read access is allowed from inside read-action only` errors thrown on background coroutine threads during project load and post-build redeploy:
  - `ArtifactReferenceRefresher.refresh()` — called from `readExternal()` during project initialization
  - `LocalDeploymentStrategy.buildExtraResourcesXml()` — called from compiler-completion callbacks; all model access (module graph, OrderEnumerator, ArtifactManager) now collected atomically under a single `ReadAction.compute()` via `ArtifactModelSnapshot`
  - `TomcatRunConfiguration.syncBeforeLaunchWithDeployments()` — `ArtifactManager.getInstance()` wrapped in `ReadAction`
- **API compatibility (IntelliJ 2025.x)** — Replaced internal `ExecutionManager.getRunningDescriptors()` with `RunContentManager.getAllDescriptors()`, deprecated `UIUtil.getContextHelpForeground()` with `JBUI.CurrentTheme.ContextHelp.FOREGROUND`, and `ProgramRunnerUtil.executeConfiguration()` with `ExecutionEnvironmentBuilder`
- **Service annotations** — Added `@Service(Level.PROJECT)` to `TomcatDeploymentStatusService` and `@Service(Level.APP)` to `TomcatPortRegistry`

### Changed
- Stale artifact filtering — artifacts from renamed or deleted modules are no longer shown in the artifact selector or auto-detected for deployment

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
