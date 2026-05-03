<!-- Plugin description -->
# DevTomcat

DevTomcat is an IntelliJ IDEA plugin that provides advanced Apache Tomcat
server management for IntelliJ Community Edition.

Run, debug, and deploy web applications with smart diagnostics, live status tracking, and zero configuration overhead. DevTomcat automatically loads webapp classes and libraries from your project — no need to manually copy files to `WEB-INF/classes` or `WEB-INF/lib`. Supports Tomcat 7+.
<!-- Plugin description end -->

## Features

- **Full Tomcat lifecycle** — Start, stop, debug, and redeploy from the IDE
- **Auto-detect artifacts** — Finds WAR/exploded artifacts from IntelliJ Artifacts, web modules, and build output
- **Multi-module support** — Deploy multiple web modules simultaneously with independent context paths
- **Port conflict auto-resolution** — Detects occupied ports and reassigns automatically at startup
- **server.xml generation** — Produces per-instance `server.xml` with HTTP, HTTPS, AJP, and shutdown connectors from your configuration
- **CATALINA_BASE isolation** — Each run configuration gets its own instance directory, or you can point to a custom one
- **Startup performance tracking** — Records startup times and shows trend comparisons across runs
- **Intelligent console monitoring** — Detects Tomcat log levels (SEVERE/ERROR/FATAL/WARNING) and exception stack traces without false positives on normal application output
- **Graceful non-blocking shutdown** — Sends the SHUTDOWN command (or runs a custom script) on a background thread so the IDE stays responsive
- **Custom script support** — Startup and shutdown scripts are properly tokenized, so `catalina.sh run` is correctly split into executable + arguments
- **Before Launch consolidation** — All deployment artifacts are combined into a single "Build N artifacts" task
- **Log file monitoring** — Standard Tomcat logs appear as console tabs with automatic merge of new log types on plugin updates
- **Remote deployment** — Deploy to a remote Tomcat instance via the Manager API

## Installation

1. Open **Settings** > **Plugins** > **Marketplace**
2. Search for "DevTomcat"
3. Install and restart IDE

## Quick Start

### 1. Register a Tomcat Server

1. Go to **File** > **Settings** (or `Ctrl+Alt+S`)
2. Navigate to **Tomcat Server**
3. Click **+** and point to your Tomcat installation directory (e.g., `/opt/tomcat-10`)
4. The plugin auto-detects the version, CATALINA_HOME, and bundled libraries

### 2. Create a Run Configuration

1. Go to **Run** > **Edit Configurations**
2. Click **+** > **DevTomcat**
3. Select the Tomcat server you registered
4. Add deployment artifacts (auto-detected from your project)
5. Click **Run** or **Debug**

---

## Run Configuration Tabs

The DevTomcat run configuration editor has five tabs:

| Tab | Purpose |
|-----|---------|
| **Server** | Tomcat instance, ports, JVM, browser, and runtime behavior |
| **Deployment** | WAR/exploded artifacts and context paths |
| **Logs** | Log file monitoring in the console |
| **Startup/Connection** | Startup/shutdown scripts and environment variables |
| **Code Coverage** | Code coverage settings |

---

## Server Tab

The Server tab is the main configuration surface. It switches between **Local** and **Remote** modes.

### Application Server

| Field | Description |
|-------|-------------|
| **Application server** | Dropdown of registered Tomcat installations. Click **Configure...** to add/edit servers. |

### Open Browser

| Field | Description | Default |
|-------|-------------|---------|
| **After launch** | Open a browser when the server starts | Enabled |
| **Browser** | Which browser to open | System default |
| **URL** | The URL to open | `http://localhost:{port}/{context}` |

### VM Options

Free-form JVM arguments passed to the Tomcat process. Examples:

```
-Xmx1024m -Xms256m
-Dspring.profiles.active=dev
-Duser.language=en -Duser.country=US
```

### Update Actions

Controls what happens when you modify code while the server is running:

| Field | Options | Default |
|-------|---------|---------|
| **On 'Update' action** | Update resources, Update classes and resources, Redeploy, Restart server | Restart server |
| **On frame deactivation** | Do nothing, or any of the above | Do nothing |
| **Show dialog** | Prompt before taking action | Enabled |

### JRE

| Field | Description | Default |
|-------|-------------|---------|
| **JRE** | Java runtime for the Tomcat process. Lists all configured SDKs. | Project SDK |

### Tomcat Server Settings

