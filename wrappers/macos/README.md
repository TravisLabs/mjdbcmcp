# mjdbcmcp macOS Menu Bar App

A native Swift macOS menu bar application for supervising the `mjdbcmcp` server.

## Features

- **Menu Bar Status Item**: Live status indicator (Running, Starting, Stopped, Error) using SF Symbols without cluttering the Dock (`LSUIElement = true`).
- **Process Supervisor**: Automatically launches `mjdbcmcp.jar` with host Java 21+ (`/opt/homebrew`, `/Library/Java/JavaVirtualMachines`, `/usr/libexec/java_home`, `PATH`, etc.) and handles graceful shutdown via `SIGTERM`.
- **Health Polling**: Continuously queries `http://127.0.0.1:<port>/actuator/health` to confirm server readiness.
- **Settings UI**: Native SwiftUI preferences window to configure:
  - Server port (default: `8080`)
  - Server configuration directory (default: `~/.mjdbcmcp`)
  - Custom Java executable path override
  - Custom JAR path override
  - Autostart on application launch
- **Quick Actions**:
  - Open Admin Interface in default browser (`http://localhost:<port>`)
  - Copy MCP endpoint URL (`http://localhost:<port>/mcp`) to clipboard
  - Start / Stop / Restart server
  - Open configuration directory in Finder
  - View server logs (`server.log`)

## Building

### Quick Build Script
From the repository root:
```bash
# 1. Build the backend JAR (if not already built)
./gradlew bootJar

# 2. Build the macOS Menu Bar App
./wrappers/macos/scripts/build-app.sh
```
The output application bundle is created at:
```
wrappers/macos/build/MjdbcmcpMenu.app
```

### Developing with Swift Package Manager
```bash
cd wrappers/macos
swift build
```

To open in Xcode:
```bash
open wrappers/macos/Package.swift
```
