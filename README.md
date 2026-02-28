# IDE Sync - Cursor-JetBrains IDE Sync (Dev Version)

>**⚠️ DEV VERSION:** This is a development version that must be built locally. It does NOT work with VSCode - only with **Cursor**.

>**Note:** This synchronization system is suitable for Cursor (VSCode fork) and JetBrains IntelliJ-based IDEs like Rider, IntelliJ IDEA and WebStorm.

A synchronization system that allows seamless switching between Cursor and JetBrains IntelliJ-based IDEs while maintaining the current file and cursor position.

## Version Compatibility

### Cursor
- Supported versions: Cursor 1.84.0 and newer (VSCode-compatible builds)

### JetBrains IDE
- Supported versions: 2023.3 and newer

## Configuration

The default port is 3000, this can be changed in the respective settings and must be the same:
- In Cursor: Settings > Extensions > IDE Sync - Connect to JetBrains IDE > Port
- In JetBrains IDE: Settings > Tools > IDE Sync - Connect to VSCode > WebSocket Port

## Usage

1. Start both IDEs (Cursor and JetBrains)
2. Make sure that the sync is activated in the status bar of both IDEs
3. When you switch between IDEs, the current file and cursor position will be synchronized automatically

## Features

- Real-time synchronization of file opening and cursor position
- Automatic reconnection on port changes
- Status bar indicator showing connection status
- Multi-pair support: multiple Cursor+JetBrains pairs can sync simultaneously on different ports

## Components

### Cursor Extension
- Located in `/vscode-extension`
- Monitors current file and cursor position in Cursor
- Communicates with JetBrains plugin via WebSocket
- **Dev build only** - not available in VSCode Marketplace

### JetBrains IDE Plugin
- Located in `/jetbrains-plugin`
- Monitors current file and cursor position in JetBrains IDE
- Communicates with Cursor extension via WebSocket
- **Dev build only** - install from local build

## Building (Local Dev Version)

This dev version must be built locally. It is NOT available in any marketplace.

### Prerequisites
- Node.js and npm for Cursor extension
- JDK 17+ and Gradle for JetBrains IDE plugin

### Build Steps

1. Clone the repository
```bash
git clone https://github.com/secret/IDESync-VSCode-JetBrains.git
cd IDESync-VSCode-JetBrains
```

2. Build Cursor extension (local dev build)
```bash
cd vscode-extension
npm install
npm run build
npm run package
cd ..
```

3. Build JetBrains plugin (local dev build)
```bash
cd jetbrains-plugin
./gradlew buildPlugin
cd ..
```

## Installation (Local Dev Version)

### Cursor Extension (Dev Build)
1. Build the extension locally (see above)
2. Install in Cursor: `Cmd+Shift+P` > `Extensions: Install from VSIX...` > Select `IDESync-VSCode-JetBrains/vscode-extension/vscode-jetbrains-sync-1.2.8.vsix`
3. Restart Cursor

### JetBrains IDE Plugin (Dev Build)
1. Build the plugin locally (see above)
2. Install in JetBrains IDE: `Settings` > `Plugins` > `Manage Repositories,... (Settings symbol)` > `Install Plugin from Disk...` > Select `IDESync-VSCode-JetBrains/jetbrains-plugin/build/distributions/vscode-jetbrains-sync-1.2.8.zip`
3. Restart JetBrains IDE

## Current Dev Version

**Version:** 1.2.8 (both Cursor and JetBrains)

**Recent Changes:**
- Multi-pair synchronization support (ports 3001, 3002, etc.)
- Workspace validation for correct pair matching
- Discovery Server architecture with dynamic port assignment
- Clear error notifications for connection failures