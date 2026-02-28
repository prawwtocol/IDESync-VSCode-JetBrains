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

## Usage (For Developers)

### Initial Connection Setup

The sync process uses a two-phase handshake with dynamic port assignment:

1. **Start both IDEs** — open Cursor and your JetBrains IDE (e.g., PyCharm, IntelliJ)

2. **Enable sync in Cursor** — click the status bar item or run `Toggle VSCode-JetBrains Sync` command (Cmd+Shift+P). This starts the **Discovery Server** on port 3000. The status bar will show `IDE Sync On:Searching...`

3. **Enable sync in JetBrains** — click the status bar widget or use `Switch to Paired IDE` action. The plugin connects to the Discovery Server on port 3000 and sends a handshake message with the project path

4. **Handshake & Port Assignment** — Cursor validates the workspace path matches, then assigns a unique port (3001, 3002, etc.) and starts the **Data Server**. JetBrains receives the port assignment and disconnects from Discovery

5. **WebSocket Connection Established** — JetBrains connects to the Data Server on the assigned port. Cursor stops the Discovery Server (you'll see a "Discovery server stopped" notification). The status bar in both IDEs now shows the active port (e.g., `IDE Sync On:3001`)

### Switching Between IDEs

Once connected, use **Cmd+Shift+I** (or run `Switch to Paired IDE` command) to instantly switch between Cursor and JetBrains. The current file and cursor position are synchronized automatically.

### Connection Flow Summary

```
Cursor (Discovery Server:3000) ←── JetBrains connects, sends projectPath
         ↓
Cursor validates workspace match
         ↓
Cursor assigns port, starts Data Server
         ↓
JetBrains connects to Data Server (port 3001+)
         ↓
Bidirectional sync active ──→ filePath, line, column, focus changes
```


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
