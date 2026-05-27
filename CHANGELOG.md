# DevTomcat Changelog

## [Unreleased]

## [1.2.0]

### Fixed
- Owning-module resolution rebuilt on typed `ArtifactPointer`/`ModulePointer` dispatch.
- Orphan-artifact check reads `ArtifactPointer.getArtifact()` directly.
- Console stops flagging incidental `ERROR`/`SEVERE`/`FATAL`/`WARN` substrings as level keywords.
- Migrated remaining `SimpleListCellRenderer.create(...)` factory calls to the subclass form.

### Changed
- `pluginSinceBuild` lowered to `242` (IntelliJ 2024.2+).
- Launch / update / remote-deploy pipelines iterate typed `Deployment` end to end.
- `DeploymentConfig` gains typed mutators (`setDeployments`, `addDeployment`, `removeDeployment`, `getDeploymentByName`).
- Typed sync entry points added (`DeployedClassesSync.syncDeployments`, `WebResourcesSync.syncDeployments`, `warnAboutWarDeploymentsIfPresent`).
- `TomcatApplicationUpdater` orchestrators hoist `getDeployments()` once per pass.
- `LocalDeploymentStrategy` deep helpers take typed `Deployment`.
- `TomcatPreflightValidator.checkDuplicateDeployments` / `checkDuplicateJars` switch to typed dispatch.
- `ArtifactStructureValidator`, `TomcatBuildArtifactsTaskProvider`, `SelectArtifactsDialog` switch to typed.
- `ArtifactMatchingUtils.findMatching(Deployment)` replaces the fuzzy matcher with one pointer lookup.
- `ProjectArtifactDetector` produces typed `Deployment` directly.
- `DeploymentTableManager` exposes typed UI-boundary accessors.
- `ArtifactReferenceRefresher` rewritten on typed-pointer dispatch (543 → ~140 lines).
- Persistence boundary stabilized; `DeploymentArtifact` / `DeploymentAdapter` / `DeploymentConfig.getArtifacts()` un-deprecated.
- Dropped: legacy `getDeployedArtifacts()` accessors and replaced-by-typed sync/warn overloads.

## [1.1.1]

### Changed
- Diagnostic analyzer skips the regex sweep for log lines without any failure keyword.
- Migrated 2 `SimpleListCellRenderer.create(String, Function)` call sites to the non-deprecated 3-arg form.
- Pipeline analyzers early-exit on keyword check before any regex.
- WebResourcesSync uses a single `readAttributes` instead of `exists` + `readAttributes`.
- ContextFailureRootCauseAnalyzer narrowed sync scope; logger callback runs outside the lock.
- Shared MavenReflection cache resolves `MavenProjectsManager` once at class init.
- TomcatPreflightValidator caches CompilerConfiguration reflection at class init.
- Two unconditional `LOG.debug` concatenations now guarded with `isDebugEnabled`.

### Fixed
- Toolbar Rerun icon stays visible with multiple Tomcat configs.
- Services panel shows FAILED for context-startup failures via LifecycleException / component-failed log forms.
- Failed restart no longer accumulates stale "started Tomcat" entries in the toolbar.

## [1.1.0]

### Changed
- Remote-mode launches use pure Manager-API deploy, no local JVM fork.
- Repeat balloons moved to console-only.
- Port resolution seeds from user-intended port; preferred-vs-resolved tracking.
- Tomcat shutdown waits for OS port release.
- Orphan reclaim verifies port is free post-kill.

### Fixed
- Class-sync refuses ECJ broken-class stubs.
- Class-sync runs on every launch, not just Update.
- Class-sync follows transitive module dependencies.
- Class-sync per-artifact diagnostics in run console.
- Class-sync size tie-breaker for equal-mtime files.
- Web-resource sync mirrors `src/main/webapp/` into exploded artifact root.

### Added
- `PortStrategy` per-config policy: `AUTO_BUMP` / `RECLAIM_THEN_FAIL` / `STRICT`.
- Tools → Set Up DevTomcat from Project: one config, N exploded deployments, port-mode picker.
- Four library-agnostic diagnostic patterns: ECJ stubs, localhost backend unreachable, JDK module-access, SFTP.
- Compiler-type preflight warning when IDE compiler is Eclipse.
- Port-drift warning in the run-config editor.
- WAR-artifact warning naming artifacts that won't hot-sync.

