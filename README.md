# DevTomcat
   
   <!-- Plugin description -->
   Free Tomcat integration for IntelliJ IDEA Community Edition.
   
   DevTomcat automatically loads webapp classes and libraries from your project—no need to manually copy files to `WEB-INF/classes` or `WEB-INF/lib`. Supports Tomcat 6+.
   <!-- Plugin description end -->
   
   ## Installation
   
   1. Open **Settings** → **Plugins** → **Marketplace**
   2. Search for "DevTomcat"
   3. Install and restart IDE
   
   ## Configuration
   
   ### Add Tomcat Server
   
   1. Go to **File** → **Settings** (or `Ctrl + Alt + S`)
   2. Navigate to **Tomcat Server**
   3. Add your Tomcat installations (e.g., Tomcat 9, Tomcat 10)
   
   ### Create Run Configuration
   
   1. Go to **Run** → **Edit Configurations**
   2. Click **+** → **DevTomcat**
   3. Configure the settings below
   
   ## Run Configuration Options
   
   | Option | Description | Default |
   |--------|-------------|---------|
   | **Tomcat Server** | Select configured Tomcat installation | — |
   | **Deployment Directory** | Webapp directory (e.g., `src/main/webapp`) | Auto-detected |
   | **Context Path** | Application context path | `/<module_name>` |
   | **Server Port** | HTTP port | `8080` |
   | **Admin Port** | Shutdown port | `8005` |
   | **VM Options** | JVM arguments (e.g., `-Duser.language=en`) | — |
   | **Env Options** | Environment variables (e.g., `key=value`) | — |
   
   > **Note:** Do not add compiled output directories to Deployment Directory.
   
   ## Custom Context
   
   Place a `context.xml` in `webapp/META-INF/` for custom resources:
   
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <Context>
       <Environment name="appName" value="MyApp" type="java.lang.String" override="false"/>
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