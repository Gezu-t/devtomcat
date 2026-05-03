# DevTomcat Changelog

## [1.0.9]

Deep deployment audit. Security hardening, port resolver fixes, and many smaller correctness fixes around launch, Debug on 2025.1, and remote deploy.

### Changed
- Extracted `PortResolver` from `TomcatJavaParametersBuilder` for testability.

### Security
- AJP without `address` reproduced CVE-2020-1938 (Ghostcat). IDE-injected AJP now binds `127.0.0.1`.
- JMX exposed without host binding. JMX and RMI registry now bind `127.0.0.1` by default; override via VM options.

### Fixed (Tomcat compatibility)
- Tomcat 7.x, 8.0.x, 8.5.<51, and 9.0.<31 flooded the run console with `ClassFormatException: Invalid byte tag in constant pool: 19` SEVERE messages when WEB-INF/lib contained Java 9+ modular JARs (jackson, jaxb-api, byte-buddy, snakeyaml, etc.). Cause: their bundled BCEL parser does not recognise `CONSTANT_Module` (tag 19) and chokes on `module-info.class`. New `BcelModuleInfoCompat` shim detects affected versions and adds modular JARs to the context's `<JarScanFilter pluggabilitySkip="...">` so annotation scanning is skipped only for those JARs and only on those Tomcats. Modern Tomcats (10.x, 11.x, 8.5.51+, 9.0.31+) are unaffected. Runtime classloading is unchanged in all cases.

### Fixed (data loss)
- `copyConfDirectory` wiped the user's `conf/` when `CATALINA_BASE` equalled `CATALINA_HOME`. Now refused with a clear error.
- Stale-deployment cleanup wiped pinned `CATALINA_BASE`. Cleanup gated to the IDE-managed system directory.
- Parallel-run cleanup followed a symlink at the run-base root. Now `NOFOLLOW_LINKS` + explicit refusal.

### Fixed
- Services tree URL ignored HTTPS; uses HTTPS port when enabled.
- Tomcat 7 `No rules found matching 'Context/Resources/PreResources'` warning. Generator skips PreResources on Tomcat 7.
- Restart in Debug on 2025.1 threw `IllegalStateException: Running sync tasks on pure EDT`. `destroyProcess` now off-EDT.
- Services-panel Stop in Debug had the same EDT trap.
- Remote-deploy URL injection: `?path=` paths now URL-encoded.
- Liquibase cleanup missed under `tr_TR` (capital I to ı). Pinned `Locale.ROOT`.
- Multi-module artifact-to-module matching broken under `tr_TR`. Both sides pinned to `Locale.ROOT`.
- Run-config editor leaked its message-bus listener; now scoped to the editor disposable.
- Remote-deploy progress used JVM-default decimal separator. `formatSize` pinned to `Locale.ROOT`. Same fix in run-history and trend dialogs.
- Port resolver displaced peer services with their own preferred ports. Search is now peer-aware.
- `TomcatConfigurationData.setContextPath` skipped slash canonicalization. Now uses `ContextPathUtils.normalizeContextPath`.
- Smart-error console dropped the detected message. Format now includes `Category: Message. Suggestion`.
- Manager URL with trailing slash silently fell back to localhost. Setter strips trailing slashes.
- `CredentialResolver`, `RemoteCredentialStore.retrievePassword`, `TomcatNotifier`, `TomcatRunConfiguration.getState`, and `syncBeforeLaunchWithDeployments` swallowed `ProcessCanceledException`. PCE now rethrows.
- `hasManualJdwpAgent` masked real `-agentlib:jdwp=` by a leading `-agentlib:jdwp_other`. Scans past rejected matches.
- Context.xml writes were non-atomic. New `atomicWriteString` helper.
- Renaming a running config leaked its ports. `TomcatPortRegistry` now migrates entries on rename.
- Carry-over relaunch lost JDWP exhaustion warnings. Now logs the same warnings as the first-time debug path.
- Silent JRE fallback when configured JRE was unregistered. Now surfaced in the run console.
- One throwing listener silenced its peers in `TomcatLifecycleListener.composite` and `TomcatOutputPipeline.processLine`. Both isolate per-listener with WARN.
- Remote-deploy task outlived its process and posted stale dashboard updates. Short-circuits on terminate.
- Bundled-app mirror produced malformed `context.xml` for directories containing `--`. Dir name is now sanitised in the comment.

