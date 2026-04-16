# DevTomcat User Manual

Free, full-featured Apache Tomcat integration for IntelliJ IDEA — Community and Ultimate editions.

---

## Table of Contents

1. [Requirements](#requirements)
2. [Installation](#installation)
3. [Registering a Tomcat Server](#registering-a-tomcat-server)
4. [Creating a Run Configuration](#creating-a-run-configuration)
5. [Server Tab](#server-tab)
   - [Application Server](#application-server)
   - [Open Browser](#open-browser)
   - [VM Options](#vm-options)
   - [Update Actions](#update-actions)
   - [JRE Selection](#jre-selection)
   - [Tomcat Server Settings](#tomcat-server-settings)
6. [Deployment Tab](#deployment-tab)
   - [Adding Artifacts](#adding-artifacts)
   - [Application Context](#application-context)
   - [Multi-Module Projects](#multi-module-projects)
7. [Logs Tab](#logs-tab)
8. [Startup/Connection Tab](#startupconnection-tab)
   - [Mode Selector](#mode-selector)
   - [Startup and Shutdown Scripts](#startup-and-shutdown-scripts)
   - [Environment Variables](#environment-variables)
9. [Code Coverage Tab](#code-coverage-tab)
10. [Before Launch](#before-launch)
11. [Running and Debugging](#running-and-debugging)
    - [Run](#run)
    - [Debug](#debug)
    - [Update Running Application](#update-running-application)
    - [Services Toolbar Actions](#services-toolbar-actions)
12. [Remote Deployment](#remote-deployment)
13. [Advanced Features](#advanced-features)
    - [CATALINA_BASE Isolation](#catalina_base-isolation)
    - [Configuration Overlay](#configuration-overlay)
    - [Auto-Port Resolution](#auto-port-resolution)
    - [Smart Diagnostics](#smart-diagnostics)
    - [Deployment Dashboard](#deployment-dashboard)
    - [Configuration Export/Import](#configuration-exportimport)
14. [Troubleshooting](#troubleshooting)

---

## Requirements

| Requirement | Minimum Version |
|-------------|----------------|
| IntelliJ IDEA | 2024.1+ (Community or Ultimate) |
| Apache Tomcat | 7.x or later |
| Java (JDK) | 17+ |

---

## Installation

1. Open IntelliJ IDEA
2. Go to **File → Settings → Plugins** (or **IntelliJ IDEA → Preferences → Plugins** on macOS)
3. Select the **Marketplace** tab
4. Search for **DevTomcat**
5. Click **Install** and restart the IDE

![DevTomcat plugin installation from JetBrains Marketplace](images/install-plugin.png)
*Figure 1 — Installing DevTomcat from the Marketplace*

---

## Registering a Tomcat Server

Before creating a run configuration, register at least one Tomcat installation.

1. Go to **File → Settings → Build, Execution, Deployment → DevTomcat**
2. Click the **+** button
3. Browse to your Tomcat installation directory (e.g., `/opt/apache-tomcat-10.1.20`)
4. DevTomcat detects the version automatically
5. Click **OK**

![Tomcat server registration dialog](images/register-server.png)
*Figure 2 — Registering a Tomcat server installation*

You can register multiple Tomcat versions and switch between them per run configuration.

---

## Creating a Run Configuration

1. Go to **Run → Edit Configurations...**
2. Click **+** and select **Dev Tomcat**
3. Choose **Local** (run on your machine) or **Remote** (deploy to a remote server)
4. Give the configuration a name

![Creating a new DevTomcat run configuration](images/new-run-config.png)
*Figure 3 — Adding a new DevTomcat run configuration*

The configuration editor opens with five tabs: **Server**, **Deployment**, **Logs**, **Startup/Connection**, and **Code Coverage**.

---

## Server Tab

The Server tab contains all core server settings.

![Server tab overview](images/server-tab.png)
*Figure 4 — Server tab with all settings sections*

### Application Server

Select which registered Tomcat installation to use. Click **Configure...** to add or manage Tomcat installations without leaving the dialog.

![Application server selection](images/app-server-section.png)
*Figure 5 — Application server dropdown*

### Open Browser

Controls whether a browser opens automatically after Tomcat starts.

| Field | Description |
|-------|-------------|
| **After launch** | Enable/disable automatic browser opening |
| **Browser** | Select which browser to use (System Default, Chrome, Firefox, etc.) |
| **with JavaScript debugger** | Attach IntelliJ's JavaScript debugger to the browser session |
| **URL** | The URL to open — auto-generated from deployment context, fully editable |

![Open browser section](images/browser-section.png)
*Figure 6 — Open browser settings*

**URL behavior:**
- When you first add a deployment, the URL auto-generates as `http://localhost:<port>/<context>/`
- If you change the HTTP port, the URL port updates automatically
- If you change the deployment's context path, the URL context updates automatically
- **Once you manually edit the URL**, it becomes yours — DevTomcat will never overwrite it
- Your custom URL and browser selection persist across IDE restarts

### VM Options

JVM arguments passed to the Tomcat process. Click the expand button for a full-screen editor.

Common examples:
```
-Xmx1024m -Xms256m
-Dspring.profiles.active=dev
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005
```

![VM options field](images/vm-options.png)
*Figure 7 — VM options with expand button*

### Update Actions

Controls what happens when you trigger **Update Running Application** (Ctrl+F10 / Cmd+F10).

| Field | Description |
|-------|-------------|
| **On 'Update' action** | What to do: Update resources, Update classes and resources, Redeploy, or Restart server |
| **Show dialog** | Prompt before performing the update action |
| **On frame deactivation** | What to do when you switch away from IntelliJ (e.g., to a browser) |

![Update actions section](images/update-actions.png)
*Figure 8 — Update action and frame deactivation settings*

### JRE Selection

Choose which JDK runs Tomcat. Defaults to the project SDK.

![JRE selection](images/jre-section.png)
*Figure 9 — JRE dropdown showing project SDK and installed JDKs*

### Tomcat Server Settings

| Field | Description |
|-------|-------------|
| **HTTP port** | Main connector port (default: 8080) |
| **HTTPS port** | SSL connector port (leave empty to disable) |
| **JMX port** | JMX monitoring port (default: 1099) |
| **AJP port** | AJP connector port (leave empty to disable) |
| **Shutdown port** | Port for shutdown commands |
| **Debug port** | JDWP debug port used when launching in Debug mode (default: 5005). Set a unique value per configuration when running multiple Tomcat instances simultaneously to avoid port conflicts |
| **CATALINA_BASE** | Override the runtime instance directory (leave empty for auto-generated) |
| **Deploy applications configured in Tomcat instance** | Enable hot deployment |
| **Preserve sessions across restarts and redeploys** | Keep HTTP sessions alive during redeployment |

![Tomcat server settings](images/tomcat-settings.png)
*Figure 10 — Port configuration and server options*

---

## Deployment Tab

Configure which artifacts are deployed to Tomcat at startup.

![Deployment tab overview](images/deployment-tab.png)
*Figure 11 — Deployment tab with artifact list and context path editor*

### Adding Artifacts

Click the **+** button to add deployments:

- **Artifact...** — Select from IntelliJ-configured artifacts (Project Structure → Artifacts) or auto-detected web modules and WAR files
- **External Source...** — Browse for a WAR file or exploded directory on disk

![Add artifact popup](images/add-artifact-popup.png)
*Figure 12 — Add deployment options*

Artifacts are displayed grouped by type:
1. **Exploded** artifacts (best for development — supports hot reload)
2. **WAR** artifacts (packaged deployments)
3. **Other** artifacts (plain, jar)

![Artifact selection dialog](images/artifact-selection-dialog.png)
*Figure 13 — Artifact selection showing type-grouped artifacts*

### Application Context

Each deployed artifact has its own **Application context** — the URL path prefix under which it's accessible.

- Select an artifact in the list
- Edit the **Application context** field below
- Context paths are auto-generated from the artifact name but fully editable
- Duplicate context paths are automatically resolved with a `-2`, `-3` suffix

![Application context editing](images/context-path.png)
*Figure 14 — Editing the application context for a deployment*

### Multi-Module Projects

DevTomcat understands the IntelliJ module dependency graph and handles multi-module Maven/Gradle projects correctly.

**Deploying multiple web applications from one project:**

1. Add each web module as a separate deployment
2. Each gets its own context path (e.g., `/web`, `/api`)
3. Set the browser URL on the Server tab to your preferred landing page (e.g., `http://localhost:8080/web/login`)
4. Use the toolbar arrows to reorder deployments — the first deployment is used for the browser URL auto-generation

| Deployment | Context | Description |
|------------|---------|-------------|
| web-frontend:war exploded | `/web` | Frontend application |
| api-backend:war exploded | `/api` | REST API |
| admin-panel:war exploded | `/admin` | Administration interface |

**Shared library modules (Maven/Gradle):**

When a web application depends on a shared module (e.g., `common`) that is packaged as a JAR dependency:

- **Maven-built artifacts** (`mvn package`): `common-1.0-SNAPSHOT.jar` appears in `WEB-INF/lib`. DevTomcat detects this and does **not** add a duplicate classpath overlay for `common/target/classes`. This prevents `ChangeLogParseException: Found 2 files` errors from Liquibase, duplicate bean errors from CDI, and similar duplicate-classpath problems.
- **IntelliJ-built artifacts** (exploded): `common`'s classes are merged into `WEB-INF/classes` by the artifact builder. DevTomcat adds `<PreResources>` for `common/target/classes` only when the JAR is not already present in `WEB-INF/lib`.
- **Hot reload**: Changes to the web module itself (`webapp/target/classes`) are reflected immediately via `<PreResources>`. Changes to shared modules packaged as JARs require a **Redeploy** (not just Build).

> **Note:** If you see `Liquibase: Found 2 files with the path 'classpath:...'` or similar duplicate-resource errors, it means a module's output is on the classpath twice. Trigger a **Redeploy** from the Services toolbar to regenerate `context.xml` with the correct classpath configuration.

---

## Logs Tab

Configure which Tomcat log files appear as console tabs in the Run tool window.

![Logs tab](images/logs-tab.png)
*Figure 15 — Log file configuration*

**Standard log files** (pre-configured):

| Log File | Default Active | Description |
|----------|---------------|-------------|
| catalina.out | Yes | Main Tomcat output (stdout/stderr) |
| catalina.log | Yes | Catalina engine log |
| localhost.log | Yes | Web application log |
| localhost_access.log | No | HTTP access log |
| manager.log | No | Tomcat Manager application log |
| host-manager.log | No | Host Manager log |

**Additional options:**
- **Save console output to file** — Persist console output to a file
- **Show console when a message is printed to stdout/stderr** — Auto-focus the console
- **Activate tool window** — Bring the Run window forward on output
- **Show this page** — Show the Logs tab when the tool window activates

You can add custom log files using the **+** button.

---

## Startup/Connection Tab

Configure startup behavior, scripts, and environment variables per executor mode (Run, Debug, Coverage).

![Startup/Connection tab](images/startup-connection-tab.png)
*Figure 16 — Startup/Connection tab with mode selector*

### Mode Selector

Switch between **Run**, **Debug**, and **Coverage** modes using the tabs at the top. Each mode maintains independent settings for scripts and environment variables.

### Startup and Shutdown Scripts

Define custom shell commands that execute before Tomcat starts or after it shuts down. Useful for:
- Database migrations before startup
- Cache cleanup after shutdown
- Custom health checks

> **Note:** In Remote mode, startup and shutdown script sections are hidden since the remote server manages its own lifecycle.

### Environment Variables

Set environment variables passed to the Tomcat process. The table shows:
- **User-defined variables** — Manually added key-value pairs
- **Computed variables** — Auto-generated from your configuration (e.g., `JAVA_OPTS` from VM options). These are read-only and marked with a different style.

| Variable | Source | Example |
|----------|--------|---------|
| `JAVA_HOME` | User-defined | `/usr/lib/jvm/java-17` |
| `SPRING_PROFILES_ACTIVE` | User-defined | `dev,local` |
| `JAVA_OPTS` | Computed from VM options | `-Xmx1024m -Xms256m` |

Check **Pass parent environment variables** to inherit system environment variables.

---

## Code Coverage Tab

Configure code coverage collection using JaCoCo.

![Code Coverage tab](images/code-coverage-tab.png)
*Figure 17 — Code Coverage include/exclude patterns*

| Field | Description |
|-------|-------------|
| **Include patterns** | Classes to instrument (e.g., `com.myapp.*`) |
| **Exclude patterns** | Classes to skip (e.g., `com.myapp.generated.*`) |

> **Note:** The Code Coverage tab is only available in **Local** mode. Remote servers cannot be instrumented from the plugin.

Run with coverage using **Run → Run with Coverage** or the coverage toolbar button.

---

## Before Launch

The **Before Launch** section at the bottom of the editor controls tasks that run before Tomcat starts.

![Before Launch section](images/before-launch.png)
*Figure 18 — Before Launch showing Build and Build Artifacts tasks*

DevTomcat automatically manages two tasks:

| Task | Description |
|------|-------------|
| **Build** | Compiles the project (always present) |
| **Build *N* artifact(s)** | Builds all deployment artifacts from the Deployment tab (auto-synced) |

When you add or remove deployments, the "Build artifacts" task updates automatically. You can add additional tasks (e.g., Run Gradle task, Run npm script) using the **+** button.

---

## Running and Debugging

### Run

Click the **Run** button (green triangle) or press **Shift+F10** to start Tomcat.

![Tomcat running in the console](images/run-console.png)
*Figure 19 — Tomcat running with console output and log tabs*

The console shows:
- Tomcat startup log with deployment status per artifact
- Clickable URLs — click to open in browser
- Clickable file paths — click to open in the editor
- Separate tabs for each configured log file

### Debug

Click the **Debug** button (bug icon) or press **Shift+F9** to start Tomcat in debug mode.

- Full breakpoint support in servlets, filters, listeners, and framework code
- Hot-swap classes with **Build → Recompile** (Ctrl+Shift+F9)
- Evaluate expressions and modify variables at breakpoints

**Running multiple debug configurations**: Each run configuration has its own **Debug port** field (Server tab → Port configuration). Set a different port for each configuration (e.g. 5005, 5006, 5007) to avoid JDWP conflicts. If you leave them all at the default 5005, DevTomcat's auto-port resolution will still find unique ports automatically, but setting them explicitly gives you predictable results.

### Update Running Application

While Tomcat is running, press **Ctrl+F10** (Cmd+F10 on macOS) to update the running application without a full restart.

If **Show dialog** is enabled (Server tab → Update Actions), a dialog appears letting you choose an action on the fly. Otherwise the action configured in **On 'Update' action** runs immediately.

| Action | What It Does | Best For |
|--------|-------------|----------|
| **Update resources** | Copies changed static resources (HTML, CSS, JS, images) to the artifact output directory | Frontend-only changes — fastest |
| **Update classes and resources** | Recompiles changed classes and copies static resources | Java + static changes without full redeploy |
| **Redeploy** | Regenerates `context.xml`, undeploys the old context, and deploys the new one | Structural changes, new dependencies |
| **Restart server** | Stops the Tomcat process and starts a fresh one | JVM option changes, server.xml changes, stubborn class loading issues |

**On frame deactivation** — the same options apply automatically when you switch from IntelliJ to another application (e.g., switch to a browser to test). Set it to a lightweight action so your browser always sees fresh content without manual intervention.

> **Tip:** For day-to-day development, set **On 'Update' action** to **Update classes and resources** and **On frame deactivation** to **Update resources**. This gives you hot class reloading on demand and instant static-resource refresh every time you switch to the browser.

### Services Toolbar Actions

Open the **Services** tool window (**View → Tool Windows → Services**) to access lifecycle actions directly without touching the keyboard shortcut or the run configuration.

![Services toolbar buttons](images/services-toolbar.png)
*Figure 20 — Services toolbar showing Update, Redeploy, and Restart buttons*

When a DevTomcat configuration node is selected, three one-click buttons appear in the toolbar:

| Button | Action | Equivalent |
|--------|--------|-----------|
| **Update Application** | Shows the Update dialog and executes the chosen strategy | Ctrl+F10 |
| **Redeploy** | Redeploys all artifacts (regenerates context XML for exploded, re-copies WARs) | Redeploy action |
| **Restart** | Stops and restarts the entire Tomcat process | Restart server action |

The context menu (right-click on the configuration node) also provides:

| Menu Item | Description |
|-----------|-------------|
| **Start Tomcat** | Starts the configuration if not running |
| **Stop Tomcat** | Gracefully shuts down the running server |
| **Update Application** | Opens the update strategy dialog |
| **Redeploy** | Redeploys all artifacts |
| **Restart Tomcat** | Full process restart |
| **Debug Tomcat** | Stops a running Run-mode server and relaunches it in Debug mode — attach the debugger without editing the run configuration |
| **Deployment History** | Opens the deployment history for this configuration |
| **Startup Time Trends** | Opens the startup performance graph |

> **Debug Tomcat from Services:** If Tomcat is already running in Run mode and you need to set a breakpoint, right-click the configuration node and choose **Debug Tomcat**. DevTomcat stops the running process and immediately relaunches it with JDWP enabled — no need to stop and manually switch executor.

---

## Remote Deployment

DevTomcat supports deploying to a remote Tomcat server via the Tomcat Manager API.

1. Create a **Remote** run configuration (Run → Edit Configurations → + → Dev Tomcat → Remote)
2. Configure the remote server connection on the Server tab:

| Field | Description |
|-------|-------------|
| **Host** | Remote server hostname or IP |
| **Port** | Remote Tomcat HTTP port |
| **Use credentials** | Enable username/password authentication |
| **Username** | Tomcat Manager username (configured in `tomcat-users.xml`) |
| **Password** | Tomcat Manager password |

3. Click **Test Connection** to verify connectivity
4. Add artifacts on the Deployment tab — WAR files will be uploaded to the remote server

![Remote configuration](images/remote-config.png)
*Figure 20 — Remote server connection settings with Test Connection*

> **Note:** The remote Tomcat server must have the Manager application deployed and the user must have the `manager-script` role in `tomcat-users.xml`.

---

## Advanced Features

### CATALINA_BASE Isolation

Each run configuration gets its own **CATALINA_BASE** directory — an isolated runtime instance with separate `conf/`, `logs/`, `webapps/`, `work/`, and `temp/` directories.

```
<project>/.idea/devtomcat/<config-name>/
├── conf/       ← Copied from Tomcat installation, server.xml mutated with your ports
├── logs/       ← catalina.out, localhost.log, access log
├── temp/       ← JVM temporary files
├── webapps/    ← Deployment context XML and WAR files
└── work/       ← Compiled JSPs (cleaned each launch)
```

**Benefits:**
- Multiple configurations can share one Tomcat installation without port or deployment conflicts
- Each config has independent logs and work directories
- The Tomcat installation stays unmodified

**Override:** Set the **CATALINA_BASE** field in Tomcat Server Settings to point to a custom directory (e.g., a RAM disk for faster I/O).

### Configuration Overlay

Customize Tomcat configuration files that persist across runs:

1. Create the overlay directory: `<project>/.idea/devtomcat/<config-name>/conf/`
2. Place modified config files there (e.g., `context.xml`, `catalina.properties`)
3. Files in the overlay overwrite the defaults copied from CATALINA_HOME
4. The plugin always manages ports in `server.xml` — your overlay's custom Realms, Valves, and JNDI resources are preserved

### Auto-Port Resolution

If a configured port is already in use when Tomcat starts, DevTomcat automatically finds the next available port and logs a warning in the console:

```
[DevTomcat] Port conflicts detected and auto-resolved:
  HTTP port 8080 in use, resolved to 8081
  Debug (JDWP) port 5005 in use, resolved to 5006
```

This covers all ports — HTTP, Shutdown, HTTPS, JMX, AJP, and the JDWP debug port.

**Running multiple configurations simultaneously**: DevTomcat uses an atomic port registry to prevent race conditions when two configurations launch at the same time. Both instances see each other's claimed ports before any JVM has bound to them, so they are always assigned distinct ports even if they start within milliseconds of each other.

### Smart Diagnostics

DevTomcat detects 16+ common Tomcat errors in console output and provides actionable suggestions:

| Error Category | Examples |
|---------------|----------|
| **Classpath** | ClassNotFoundException, NoClassDefFoundError |
| **Port** | BindException (port already in use) |
| **Memory** | OutOfMemoryError (heap, metaspace, GC overhead) |
| **SSL** | Certificate errors, keystore problems |
| **Database** | JDBC connection failures, pool exhaustion |
| **Deployment** | Context path conflicts, WAR extraction failures |

### Deployment Dashboard

Track deployment status in the **Services** tool window (**View → Tool Windows → Services**).

![Deployment dashboard in Services](images/deployment-dashboard.png)
*Figure 21 — Live deployment status in the Services tool window*

Features:
- **Live status** — Real-time server and per-artifact status (Starting, Deploying, Running, Failed)
- **Deployment history** — Persistent history with duration and error/warning counts
- **Startup trends** — Track startup performance across runs (**Tools → DevTomcat → Startup Time Trends**)

### Configuration Export/Import

Share run configurations with your team:

1. Click **Export** in the configuration editor toolbar
2. Save the XML file
3. Share the file (commit to VCS, send to teammates)
4. Team members click **Import** and select the XML file

![Export/Import toolbar](images/export-import.png)
*Figure 22 — Export and Import links in the configuration editor*

---

## Troubleshooting

### Tomcat won't start

1. **Check the console output** for error messages — DevTomcat highlights them with diagnostic suggestions
2. **Verify the Tomcat installation** — Go to Settings → DevTomcat and re-validate the server path
3. **Check port conflicts** — Another process may be using port 8080. DevTomcat auto-resolves this, but check the console for warnings
4. **Verify JDK compatibility** — Tomcat 10.1+ requires Java 11+, Tomcat 11 requires Java 17+

### Artifacts not showing in selection dialog

1. **Check Project Structure** — Go to File → Project Structure → Artifacts and verify artifacts are configured
2. **Build the project first** — Run Build → Build Project (Ctrl+F9) to generate build outputs
3. **Auto-detection** — If no IntelliJ artifacts exist, DevTomcat scans for web modules (`src/main/webapp`) and build outputs (`target/*.war`, `build/libs/*.war`)

### Browser opens wrong URL

The browser URL is on the **Server tab** under "Open browser". If it shows the wrong context:
1. Edit the **URL** field directly — type your desired URL
2. Once you edit it, DevTomcat preserves your custom URL permanently
3. The URL only auto-updates from deployments while you haven't customized it

### Changes not reflected after redeployment

1. Press **Ctrl+F10** (Update Running Application) to push changes
2. If using "Update classes and resources", ensure the class was recompiled (Ctrl+Shift+F9)
3. For JSP changes, clear the `work/` directory (done automatically on full restart)
4. Check that **Deploy applications configured in Tomcat instance** is enabled in Tomcat Server Settings

### Remote deployment fails

1. Verify the **Manager application** is deployed on the remote Tomcat
2. Check `tomcat-users.xml` on the remote server — the user needs the `manager-script` role:
   ```xml
   <user username="admin" password="secret" roles="manager-script"/>
   ```
3. Click **Test Connection** to verify connectivity before deploying
4. Check firewall rules — the Manager API port must be accessible

### Duplicate-classpath errors (Liquibase, CDI, Spring) in multi-module projects

If you see errors like:
```
ChangeLogParseException: Found 2 files with the path 'classpath:db/changelog/...'
```
or CDI deployment errors about duplicate bean descriptors, the same module's classes are appearing on the classpath twice — once from `target/classes` and once from its JAR in `WEB-INF/lib`.

**Fix:** Trigger a **Redeploy** from the Services toolbar. DevTomcat will regenerate `context.xml` with the correct classpath — shared modules already packaged as JARs will be served from the JAR only, without an additional `<PreResources>` overlay.

If the error persists after Redeploy, ensure the artifact was built fresh (`mvn clean package` or **Build → Rebuild Project**) so `WEB-INF/lib` contains the latest version of the shared module JAR.

### Tomcat stops after restart and does not relaunch

If a balloon notification appears saying **"Restart Failed"** or **"Relaunch Failed"**, Tomcat stopped successfully but the new process could not start. Common causes:

1. **Run configuration deleted or renamed** — check that the configuration still exists in Run → Edit Configurations
2. **JDK not found** — verify the Project SDK is configured (File → Project Structure → Project)
3. **Port still in use** — the old process may not have released its port yet; wait a few seconds and click **Start** in the Services toolbar manually

---

*DevTomcat — Apache Tomcat integration for IntelliJ IDEA*
*[GitHub](https://github.com/Gezu-t/devtomcat) | [Report Issues](https://github.com/Gezu-t/devtomcat/issues)*