This section controls ports, deployment behavior, and the CATALINA_BASE directory.

#### Ports

| Port | Description | Default | When Active |
|------|-------------|---------|-------------|
| **HTTP port** | Main connector for web traffic | `8080` | Always |
| **HTTPS port** | SSL/TLS connector | `8443` | When a value is entered |
| **JMX port** | Java Management Extensions monitoring | `1099` | When a value is entered |
| **AJP port** | Apache JServ Protocol connector (for Apache httpd fronting) | `8009` | When a value is entered |
| **Shutdown port** | Tomcat admin shutdown listener | `8005` | Always |

**Port behavior:**
- Leave a port field **empty** to disable that connector (HTTPS, JMX, AJP).
- HTTP and Shutdown ports are always required.
- If a port is in use, DevTomcat **auto-resolves** to the next available port and logs a warning.
- Internal conflicts (same port assigned to multiple services) are also auto-resolved.

#### CATALINA_BASE

| Field | Description | Default |
|-------|-------------|---------|
| **CATALINA_BASE** | The instance-specific runtime directory for this configuration | Auto-generated |

**What is CATALINA_BASE?**

Apache Tomcat separates its installation (`CATALINA_HOME`) from its runtime instance (`CATALINA_BASE`). This allows multiple Tomcat instances to share the same installation binaries while having independent configurations, logs, and deployed applications.

```
CATALINA_HOME (shared, read-only)     CATALINA_BASE (per-instance)
├── bin/                                ├── conf/
│   ├── bootstrap.jar                   │   ├── server.xml      ← generated with your ports
│   ├── catalina.sh                     │   ├── web.xml         ← copied from CATALINA_HOME
│   └── tomcat-juli.jar                 │   ├── context.xml
├── lib/                                │   ├── logging.properties
│   ├── catalina.jar                    │   └── Catalina/
│   ├── servlet-api.jar                 │       └── localhost/
│   └── ...                             │           └── myapp.xml  ← deployment descriptors
└── ...                                 ├── logs/
                                        │   ├── catalina.out
                                        │   ├── catalina.2024-01-15.log
                                        │   └── localhost.2024-01-15.log
                                        ├── temp/
                                        ├── webapps/
                                        └── work/
```

**Default behavior (field left empty):**

DevTomcat auto-generates a CATALINA_BASE at:
```
{project}/.idea/tomcat/{configuration-name}/
```
Each run configuration gets its own isolated instance directory. This is the recommended setup for most projects.

**Custom CATALINA_BASE:**

Set a custom path when you need to:
- Use a **pre-configured** Tomcat instance directory with specific `server.xml` settings, SSL certificates, custom valves, or JNDI resources
- **Share** a CATALINA_BASE across configurations or projects
- Point to an **external** directory (e.g., on a separate disk for logs)
- Use a directory with **pre-existing data** (e.g., persisted sessions, cached JSP compilations)

Click the **browse button** to select a directory.

#### Deployment Options

| Field | Description | Default |
|-------|-------------|---------|
| **Deploy applications configured in Tomcat instance** | Enable hot deployment of artifacts | Enabled |
| **Preserve sessions across restarts and redeploys** | Keep HTTP sessions alive during redeployment | Disabled |

### server.xml Generation

When Tomcat starts, DevTomcat generates (or updates) `conf/server.xml` inside CATALINA_BASE based on the original `server.xml` from CATALINA_HOME and your port configuration:

- **HTTP connector** — Port is always substituted
- **Shutdown port** — Always substituted on the `<Server>` element
- **HTTPS connector** — When enabled:
  - Updates an existing HTTPS connector's port if found (matches by `SSLEnabled="true"` or `scheme="https"`)
  - Injects a new `Http11NioProtocol` connector if none exists
  - Updates `redirectPort` on the HTTP connector to match
- **AJP connector** — When enabled:
  - Updates an existing `AJP/1.3` connector's port if found
  - Uncomments a commented-out AJP connector (common in Tomcat 9+) and adds `secretRequired="false"`
  - Injects a new AJP connector if none exists

---

## Deployment Tab

### Adding Artifacts

Click **+** to add deployable artifacts. DevTomcat auto-detects artifacts using a tiered strategy:

1. **IntelliJ Artifacts** — Artifacts configured in **Project Structure** > **Artifacts** (WAR, Exploded WAR)
2. **Web Modules** — Modules with `src/main/webapp` or `WEB-INF` directories
3. **Build Output** — WAR files in `build/libs`, `target`, `out/artifacts`