## [1.0.14]

### Added
- Clickable Java stack traces in the run-config console.
- Console folding for Tomcat startup banner and container-internal stack frames.
- Actionable balloons on common failures (port-in-use, missing-class, OOM, JRE mismatch).
- Balloon when custom `server.xml` cannot be parsed; launch falls back to minimal config.

### Fixed
- Restart / Update / Redeploy now pick up Java edits on Maven exploded deployments.
- Plugin-verifier "use of internal API" warning on `PluginManagerCore.getPlugin`.
- IDE main-window flicker when balloons fire from background threads (TomcatNotifier dispatches via EDT).
- Plugin now installable on IntelliJ IDEA 2026.2 EAP (`until-build` raised to `262.*`).
- Duplicate-context-path detection catches normalized variants; invalid paths rejected at Apply.
- Stale-deployment cleanup failures surface as a balloon naming the locked files.
- Warns when the deployed artifact is older than recent source edits.
- Pre-launch validation catches missing `WEB-INF/` and WAR-vs-exploded type mismatches.
- Startup-failure root cause surfaced as a balloon.
- Midnight log-rotation notice explains why Log tabs are tailing yesterday's file.
- Symlinked docBase deploys on Tomcat 8+ via `<Resources allowLinking="true">`.
- Warns when no artifacts will be deployed.
- Cleans leftover `webapps/<context>/` directories from previous WAR extracts.
- Pre-launch WAR integrity check catches corrupted / truncated / 0-byte WARs.

## [1.0.13]

### Added
- Right-click on a deployed artifact in the Services tree for Open in Browser and Copy URL.
- Services tree row shows a debugger icon when the configuration is running under the Debug executor.
- Clicking Rerun on a running Tomcat now opens the Update dialog.

### Fixed
- Main toolbar Run button now swaps to the Rerun icon while Tomcat is running.
- HTTPS-port writeback also rewrites the stored browser URL.
- Cross-scheme writeback safety: HTTP-port changes no longer touch HTTPS URLs and vice versa.

## [1.0.12]

### Fixed
- After-launch URL kept the old port after auto-resolution bumped Tomcat (writeback rewrites loopback URLs).
- Services tree restored on IntelliJ 2025.3+ via a new ServiceViewContributor.
- Browser-launch isModified silently dropped pending edits on exception.

### Changed
- JRE combo live-refreshes when SDKs change in Project Structure.
- JRE version parser uses `Runtime.Version.parse` so build tags display correctly.
- Run-config validation surfaces all errors at once.
- Preserve-sessions checkbox now has a tooltip.
- Internal polish in the run-config editor sections.

## [1.0.11]

### Fixed
- Debug launch on Java 8 failed with `TRANSPORT_INIT(510)`; JDWP address syntax branches on JDK version.
- ECJ swap installed a JAR the runtime JVM could not load; new JVM-aware picker maps JVM to ECJ tier.
- "Restore Previous ECJ" balloon for users in the bad-swap state from a 1.0.10 swap.
- Container-provided JAR filter no longer drops JSTL and other app libraries that share a name prefix.

### Changed
- Stale-swap detection reads installed ECJ JAR's class-file major directly.
- Multi-class probe in JAR introspection tries four well-known ECJ entry points.
- Registry-key override for swap target version: `devtomcat.ecj.target.version`.
- ECJ version parser normalises Eclipse Platform release filename form to Maven coordinate.
- EOL warning wording simplified for default-named Tomcat installs.

## [1.0.10]

### Self-audit fixes (within 1.0.10)
- `EcjJarSwapper.restoreBackup` refuses paths missing the `.devtomcat-bak` suffix.
- ECJ JAR download migrated to IntelliJ's `HttpRequests`.
- ECJ swap balloon deduped per IDE session by JAR path + version.
- JDK-mismatch quick-fix balloon filtered to messages containing "Java".

