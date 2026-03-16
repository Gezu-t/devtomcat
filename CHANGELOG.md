# DevTomcat Changelog

## [Unreleased]

## [1.0.6]

### Fixed
- **Debug mode**: Fixed debugger not attaching — explicit RemoteConnection with JDWP port matching, JDK 9+ `address=*:port` format
- **Debug port conflicts**: Debug port (5005) now included in pre-launch port conflict detection and auto-resolution
- **Catalina log spam**: Set `reloadable="false"` in context XML to prevent Tomcat's background class scanner from flooding logs with `NoSuchFileException` when Maven cache JARs are missing
- **Deprecated API cleanup**: Replaced `ProcessAdapter`, `Comparing.equal`, `new URL(String)`, `ConfigurationException.getMessage()`, `RawCommandLineEditor`, `FileSaverDescriptor`, `FileChooserDescriptorFactory.createSingleLocalFileDescriptor`
- **Internal API removal**: Replaced `SlowOperations.knownIssue` (internal) with `SlowOperations.allowSlowOperations` (public)
- **`ExpandableTextField`**: Replaced deprecated `RawCommandLineEditor.setDialogCaption()` with `ExpandableTextField.setTitle()`

### Changed
- Application updates (Ctrl+F10) now handled entirely by `TomcatApplicationUpdater` — no dependency on Tomcat's `reloadable` background scanner
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