### Diagnostics
- `TomcatServerManagerState.resolveOrAutoRegister` logs the specific failure reason (missing path, missing `catalina.jar`, IOException).
- `runIde` sets `idea.is.internal=true` for stacktrace coverage on Disposable warnings.

### Tests
- New `PortResolverTest` (7 cases) and slimmed `TomcatJavaParametersBuilderTest`.
- HTTPS coverage in `TomcatDeploymentNodeTest`.
- `TomcatVersionGate` group in `LocalDeploymentStrategyTest` pins Tomcat-7 PreResources omission.
- Regression tests: `peerAllocationDoesNotAbortSearch`, `refusesSamePathCase`, `rejectedLeadingMatchDoesNotMaskRealAgent`, `trailingSlashAccepted`, `missingLeadingSlashCanonicalized`, plus `formatForConsole` extension.

## [1.0.8]

Resilience release. No new features — every change either reduces the surface area of future bugs or locks in a past fix with an end-to-end test.

### Changed
- **Minimum IntelliJ version raised to 2025.1.** `pluginSinceBuild=251.29188.11`, compile against 2025.1.7. The 2024.1 permutation added verifier cost without unlocking anything (the 2026.1 dashboard builder API is not available on 2024.1/2025.1 anyway). Halves the verifier matrix and paves the road for the dashboard migration once the floor bumps again.
- **IntelliJ Platform Gradle Plugin bump deferred** — 2.14.0 requires Gradle 9+, which is a larger migration than this release should carry. Pinned at 2.11.0 for 1.0.8; the Gradle 9 + plugin bump is a dedicated 1.0.9 task.
- **State machine in `TomcatDeploymentStatusService`** refactored to derive the server state from a single authoritative function (`recomputeServerState`) instead of scattered ad-hoc assignments. Every event handler now updates the per-artifact state, then recomputes. `restoreRunningStateIfIdle` and the duplicated `serverState = …` writes are gone. Invariants are documented in the source.
- **`DashboardCompat` simplified** — dead speculative-reflection fallbacks removed (the guessed replacement method names never landed on the interface; the real 2026.1 replacement is a parameter-based `updatePresentation` overload, not a new accessor). Kept as a one-file boundary with a concrete migration checklist for the eventual 2026.1 floor bump.

### Fixed
- **`onDeploymentSummaryFailed` no longer speculatively promotes DEPLOYING/RELOADING artifacts to FAILED.** Real Tomcat emits the summary-failure line while other contexts are still starting; those contexts frequently succeed afterward. Per-artifact failure signals + the `StartupAnalyzer` fallback continue to decide which artifacts actually failed, precisely. Surfaced by the new integration harness running a realistic fixture.
- **Self-healing for persisted-but-unregistered Tomcat references.** `TomcatServerManagerState.resolveOrAutoRegister` auto-registers a server when its persisted path points to a valid Tomcat install on disk. Fresh IDE installs, VCS-imported projects, and wiped sandbox profiles no longer block Run with a "Persisted Tomcat server is not registered" warning for an install that's physically present. Wired into the UI load path, the launcher, and the pre-launch validator. Broken references (empty path, missing directory, non-Tomcat directory) still block Run with a precise error.
- **Artifact state stickiness** — per-artifact `FAILED` is now sticky across a late `onArtifactDeployed` for the same artifact within a launch; stickiness also applies across cancellation and reload events.

### Added
- **Integration test harness** (`TomcatPipelineHarness`) — replays Tomcat output fixtures through the full pipeline → lifecycle → status-service chain. Caught the summary-failure over-promotion bug on first run.

### Tests
- 4 fixture-driven integration tests, 3 state-machine invariants, 7 `resolveOrAutoRegister` units.

## [1.0.7]

### Fixed
- **Debug mode breakpoints** — JDWP agent is now injected directly onto the JVM's VM parameters; the old path via `GenericDebuggerRunner`'s patcher was silently bypassed, so Tomcat launched without the agent and every breakpoint was skipped
- **Services panel mixed-success-as-success** — a new `ServerDeploymentSummaryFailureAnalyzer` catches Tomcat's summary messages ("One or more Contexts did not start successfully" and peers) and keeps the server state FAILED even when the per-artifact pattern can't name which artifact broke. Signaling is gated on this authoritative signal rather than the generic error counter, so non-fatal SEVERE noise on healthy startups no longer causes false positives
- **Cancellation vs. failure** — user-cancelled remote deployments reset to PENDING via a new `onArtifactCancelled` hook instead of being sticky-FAILED
- **Remote deploy failure visibility** — invalid artifacts are filtered up front; manager-connection failures fire `onArtifactFailed` for every configured artifact so the Services tree reflects the failure instead of leaving artifacts stuck in DEPLOYING