### Added
- Tomcat EOL warning balloon for EOL branches (7.x, 8.0.x, 8.5.x, 10.0.x).
- JDK / Tomcat mismatch quick-fix with Open Run Configuration / Open Project Structure actions.
- One-click ECJ JAR swap for Tomcat installs whose bundled compiler is too old.

### Fixed
- Stock AJP connector at port 8009 leaked through when AJP was disabled in the run config.
- Remote-mode config showed a generic deploy icon instead of the Tomcat brand.
- Services tree URL hardcoded `localhost` for remote-mode configurations.
- Container-provided JARs silently dropped on Tomcat 7 / 8.0.x; routed through `catalina.properties`.
- Remote-deploy upload kept running after the local Tomcat was stopped.

### Changed
- Em-dashes scrubbed from user-facing strings.

## [1.0.9]

### Changed
- Extracted `PortResolver` from `TomcatJavaParametersBuilder` for testability.

### Security
- AJP without `address` reproduced CVE-2020-1938 (Ghostcat); IDE-injected AJP now binds `127.0.0.1`.
- JMX and RMI registry now bind `127.0.0.1` by default; override via VM options.

### Fixed (Tomcat compatibility)
- ECJ-too-old warning for legacy Tomcats: new `EcjVersionCompat` shim surfaces a single pre-launch warning.
- Modular-JAR `ClassFormatException` flood on Tomcat 7.x / 8.0.x / 8.5.<51 / 9.0.<31: `BcelModuleInfoCompat` appends modular JARs to `jarsToSkip`.

### Fixed (data loss)
- `copyConfDirectory` wiping user's `conf/` when `CATALINA_BASE` equalled `CATALINA_HOME` — now refused.
- Stale-deployment cleanup wiping pinned `CATALINA_BASE` — gated to IDE-managed system directory.
- Parallel-run cleanup followed a symlink at the run-base root.

### Fixed
- Services tree URL ignored HTTPS.
- Tomcat 7 `No rules found matching 'Context/Resources/PreResources'` warning suppressed.
- Restart in Debug on 2025.1 threw `Running sync tasks on pure EDT`.
- Services-panel Stop in Debug had the same EDT trap.
- Remote-deploy URL injection: `?path=` paths now URL-encoded.
- Liquibase cleanup missed under `tr_TR`; pinned `Locale.ROOT`.
- Multi-module artifact-to-module matching broken under `tr_TR`.
- Run-config editor leaked its message-bus listener.
- Remote-deploy progress used JVM-default decimal separator.
- Port resolver displaced peer services with their own preferred ports.
- `TomcatConfigurationData.setContextPath` skipped slash canonicalization.
- Smart-error console dropped the detected message.
- Manager URL with trailing slash silently fell back to localhost.
- `ProcessCanceledException` swallowed at five call sites; now rethrows.
- `hasManualJdwpAgent` masked real `-agentlib:jdwp=` by a leading `-agentlib:jdwp_other`.
- Context.xml writes were non-atomic.
- Renaming a running config leaked its ports.
- Carry-over relaunch lost JDWP exhaustion warnings.
- Silent JRE fallback when configured JRE was unregistered.
- One throwing listener silenced its peers in `TomcatLifecycleListener.composite` and `TomcatOutputPipeline.processLine`.
- Remote-deploy task outlived its process and posted stale dashboard updates.
- Bundled-app mirror produced malformed `context.xml` for directories containing `--`.

### Diagnostics
- `TomcatServerManagerState.resolveOrAutoRegister` logs the specific failure reason.
- `runIde` sets `idea.is.internal=true` for stacktrace coverage.

### Tests
- New `PortResolverTest` (7 cases) and slimmed `TomcatJavaParametersBuilderTest`.
- HTTPS coverage in `TomcatDeploymentNodeTest`.
- `TomcatVersionGate` group in `LocalDeploymentStrategyTest`.
- Regression tests: peer-allocation, same-path-case refusal, leading-match disambiguation, trailing-slash, missing-leading-slash, `formatForConsole`.

## [1.0.8]

