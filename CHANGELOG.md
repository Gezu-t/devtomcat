# DevTomcat Changelog

## [Unreleased]

### Added
- Optional "Update on save": while a server runs, saving a project file debounce-triggers the configured Update action (off by default; auto-triggers skip configs with no module-backed deployment to avoid a wasteful whole-project compile).
- One-click "Reclaim Deployment" action surfaces in a balloon for WAR artifacts with a sibling exploded directory and for already-exploded external deployments living under a project module, enabling hot reload (Ctrl+F10) without rebuilding.
- Pre-launch classpath-duplicate scan for exploded deployments: warns when the same logical resource is packaged in both `WEB-INF/classes/` and a `WEB-INF/lib/` JAR (or in two JARs), filtering universally-benign cases like `META-INF/MANIFEST.MF`, `META-INF/services/*`, `META-INF/maven/*`, multi-release JAR overrides, and license files.

### Changed
- Exploded deployments overlay the module's runtime classpath onto Tomcat's webapp classloader: class output directories mount at `/WEB-INF/classes` via `<PreResources>` (zero-copy hot reload of freshly compiled bytes), and library JARs not already in `WEB-INF/lib/` mount via `<PostResources>`. Code changes become visible on the next request without rebuilding the WAR.
- Exploded deployments also mount the module's webapp source directories at the web-app root via `<PreResources>`, so edited JSPs and static resources are served from source on the next request without re-copying into the artifact.
- Class sync runs the broken-class byte scan only on files it is about to copy; unchanged files cost a stat, so a no-op sync no longer reads the entire deployed classpath off disk on each launch/update.
- Under Debug, "Update classes and resources" redefines changed classes in the live JVM and preserves sessions and in-memory state, restarting the context automatically only when a change is structural and cannot be redefined.
- Update actions compile only the deployment modules plus their upstream dependency closure instead of the whole project, falling back to a full build when no module-backed deployment resolves.

### Fixed
- Empty Deployment tab now blocks launch with a clear error, instead of warning post-launch when Tomcat has already started with nothing to serve.
- Flicker when removing a deployment from the Deployment tab.
- Class sync and web-resources sync now remove orphan files in the deployed artifact when the source no longer claims them, so deleted/renamed source files stop being loadable by Tomcat. Class sync respects the WAR module's source-root union; webapp sync skips `WEB-INF/classes/` and `WEB-INF/lib/` (owned by other pipelines).

## [1.2.0]

### Fixed
- Console no longer flags incidental error/severe/fatal/warn substrings as level keywords.
- Reliability improvements.

### Changed
- Internal improvements.

## [1.1.1]

### Fixed
- Toolbar Rerun icon stays visible with multiple Tomcat configs.
- Services panel shows FAILED for context-startup failures.
- Failed restart no longer accumulates stale "started Tomcat" entries in the toolbar.

### Changed
- Internal improvements.

## [1.1.0]

### Added
- Per-config port strategy: auto-bump / reclaim-then-fail / strict.
- Tools → Set Up DevTomcat from Project.
- New diagnostic patterns: ECJ stubs, localhost backend unreachable, JDK module-access, SFTP.
- Compiler-type preflight warning when IDE compiler is Eclipse.
- Port-drift warning in the run-config editor.
- WAR-artifact warning naming artifacts that won't hot-sync.

### Fixed
- Class-sync refuses ECJ broken-class stubs.
- Class-sync runs on every launch, not just Update.
- Class-sync follows transitive module dependencies.
- Class-sync per-artifact diagnostics in the run console.
- Class-sync size tie-breaker for equal-mtime files.
- Web-resource sync mirrors webapp sources into the exploded artifact root.

### Changed
- Remote-mode launches use pure Manager-API deploy (no local JVM fork).
- Repeat balloons moved to console-only.
- Port resolution seeds from user-intended port; preferred-vs-resolved tracking.
- Tomcat shutdown waits for OS port release.
- Orphan reclaim verifies port is free post-kill.

## [1.0.14]

### Added
- Clickable Java stack traces in the run-config console.
- Console folding for Tomcat startup banner and container-internal stack frames.
- Actionable balloons on common failures (port-in-use, missing-class, OOM, JRE mismatch).
- Balloon when custom server.xml cannot be parsed; launch falls back to minimal config.