### Deployment List

| Column | Description |
|--------|-------------|
| **Artifact** | Name of the deployment artifact |
| **Application context** | URL path (e.g., `/myapp`). Use `/` for ROOT deployment. Editable below the list. |

### Validation

DevTomcat validates deployment artifacts before applying configuration. Artifacts with missing or empty paths are flagged as invalid, preventing runtime failures.

### Before Launch

DevTomcat consolidates all deployment artifacts into a single **"Build N artifacts"** task in the Before Launch section. Adding or removing artifacts automatically updates this task.

### Multi-Module Projects

For multi-module Maven/Gradle projects, each web module is detected as a separate deployable artifact. You can deploy multiple artifacts simultaneously — each with its own context path.

---

## Logs Tab

### Log Files

DevTomcat monitors standard Tomcat log files and displays them as tabs in the Run/Debug console:

| Log File | Pattern | Default |
|----------|---------|---------|
| **Catalina Out** | `catalina.out` | Active |
| **Catalina Log** | `catalina.*.log` | Active |
| **Localhost Log** | `localhost.*.log` | Active |
| **Access Log** | `localhost_access_log.*.txt` | Inactive |
| **Manager Log** | `manager.*.log` | Inactive |
| **Host Manager Log** | `host-manager.*.log` | Inactive |

- Use the toolbar buttons to **add**, **edit**, or **remove** custom log file entries
- Toggle **Is Active** to enable/disable individual log file monitoring
- Toggle **Skip Content** to skip existing content and only show new entries
- New standard log types added in plugin updates are **automatically merged** into existing configurations

### Console Monitoring

DevTomcat parses Tomcat console output in real time to provide:

- **Error detection** — Matches `SEVERE`, `ERROR`, and `FATAL` log levels plus Java exception stack traces (`Caused by:`, exception class names). Avoids false positives on normal application output that happens to contain words like "error" or "unable".
- **Warning detection** — Matches `WARNING` and `WARN` log levels.
- **Error suggestions** — When known errors are detected (e.g., `BindException`, `OutOfMemoryError`, `ClassNotFoundException`), DevTomcat logs actionable suggestions in the console.
- **Startup tracking** — Records server startup time and compares against previous runs.
- **Session summary** — On shutdown, logs a summary including duration, exit code, error/warning counts, and deployment status.

### Console Options

| Option | Description | Default |
|--------|-------------|---------|
| **Save console output to file** | Persist console output to a file | Disabled |
| **Show console when stdout** | Show console on standard output | Enabled |
| **Show console when stderr** | Show console on standard error | Enabled |
| **Activate tool window** | Bring tool window to front on output | Enabled |
| **Show this page** | Show the Logs tab on activation | Disabled |

---

## Startup/Connection Tab

Configures startup and shutdown behavior for each execution mode independently.

### Execution Modes

Each mode maintains its own startup script, shutdown script, and environment variables:

| Mode | Description |
|------|-------------|
| **Run** | Normal execution |
| **Debug** | Debug with breakpoints (JDWP agent auto-configured) |
| **Coverage** | Code coverage instrumentation |
| **Profile** | CPU/memory profiling |

### Scripts

| Field | Description | Default |
|-------|-------------|---------|
| **Startup script** | Script to start Tomcat | `{TOMCAT_HOME}/bin/catalina.sh run` |
| **Shutdown script** | Script to stop Tomcat | `{TOMCAT_HOME}/bin/catalina.sh stop` |
| **Use default** | Use the standard catalina script | Enabled |

Custom scripts are properly tokenized — a script like `catalina.sh run` is correctly split into the executable and its arguments, supporting paths with spaces when quoted.

### Shutdown Behavior

When stopping Tomcat, DevTomcat uses this strategy:

1. **Custom script** — If configured, runs the shutdown script with the configured environment variables
2. **Graceful shutdown** — Sends the `SHUTDOWN` command to the shutdown port (default 8005)
3. **Force kill** — If the process doesn't terminate within 10 seconds, forces termination

All shutdown operations run on a background thread to keep the IDE responsive.

### Environment Variables

Add environment variables passed to the Tomcat process. Common presets available:

| Variable | Typical Use |
|----------|------------|
| `JAVA_OPTS` | JVM options applied to all Java commands |
| `CATALINA_OPTS` | JVM options for Tomcat only (not shutdown) |
| `CATALINA_HOME` | Override Tomcat installation directory |
| `CATALINA_BASE` | Override instance directory |
| `JAVA_HOME` | Override Java installation |

| Option | Description | Default |
|--------|-------------|---------|
| **Pass environment variables** | Inherit parent process environment | Enabled |

---

## Remote Mode

DevTomcat supports deploying to a remote Tomcat instance via the Tomcat Manager API.

### Setup

1. In the Server tab, switch to **Remote** mode
2. Configure the **Host** and **Port** of the remote Tomcat Manager
3. The Manager URL is constructed as `http://{host}:{port}/manager`

### Requirements

- Tomcat Manager application must be deployed and accessible
- Manager credentials must be configured in `tomcat-users.xml` on the remote server
- The `manager-script` role is required for deployment

---

## Custom Context

Place a `context.xml` in `webapp/META-INF/` for custom resources:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <Environment name="appName" value="MyApp"
                 type="java.lang.String" override="false"/>
    <Resource name="jdbc/ds"
              auth="Container"
              type="javax.sql.DataSource"
              username="sa"
              password="sa"
              driverClassName="org.h2.Driver"
              url="jdbc:h2:mem:db;DB_CLOSE_DELAY=-1"
              maxActive="8"
              maxIdle="4"/>
</Context>
```

---

## Debug Mode

1. Click **Debug** instead of **Run** (or `Shift+F9`)
2. DevTomcat automatically injects the JDWP agent with the configured debug port
3. Set breakpoints in servlets, filters, listeners, or any server-side code
4. Hot-reload changes using the **Update** action (`Ctrl+F10`)

---

## Port Conflict Resolution

DevTomcat automatically handles port conflicts at startup:

1. **Internal conflicts** — If two services are assigned the same port (e.g., HTTP and JMX both on 8080), the second is reassigned to the next available port.
2. **External conflicts** — If a port is already in use by another process, DevTomcat finds the next available port.
3. All port changes are logged in the console with warnings so you know the actual ports in use.

---

## Troubleshooting

### Server won't start
- Check that CATALINA_HOME points to a valid Tomcat installation with `bin/bootstrap.jar`
- Verify no other process is using the configured ports
- Check the **Logs** tab for Tomcat startup errors
- Look for `SEVERE` or `ERROR` messages in the console — DevTomcat highlights these and may suggest fixes

### Deployment not working
- Ensure your artifact has a valid `WEB-INF/web.xml` (or uses Servlet 3.0+ annotations)
- Check that the artifact path exists and is built
- For exploded deployments, verify the directory contains a `WEB-INF` folder
- Verify the deployment appears in the Deployment tab with a valid context path

### HTTPS not working
- The HTTPS connector is injected into `server.xml`, but you still need to configure an **SSL certificate**
- Place your keystore in CATALINA_BASE and reference it in `server.xml` after the first run
- Alternatively, configure SSL via JVM system properties in VM Options

### Logs not appearing
- Verify `logging.properties` exists in `{CATALINA_BASE}/conf/`
- Check that log file patterns match the actual filenames in `{CATALINA_BASE}/logs/`
- Ensure the log entry is marked as **Active** in the Logs tab

### Custom shutdown script not working
- Ensure the script path is correct and the file is executable
- Scripts with arguments (e.g., `catalina.sh stop`) are automatically tokenized — no need to wrap in a shell script
- Check console output for shutdown errors — DevTomcat falls back to default shutdown if the script fails

---

## About

DevTomcat is developed and maintained by **Gezahegn Tsegaye**.

If this plugin helps you in your development workflow, please consider leaving a review on the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30721-devtomcat). Your feedback helps improve the project.

Bug reports, feature requests, and contributions are welcome.

## Inspiration & Credits

DevTomcat was inspired by [Smart Tomcat](https://plugins.jetbrains.com/plugin/9492-smart-tomcat) —
a lightweight Tomcat plugin for IntelliJ IDEA Community Edition by Victor Zheng.
Smart Tomcat demonstrated that first-class Tomcat support was possible outside of IntelliJ Ultimate,
and served as the original motivation for building DevTomcat.

DevTomcat has since taken a different direction — adding multi-artifact deployment, port conflict
auto-resolution, CATALINA_BASE isolation, remote deployment, a state-machine process handler, and
full IntelliJ Platform API integration — but the credit for the idea belongs to the Smart Tomcat project.
