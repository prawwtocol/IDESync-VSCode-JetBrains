# IDE Sync - VSCode-JetBrains Sync

>**Note:** his synchronization system is suitable for VSCode, VSCode forks like Cursor or Windsurf as well as JetBrains IntelliJ-based IDEs like Rider, IntelliJ IDEA, and WebStorm.

This extension enables synchronization between VS Code and JetBrains IDEs. It automatically synchronizes cursor position and active file between both editors.

## Features

- Automatic cursor position synchronization
- Automatic active file synchronization
- Easy toggle in the status bar

## Installation

1. Install the extension from VS Code Marketplace called `IDE Sync - Connect to JetBrains IDE`
2. Install the corresponding JetBrains plugin in JetBrains IDE called `IDE Sync - Connect to VSCode`

## Usage

1. After installation, you'll find a toggle button in the VS Code status bar
2. Click the button to enable/disable synchronization
3. The button shows the current status:
   - `Turn IDE Sync On` - Sync disabled
   - `IDE Sync On` - Sync enabled

## Settings

You can adjust the WebSocket port in the settings:

1. Open VS Code settings
2. Search for "IDE Sync - Connect to JetBrains IDE"
3. Adjust the port (default: 3000)

## Troubleshooting

If you can't establish a connection:
1. Make sure both plugins are installed
2. Check if the IDE Sync is turned on in both IDEs
3. Verify that the port settings match in both plugins
4. Restart both editors

## Feedback & Issues

Please report issues or suggestions on [GitHub](https://github.com/secret/IDESync-VSCode-JetBrains/issues). 