### Fixed
- Restart / Update / Redeploy now pick up Java edits on Maven exploded deployments.
- IDE main-window flicker when balloons fire from background threads.
- Plugin installable on IntelliJ IDEA 2026.2 EAP.
- Duplicate-context-path detection catches normalized variants; invalid paths rejected at Apply.
- Stale-deployment cleanup failures surface as a balloon naming the locked files.
- Warns when the deployed artifact is older than recent source edits.
- Pre-launch validation catches missing WEB-INF and WAR-vs-exploded type mismatches.
- Startup-failure root cause surfaced as a balloon.
- Midnight log-rotation notice.
- Symlinked docBase deploys on Tomcat 8+.
- Warns when no artifacts will be deployed.
- Cleans leftover extracted webapp directories from previous WAR deploys.
- Pre-launch WAR integrity check.

### Changed
- Internal improvements.

## [1.0.13]

### Added
- Right-click on a deployed artifact in the Services tree for Open in Browser and Copy URL.
- Services tree row shows a debugger icon when the configuration is running under Debug.
- Clicking Rerun on a running Tomcat now opens the Update dialog.

### Fixed
- Main toolbar Run button swaps to the Rerun icon while Tomcat is running.
- HTTPS-port writeback also rewrites the stored browser URL.
- Cross-scheme writeback safety between HTTP and HTTPS URLs.

## [1.0.12]

### Fixed
- After-launch URL kept the old port after auto-resolution.
- Services tree restored on IntelliJ 2025.3+.
- Browser-launch isModified silently dropped pending edits on exception.

### Changed
- JRE combo live-refreshes when SDKs change in Project Structure.
- JRE version parser handles build tags correctly.
- Run-config validation surfaces all errors at once.
- Preserve-sessions checkbox tooltip.
- Internal improvements.

## [1.0.11]

### Fixed
- Debug launch on Java 8 failed with TRANSPORT_INIT(510).
- ECJ swap installed a JAR the runtime JVM could not load.
- "Restore Previous ECJ" balloon for users in the bad-swap state.
- Container-provided JAR filter no longer drops JSTL and other app libraries.

### Changed
- Registry-key override for swap target version.
- EOL warning wording simplified for default-named Tomcat installs.
- Internal improvements.

## [1.0.10]

### Added
- Tomcat EOL warning balloon for EOL branches.
- JDK / Tomcat mismatch quick-fix with Open Run Configuration / Open Project Structure actions.
- One-click ECJ JAR swap for Tomcat installs whose bundled compiler is too old.

### Fixed
- Stock AJP connector at port 8009 leaked through when AJP was disabled in the run config.
- Remote-mode config showed a generic deploy icon instead of the Tomcat brand.
- Services tree URL hardcoded localhost for remote-mode configurations.
- Container-provided JARs silently dropped on Tomcat 7 / 8.0.x.
- Remote-deploy upload kept running after the local Tomcat was stopped.

### Changed
- Em-dashes scrubbed from user-facing strings.
- Internal improvements.

## [1.0.9]

### Security
- AJP without address reproduced CVE-2020-1938 (Ghostcat); IDE-injected AJP now binds 127.0.0.1.
- JMX and RMI registry now bind 127.0.0.1 by default; override via VM options.

### Fixed (Tomcat compatibility)
- ECJ-too-old warning for legacy Tomcats.
- Modular-JAR ClassFormatException flood on Tomcat 7.x / 8.0.x / 8.5.<51 / 9.0.<31.

### Fixed (data loss)
- Refused wiping user's conf/ when CATALINA_BASE equalled CATALINA_HOME.
- Stale-deployment cleanup no longer wipes pinned CATALINA_BASE.
- Parallel-run cleanup no longer follows a symlink at the run-base root.