### Changed
- **2026.1 deprecation cleanup** — all 14 `ReadAction.compute(ThrowableComputable)` call sites migrated to a centralised `TomcatReadActions.compute` helper; eliminates the scheduled-for-removal warnings reported by Plugin Verifier against IU-261 while staying source-compatible with 2024.1+

## [1.0.6]

### Added
- **Scoped Services actions** — "Run History" and "Startup Time Trends" in the Services panel now open for the selected configuration instead of the whole project; global views remain available in the Tools menu

### Changed
- **Startup time display** — Services panel shows human-readable durations (`12.3s`, `1m 23s`) instead of raw milliseconds
- **Startup time tracker** — moved from application-level to project-level service so identically named configurations in different projects maintain separate history
- **Run History** — renamed from "Deployment History" to better reflect the session-based model

### Fixed
- **Services panel stale display** — editing a configuration now refreshes the Services tree immediately so updated ports, context paths, and URLs are reflected without restart
- **Shutdown warning noise** — error/warning counters freeze when shutdown begins; Tomcat classloader cleanup warnings (JDBC driver, leaked threads) no longer inflate the Services badge
- **Rename tracking** — renaming a run configuration now migrates all stored data (live status, run history, startup trends) from the old name to the new name using identity-based tracking
- **Stale trend entries** — deleting a configuration now also clears its startup time history
- **Thread-safe counters** — error/warning counts changed from `volatile int` with `++` to `AtomicInteger` with `incrementAndGet()` to prevent undercounting under concurrent output
- **Defensive state copy** — `StartupTimeTracker.getState()` returns a deep copy so the trend dialog doesn't read mutable internals
- **Artifact failure in history** — run history now records artifact deployment failures even when exit code is 0, so partial sessions no longer show as OK
- **Error counts on FAILED nodes** — error/warning counts now remain visible on failed server nodes, not just running ones
- **Reload state alignment** — hot reload pushes the parent server node to "Deploying" so parent and child states are aligned
- **Post-mortem artifact states** — non-zero shutdown preserves FAILED artifact states in Services instead of clearing everything
- **Navigation gate** — double-clicking a deployment node in Services now only opens the browser when the artifact is confirmed DEPLOYED, preventing 404s on failed or in-progress artifacts

### Tests
- Added `TomcatConfigurationCleanupListenerTest` — identity key mechanics, rename detection
- Added `TomcatRunDashboardCustomizerTest` — formatDuration, formatIssueSummary
- Added `DeploymentHistoryDialogTest` — scoped vs global, titles, clear
- Added `StartupTimeTrendDialogTest` — scoped vs global, empty state
- Extended: `TomcatDeploymentStatusServiceTest`, `TomcatDeploymentHistoryTest`, `TomcatDeploymentNodeTest`, `TomcatOutputPipelineTest`, `StartupTimeTrackerTest`, `TomcatLifecycleListenerTest`

## [1.0.5]

### Added
- **Configurable Build Artifacts task** — "Build DevTomcat Artifacts" in Before Launch is now configurable; clicking it shows all deployed artifacts with checkboxes so the user can select which artifacts to validate before launch

### Changed
- **JRE Configuration dialog** — split into focused sub-dialogs (`JdkEditorDialog`, `AutoDetectJdkDialog`) for add/edit and auto-detection flows; main dialog reduced to ~255 lines
- **Startup/Connection tab** — env var table, actions, computed-key tracking, and Add/Edit dialog extracted to a new `EnvVarPanel` class; `StartupConnectionTab` reduced from 1003 → 542 lines
- **TomcatJavaParametersBuilder** — inline 44-line port resolution block extracted to `resolvePortsIfNeeded()`, which returns a `PortConfig` or throws; `build()` reduced to a clean 10-step sequence; `setupVmOptions()` signature simplified from 8 params to 5
- **VM Options field** — replaced with IntelliJ's `ExpandableTextField` for proper handling of long option strings
- **Services panel focus** — `maybeActivateConsole()` now only activates the Run/Debug tool window when it is already visible, preventing it from stealing focus from the Services panel while scrolling
- **Facade accessors** — added `isRemoteMode()`, `getServerMode()`, `getDeployedArtifacts()` on `TomcatRunConfiguration`; eliminates Law of Demeter violations across 15+ files