### Changed
- Minimum IntelliJ version raised to 2025.1 (`pluginSinceBuild=251.29188.11`).
- IntelliJ Platform Gradle Plugin pinned at 2.11.0; Gradle 9 + plugin bump deferred to 1.0.9.
- State machine in `TomcatDeploymentStatusService` derives server state from a single `recomputeServerState`.
- `DashboardCompat` simplified; dead speculative-reflection fallbacks removed.

### Fixed
- `onDeploymentSummaryFailed` no longer speculatively promotes DEPLOYING/RELOADING artifacts to FAILED.
- Self-healing for persisted-but-unregistered Tomcat references via `resolveOrAutoRegister`.
- Artifact state stickiness: per-artifact FAILED sticky across late `onArtifactDeployed`, cancellation, and reload events.

### Added
- Integration test harness (`TomcatPipelineHarness`) replays Tomcat output fixtures through the full pipeline.

### Tests
- 4 fixture-driven integration tests, 3 state-machine invariants, 7 `resolveOrAutoRegister` units.

## [1.0.7]

### Fixed
- Debug mode breakpoints: JDWP agent injected directly onto JVM VM parameters.
- Services panel mixed-success-as-success: `ServerDeploymentSummaryFailureAnalyzer` catches summary messages.
- Cancellation vs. failure: user-cancelled remote deployments reset to PENDING via `onArtifactCancelled`.
- Remote deploy failure visibility: invalid artifacts filtered up front; manager-connection failures fire `onArtifactFailed`.

### Changed
- 2026.1 deprecation cleanup: 14 `ReadAction.compute(ThrowableComputable)` call sites migrated to `TomcatReadActions.compute`.

## [1.0.6]

### Added
- Scoped Services actions: "Run History" and "Startup Time Trends" open for the selected configuration.

### Changed
- Startup time display shows human-readable durations.
- Startup time tracker moved from application-level to project-level service.
- Run History renamed from "Deployment History".

### Fixed
- Services panel refreshes immediately on configuration edit.
- Shutdown warning noise: counters freeze when shutdown begins.
- Rename tracking: stored data migrates from old to new configuration name.
- Stale trend entries cleared when a configuration is deleted.
- Thread-safe counters via `AtomicInteger`.
- Defensive state copy in `StartupTimeTracker.getState()`.
- Artifact failure in history recorded even when exit code is 0.
- Error counts on FAILED nodes remain visible.
- Reload state alignment between parent and child nodes.
- Post-mortem artifact states preserved across non-zero shutdown.
- Navigation gate: double-click only opens browser when artifact is DEPLOYED.

### Tests
- Added `TomcatConfigurationCleanupListenerTest`, `TomcatRunDashboardCustomizerTest`, `DeploymentHistoryDialogTest`, `StartupTimeTrendDialogTest`.
- Extended six existing test classes.

## [1.0.5]

### Added
- Configurable Build Artifacts task with per-artifact checkboxes.

### Changed
- JRE Configuration dialog split into focused sub-dialogs.
- Startup/Connection tab env-var table extracted to `EnvVarPanel`.
- `TomcatJavaParametersBuilder.build()` reduced to a 10-step sequence.
- VM Options field switched to `ExpandableTextField`.
- Services panel focus: `maybeActivateConsole()` no longer steals focus.
- Facade accessors added on `TomcatRunConfiguration` (`isRemoteMode`, `getServerMode`, `getDeployedArtifacts`).

### Fixed
- Context path empty-string normalized to `"/"`.
- ReadAction scope: model access in `syncBeforeLaunchWithDeployments` / `validateArtifactReferences` / `ArtifactSelectionHandler` / `TomcatConfigurationEditor` wrapped.
- Config import no longer wipes startup/shutdown scripts, `passParentEnvs`, debug host/port.
- Config import Remote→Local now reconciles tab structure.
- `EnvVarPanel` state bugs (passParentEnvs preservation, deleted-key re-add, Populate Defaults).
- `ProcessStopSupport.removeRunContent()` failure no longer blocks relaunch.
- Debug restart shows a balloon when restart fails.
- Atomic move fallback when filesystem doesn't support `ATOMIC_MOVE`.