### Fixed
- Services tree URL ignored HTTPS.
- Tomcat 7 PreResources warning suppressed.
- Restart in Debug on 2025.1 threw "Running sync tasks on pure EDT".
- Services-panel Stop in Debug had the same EDT trap.
- Remote-deploy URL injection: paths now URL-encoded.
- Liquibase cleanup missed under tr_TR locale.
- Multi-module artifact-to-module matching broken under tr_TR.
- Run-config editor leaked its message-bus listener.
- Remote-deploy progress used JVM-default decimal separator.
- Port resolver displaced peer services with their own preferred ports.
- Context-path setter skipped slash canonicalization.
- Smart-error console dropped the detected message.
- Manager URL with trailing slash silently fell back to localhost.
- ProcessCanceledException swallowed at five call sites.
- JDWP agent detection masked by leading similar-named flags.
- Context.xml writes were non-atomic.
- Renaming a running config leaked its ports.
- Carry-over relaunch lost JDWP exhaustion warnings.
- Silent JRE fallback when configured JRE was unregistered.
- One throwing listener silenced its peers.
- Remote-deploy task outlived its process and posted stale dashboard updates.
- Bundled-app mirror produced malformed context.xml for directories containing "--".

### Changed
- Internal improvements.

## [1.0.8]

### Changed
- Minimum IntelliJ version raised to 2025.1.
- Internal improvements.

### Fixed
- Summary-failure no longer speculatively promotes in-progress artifacts to FAILED.
- Self-healing for persisted-but-unregistered Tomcat references.
- Artifact FAILED state sticky across late events.

## [1.0.7]

### Fixed
- Debug mode breakpoints: JDWP agent now injected directly onto JVM parameters.
- Services panel mixed-success-as-success caught by a new summary-failure analyzer.
- User-cancelled remote deployments reset to PENDING.
- Remote deploy failure visibility: invalid artifacts filtered up front.

### Changed
- Internal improvements.

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
- Rename tracking migrates stored data from old to new configuration name.
- Stale trend entries cleared when a configuration is deleted.
- Thread-safe counters via AtomicInteger.
- Defensive state copy in startup-time tracker.
- Artifact failure in history recorded even when exit code is 0.
- Error counts on FAILED nodes remain visible.
- Reload state alignment between parent and child nodes.
- Post-mortem artifact states preserved across non-zero shutdown.
- Navigation gate: double-click only opens browser when artifact is DEPLOYED.

## [1.0.5]

### Added
- Configurable Build Artifacts task with per-artifact checkboxes.

### Changed
- VM Options field uses an expandable text field.
- Services panel focus no longer steals from other tool windows.
- Internal improvements.

### Fixed
- Context path empty-string normalized to "/".
- ReadAction scope widened in run-config editor and validators.
- Config import no longer wipes startup/shutdown scripts and env settings.
- Config import Remote→Local reconciles tab structure.
- Env-var panel state bugs.
- Process-stop cleanup failure no longer blocks relaunch.
- Debug restart shows a balloon when restart fails.
- Atomic move fallback when filesystem doesn't support it.

## [1.0.3]

### Added
- Multi-module Maven/Gradle deployment understands the IntelliJ module dependency graph.
- Duplicate deployment guard warns when two artifacts share context path or deployment path.
- Restart/relaunch failure notification.

### Fixed
- Context path normalization for empty strings.
- Threading violations on background coroutine threads.
- IntelliJ 2025.x API compatibility.
- Service annotations added.

### Changed
- Stale artifact filtering for renamed/deleted modules.

## [1.0.2]

### Added
- Update Application on re-run.
- Services toolbar actions: Update, Redeploy, Restart.
- Debug Tomcat action from the Services panel.
- Atomic port registry prevents port collisions.
- Per-configuration JDWP debug port.

### Fixed
- Redeploy preserves multi-module classpath.
- Thread safety across deployment notifications and counters.
- Security: URL scheme validation, path traversal protection, config-import size limit, manager URL validation.
- Resource leaks on editor disposal and listener teardown.
- Debug architecture: single JDWP agent ownership.
- Redeploy loop eliminated.
- Browser launch opens only after the target context is deployed.

### Changed
- Internal improvements.

## [1.0.0]

### Added
- Initial release of DevTomcat.
- Free Tomcat integration for IntelliJ IDEA.
- Run, Debug, and Coverage configurations for Tomcat 7-11.
- Multi-artifact deployment with independent context paths.
- Smart Diagnostics — 16+ Tomcat error patterns with actionable suggestions.
- Auto-port conflict resolution and CATALINA_BASE isolation.
- Live deployment status, history, and startup trends in Services panel.
- Update Running Application (Ctrl+F10) with frame deactivation support.
- Remote deployment via Tomcat Manager API.
- Configuration export/import for team sharing.