### Fixed
- **Context path empty-string edge case** — `TomcatConfigurationData.setContextPath("")` now normalizes to `"/"`. Previously `StringUtil.notNullize` only handled null; an empty string from XML deserialization would pass through as `""`, causing downstream context name resolution to silently fall back to ROOT.
- **ReadAction scope** — `syncBeforeLaunchWithDeployments()`, `validateArtifactReferences()`, `ArtifactSelectionHandler`, and `TomcatConfigurationEditor` now wrap all `ArtifactManager`/`ModuleManager` model access inside `ReadAction.compute()`. Previously `getArtifacts()` was called outside the read action, risking read-access violations on background threads.
- **Config import data loss** — importing a configuration no longer silently wipes startup/shutdown scripts, `passParentEnvs`, and debug host/port. The import now preserves existing runner settings and merges only the exported env var fields.
- **Config import mode mismatch** — importing a Remote config into a Local editor now calls `reconcileTabsForMode()` to update tab structure correctly.
- **EnvVarPanel state bugs** — `passParentEnvs` is now preserved on load; deleted computed keys can be re-added manually; `Populate Defaults` no longer resets the `Pass environment variables` checkbox.
- **ProcessStopSupport cleanup guard** — `removeRunContent()` failure no longer blocks relaunch; wrapped in try/catch so the callback always runs.
- **Debug restart notification** — `DebugTomcatAction` now shows a balloon notification when debug-mode restart fails, matching the pattern in `TomcatRunnerDelegate` and `TomcatApplicationUpdater`.
- **Atomic move fallback** — `TomcatConfigPreparer.atomicWriteString()` now falls back to non-atomic `REPLACE_EXISTING` when the filesystem doesn't support `ATOMIC_MOVE`, matching `TomcatProjectUtils.atomicCopy()`.

### Refactored — Duplicate Code Elimination
- **`ContextPathUtils.resolveContextNameSafe()`** — single source for try/catch fallback to ROOT on invalid context paths; replaced 3 private wrappers in TomcatProcessHandler, TomcatApplicationUpdater, and TomcatManagerDeployer
- **`TomcatNotifier`** — single source for balloon notifications; replaced 3 inline `NotificationGroupManager` blocks in TomcatApplicationUpdater, TomcatRunnerDelegate, and TomcatCommandLineState
- **`CompilerSupport.compileAndThen()`** — single source for the compile-and-then pattern; replaced 4 `CompilerManager.make()` blocks in TomcatApplicationUpdater
- **`ProcessStopSupport`** — single source for the stop-clean-relaunch pattern; `findDescriptor()` replaces 2 inline descriptor lookup loops, `stopCleanAndThen()` replaces 3 identical ProcessListener/processTerminated/invokeLater/removeRunContent/destroyProcess blocks in TomcatRunnerDelegate, DebugTomcatAction, and TomcatApplicationUpdater
- **`ServiceActionUtils.tryInvokeMethod()`** — single source for reflection method invocation; simplified 2 nested try/catch/loop blocks in extractProcessHandler and extractViaReflection
- **`TomcatProjectUtils.safeDelete()`** — single source for safe file deletion; replaced 3 inline deleteIfExists blocks in atomicCopy and LocalDeploymentStrategy
- **`ConfigurationSection.addLabelAndField()`** — single source for the label+field GridBagLayout row pattern; replaced identical GBC boilerplate in ApplicationServerSection, JreConfigurationSection, and UpdateActionsSection
- **`TomcatSettingsSection.addPortRow()`/`addCheckBoxColumn()`** — extracted from 6 identical port-field row blocks
- **Inline FQN cleanup** — replaced 28 inline fully-qualified names with proper imports across 18 files

### Tests
- Added `TomcatDebuggerTest` — covers runner ID stability
- Added `TomcatApplicationUpdaterTest` — covers `mapActionToDisplay()` for all four update actions
- Added `TomcatProcessHandlerTest` — covers `extractContextNameFromBrowserUrl` and `rewritePortIfNeeded` helpers
- Added `LocalDeploymentStrategyTest` — covers `stripJarVersion()` and `extractModuleName()` static helpers
- Added `EnvVarPanelStateTest` — covers `initializeState` (3 paths), `passParentEnvs` round-trip, delete-then-readd lifecycle, and `ensureComputedEnvVars` interaction

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