### Refactored — Duplicate Code Elimination
- `ContextPathUtils.resolveContextNameSafe()` replaces 3 private wrappers.
- `TomcatNotifier` replaces 3 inline `NotificationGroupManager` blocks.
- `CompilerSupport.compileAndThen()` replaces 4 `CompilerManager.make()` blocks.
- `ProcessStopSupport` replaces 2 descriptor lookups and 3 stop-clean-relaunch blocks.
- `ServiceActionUtils.tryInvokeMethod()` simplifies 2 nested reflection blocks.
- `TomcatProjectUtils.safeDelete()` replaces 3 inline `deleteIfExists` blocks.
- `ConfigurationSection.addLabelAndField()` replaces identical GBC boilerplate in three sections.
- `TomcatSettingsSection.addPortRow()` / `addCheckBoxColumn()` extracted from 6 identical blocks.
- 28 inline fully-qualified names replaced with proper imports across 18 files.

### Tests
- Added `TomcatDebuggerTest`, `TomcatApplicationUpdaterTest`, `TomcatProcessHandlerTest`, `LocalDeploymentStrategyTest`, `EnvVarPanelStateTest`.

## [1.0.3]

### Added
- Multi-module Maven/Gradle deployment understands the IntelliJ module dependency graph.
- Duplicate deployment guard warns when two artifacts share context path or deployment path.
- Restart/relaunch failure notification when restart fails after the old process was stopped.

### Fixed
- Context path normalization: `setContextPath("")` stores `"/"`.
- Threading violations in `ArtifactReferenceRefresher.refresh()`, `LocalDeploymentStrategy.buildExtraResourcesXml()`, `TomcatRunConfiguration.syncBeforeLaunchWithDeployments()`.
- API compatibility (IntelliJ 2025.x): replaced internal `ExecutionManager.getRunningDescriptors()`, deprecated `UIUtil.getContextHelpForeground()`, `ProgramRunnerUtil.executeConfiguration()`.
- Service annotations: `@Service(Level.PROJECT)` on `TomcatDeploymentStatusService`, `@Service(Level.APP)` on `TomcatPortRegistry`.

### Changed
- Stale artifact filtering: artifacts from renamed/deleted modules no longer shown.

## [1.0.2]

### Added
- Update Application on re-run: Run/Debug shows Update dialog instead of starting a duplicate process.
- Services toolbar actions: Update, Redeploy, Restart as one-click buttons.
- Debug Tomcat action restarts a running Tomcat in Debug mode from Services panel.
- Atomic port registry prevents port collisions across simultaneous launches.
- Debug port field per configuration in Server Settings.

### Fixed
- Redeploy preserves multi-module classpath via full context XML.
- Thread safety in deployment notifications, artifact counters, console debounce, lifecycle history, status service.
- Security: URL scheme validation, path traversal protection, config-import file size limit, manager URL validation.
- Resource leaks: editor disposal listener cleanup, ProcessListener self-removal, port release on build failure.
- Debug architecture: single JDWP agent ownership, resolved debug port as single source of truth.
- Redeploy loop: autoDeploy disabled in server.xml; reloadable=false in context XML.
- Browser launch opens only after target context is deployed.

### Changed
- Runner deduplication via `TomcatRunnerDelegate`.
- Symlink protection in CATALINA_BASE file operations.
- Credential resolution tracks completion to avoid redundant PasswordSafe lookups.

## [1.0.0]

### Added
- Initial release of DevTomcat.
- Free Tomcat integration for IntelliJ IDEA Community and Ultimate.
- Run, Debug, and Coverage configurations for Tomcat 7-11.
- Multi-artifact deployment with independent context paths.
- Smart Diagnostics — 16+ Tomcat error patterns with actionable suggestions.
- Auto-port conflict resolution and CATALINA_BASE isolation.
- Live deployment status, history, and startup trends in Services panel.
- Update Running Application (Ctrl+F10) with frame deactivation support.
- Remote deployment via Tomcat Manager API.
- Configuration export/import for team sharing.
