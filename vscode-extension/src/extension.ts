import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { execSync, exec } from 'child_process';
import { promisify } from 'util';
import WebSocket, { WebSocketServer, RawData } from 'ws';

const execAsync = promisify(exec);

interface SyncState {
    filePath: string;
    line: number;
    column: number;
    source: 'vscode' | 'jetbrains';
    isActive?: boolean;
    action?: string;
}

interface HelloMessage {
    type: 'hello';
    workspacePath: string;
}

interface PortAssignmentMessage {
    type: 'port-assignment';
    port: number;
    workspacePath: string;
}

interface FocusMessage {
    action: 'focus';
}

interface PairInfo {
    workspacePath: string;
    port: number;
    jetbrainsProjectPath?: string;
    lastConnectedAt: string;
    lastSwitchAt?: string;
}

interface PairsData {
    pairs: PairInfo[];
    updatedAt: string;
}

interface PendingConnection {
    ws: WebSocket;
    workspacePath: string;
    tempPort: number;
}

export class VSCodeJetBrainsSync {
    // Discovery server (port 3000)
    private discoveryWss: WebSocketServer | null = null;
    private readonly DISCOVERY_PORT = 3000;

    // Active data server (assigned port)
    private dataWss: WebSocketServer | null = null;
    private assignedPort: number | null = null;

    // Next available port for assignment - loaded from persistent storage
    private nextAvailablePort: number = 3001;

    private jetbrainsClient: WebSocket | null = null;
    private pendingConnections: Map<number, PendingConnection> = new Map();
    private disposables: vscode.Disposable[] = [];
    private currentState: SyncState | null = null;
    private isActive: boolean = false;
    private statusBarItem: vscode.StatusBarItem;
    private isConnected: boolean = false;
    private autoReconnect: boolean = false;
    private outputChannel: vscode.OutputChannel;
    private context: vscode.ExtensionContext;
    private logFileStream: fs.WriteStream | null = null;
    private logFilePath: string | null = null;

    constructor(context: vscode.ExtensionContext) {
        this.context = context;
        this.outputChannel = vscode.window.createOutputChannel('IDE Sync');
        this.statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
        this.statusBarItem.command = 'vscode-jetbrains-sync.toggleAutoReconnect';
        this.updateStatusBarItem();
        this.statusBarItem.show();

        this.log('INIT', `Extension started, workspacePath=${this.getWorkspacePath()}`);
        const logDir = this.getLogDir();
        this.outputChannel.appendLine(`[INIT] Log directory: ${logDir}`);

        // Discovery server starts only when sync is enabled and not connected
        this.setupEditorListeners();
        this.setupWindowListeners();
        this.isActive = vscode.window.state.focused;
        this.loadPairsAndInitPort();
    }

    private getLogDir(): string {
        const dir = path.join(os.homedir(), '.ide-sync');
        try {
            if (!fs.existsSync(dir)) {
                fs.mkdirSync(dir, { recursive: true });
            }
            return dir;
        } catch (e) {
            this.outputChannel.appendLine(`[INIT] Failed to create ~/.ide-sync, using tmp: ${e}`);
            const fallback = path.join(os.tmpdir(), 'ide-sync');
            fs.mkdirSync(fallback, { recursive: true });
            return fallback;
        }
    }

    private startFileLogging(port: number) {
        this.stopFileLogging();
        const sessionStart = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
        const fileName = `debug_port${port}_${sessionStart}.log`;
        this.logFilePath = path.join(this.getLogDir(), fileName);
        try {
            this.logFileStream = fs.createWriteStream(this.logFilePath, { flags: 'a' });
            this.logFileStream.on('error', (err) => {
                this.outputChannel.appendLine(`[FILE-LOG] Failed to write: ${err.message}`);
            });
            this.outputChannel.appendLine(`[FILE-LOG] Writing to ${this.logFilePath}`);
            vscode.window.showInformationMessage(`IDE Sync: logs → ${this.logFilePath}`, 'Open Folder').then(choice => {
                if (choice === 'Open Folder' && this.logFilePath) {
                    vscode.env.openExternal(vscode.Uri.file(path.dirname(this.logFilePath)));
                }
            });
        } catch (e) {
            this.outputChannel.appendLine(`[FILE-LOG] Failed to create ${this.logFilePath}: ${e}`);
            vscode.window.showErrorMessage(`IDE Sync: failed to create log file: ${e}`);
        }
    }

    private stopFileLogging() {
        if (this.logFileStream) {
            try {
                this.logFileStream.end();
            } catch (_) { /* ignore */ }
            this.logFileStream = null;
        }
        this.logFilePath = null;
    }

    private log(action: string, details: string) {
        const timestamp = new Date().toISOString().slice(11, 23);
        const line = `[${timestamp}] [${action}] ${details}`;
        this.outputChannel.appendLine(line);
        if (this.logFileStream?.writable) {
            this.logFileStream.write(line + '\n');
        }
    }

    private getWorkspacePath(): string {
        const folder = vscode.workspace.workspaceFolders?.[0];
        return folder?.uri.fsPath ?? vscode.workspace.rootPath ?? '';
    }

    private getPairsPath(): string {
        const base = this.context.globalStorageUri.fsPath;
        if (!fs.existsSync(base)) {
            fs.mkdirSync(base, { recursive: true });
        }
        return path.join(base, 'pairs.json');
    }

    private loadPairsAndInitPort(): PairsData {
        try {
            const pairsPath = this.getPairsPath();
            if (fs.existsSync(pairsPath)) {
                const data = JSON.parse(fs.readFileSync(pairsPath, 'utf-8')) as PairsData;
                this.log('PERSIST', `Loaded pairs from ${pairsPath}: ${JSON.stringify(data)}`);
                // Find max port used and set nextAvailablePort to max + 1
                const maxPort = data.pairs.reduce((max, p) => Math.max(max, p.port), 3000);
                this.nextAvailablePort = Math.max(maxPort + 1, 3001);
                this.log('INIT', `Next available port set to ${this.nextAvailablePort}`);
                return data;
            }
        } catch (e) {
            this.log('PERSIST', `Error loading pairs: ${e}`);
        }
        this.nextAvailablePort = 3001;
        return { pairs: [], updatedAt: new Date().toISOString() };
    }

    private savePairs(data: PairsData) {
        try {
            const pairsPath = this.getPairsPath();
            data.updatedAt = new Date().toISOString();
            fs.writeFileSync(pairsPath, JSON.stringify(data, null, 2), 'utf-8');
            this.log('PERSIST', `Saved pairs to ${pairsPath}`);
        } catch (e) {
            this.log('PERSIST', `Error saving pairs: ${e}`);
        }
    }

    private updatePairInfo(jetbrainsProjectPath?: string, isSwitch: boolean = false) {
        const workspacePath = this.getWorkspacePath();
        const data = this.loadPairsAndInitPort();
        const now = new Date().toISOString();
        const port = this.assignedPort ?? this.nextAvailablePort;

        let pair = data.pairs.find(p => p.workspacePath === workspacePath);
        if (!pair) {
            pair = {
                workspacePath,
                port,
                jetbrainsProjectPath,
                lastConnectedAt: now,
            };
            data.pairs.push(pair);
        } else {
            if (jetbrainsProjectPath) pair.jetbrainsProjectPath = jetbrainsProjectPath;
            pair.lastConnectedAt = pair.lastConnectedAt || now;
            pair.port = port;
        }
        if (isSwitch) pair.lastSwitchAt = now;
        this.savePairs(data);
    }

    private updateStatusBarItem() {
        let icon = '$(sync~spin)';
        if (this.isConnected) {
            icon = '$(check)';
        } else if (!this.autoReconnect) {
            icon = '$(sync-ignored)';
        }

        const portInfo = this.assignedPort ? `:${this.assignedPort}` : '';
        this.statusBarItem.text = `${icon} ${this.autoReconnect ? 'IDE Sync On' + portInfo : 'Turn IDE Sync On'}`;
        this.statusBarItem.tooltip = `${this.isConnected ? 'Connected to JetBrains IDE\n' : 'Waiting for JetBrains IDE connection\n'}Click to turn sync ${this.autoReconnect ? 'off' : 'on'}`;
    }

    public toggleAutoReconnect() {
        this.autoReconnect = !this.autoReconnect;

        if (!this.autoReconnect) {
            this.cleanupConnections();
            this.log('TOGGLE', 'Sync disabled');
            vscode.window.showInformationMessage('IDE Sync: Disabled');
        } else {
            this.log('TOGGLE', 'Sync enabled');
            vscode.window.showInformationMessage('IDE Sync: Enabled, starting discovery...');
            // Start discovery server to accept new connections
            this.startDiscoveryServer();
            // Check if discovery server actually started after a short delay
            setTimeout(() => {
                if (this.autoReconnect && !this.discoveryWss && !this.isConnected) {
                    vscode.window.showErrorMessage(
                        'IDE Sync: Failed to start discovery server. ' +
                        'Check if another VS Code instance is already searching for a pair.'
                    );
                }
            }, 500);
        }

        this.updateStatusBarItem();
    }

    private cleanupConnections() {
        if (this.jetbrainsClient) {
            this.jetbrainsClient.close();
            this.jetbrainsClient = null;
        }
        if (this.dataWss) {
            this.dataWss.close(() => {
                this.log('SERVER', 'Data server closed');
            });
            this.dataWss = null;
        }
        this.stopFileLogging();
        this.isConnected = false;
        this.assignedPort = null;
    }

    public async switchToPairedIDE() {
        this.log('CMD-I', 'switchToPairedIDE called');
        if (!this.jetbrainsClient || this.jetbrainsClient.readyState !== WebSocket.OPEN) {
            this.log('CMD-I', 'Not connected to JetBrains, cannot switch');
            vscode.window.showWarningMessage('Not connected to JetBrains IDE');
            return;
        }

        const editor = vscode.window.activeTextEditor;
        if (!editor) {
            this.log('CMD-I', 'No active editor');
            vscode.window.showWarningMessage('No active editor');
            return;
        }

        const document = editor.document;
        const position = editor.selection.active;
        const state: SyncState = {
            filePath: document.uri.fsPath,
            line: position.line,
            column: position.character,
            source: 'vscode',
            action: 'switch'
        };

        try {
            this.log('CMD-I', `Sending state: ${JSON.stringify(state)}`);
            this.jetbrainsClient.send(JSON.stringify(state));
            this.updatePairInfo(undefined, true);

            this.log('CMD-I', 'Sending focus request');
            this.jetbrainsClient.send(JSON.stringify({ action: 'focus' } as FocusMessage));

            // After sending, actively focus the JetBrains window
            // (similar to how JetBrains plugin focuses Cursor window)
            this.log('CMD-I', 'Focusing JetBrains IDE window');
            await this.focusJetBrainsWindow();
        } catch (error) {
            this.log('CMD-I', `Error: ${error}`);
            vscode.window.showErrorMessage(`Failed to switch: ${error}`);
        }
    }

    /**
     * Focus JetBrains IDE window using Aerospace or AppleScript
     * Mirrors the behavior of focusCursorWindow in JetBrains plugin
     */
    private async focusJetBrainsWindow(): Promise<void> {
        if (os.platform() !== 'darwin') {
            this.log('FOCUS-JB', 'Non-macOS: focus not implemented');
            return;
        }

        this.log('FOCUS-JB', 'Focusing JetBrains IDE window');

        // Strategy 1: Try Aerospace (most reliable for Aerospace users)
        if (await this.focusJetBrainsWithAerospace()) {
            this.log('FOCUS-JB', 'Successfully focused JetBrains with Aerospace');
            return;
        }

        // Strategy 2: AppleScript activation
        this.focusJetBrainsWithAppleScript();
    }

    private async focusJetBrainsWithAerospace(): Promise<boolean> {
        try {
            // Check if aerospace is available
            await execAsync('which aerospace');
        } catch (e) {
            this.log('FOCUS-JB', 'Aerospace not found');
            return false;
        }

        const workspacePath = this.getWorkspacePath();
        const projectName = path.basename(workspacePath);
        this.log('FOCUS-JB', `Looking for window with project: ${projectName}`);

        // Common JetBrains IDE bundle IDs
        const bundleIds = [
            'com.jetbrains.pycharm',     // PyCharm
            'com.jetbrains.intellij',    // IntelliJ IDEA
            'com.jetbrains.goland',      // GoLand
            'com.jetbrains.rubymine',    // RubyMine
            'com.jetbrains.webstorm',    // WebStorm
            'com.jetbrains.clion',       // CLion
            'com.jetbrains.datagrip',    // DataGrip
            'com.jetbrains.phpstorm',    // PhpStorm
            'com.jetbrains.rider',       // Rider
            'com.jetbrains.appcode',     // AppCode
            'com.jetbrains.ide'          // Generic
        ];

        // First, get ALL windows across ALL workspaces with workspace info
        // Format: window-id workspace app-bundle-id window-title
        let allWindows: Array<{ windowId: string, workspace: string, bundleId: string, title: string }> = [];

        try {
            const { stdout: allOutput } = await execAsync(
                `aerospace list-windows --all --format "%{window-id} %{workspace} %{app-bundle-id} %{window-title}"`
            );

            if (allOutput.trim()) {
                const lines = allOutput.trim().split('\n').filter(l => l.trim());
                for (const line of lines) {
                    // Parse: window-id workspace bundle-id title
                    // Title may contain spaces, so split carefully
                    const match = line.match(/^(\S+)\s+(\S+)\s+(\S+)\s+(.+)$/);
                    if (match) {
                        allWindows.push({
                            windowId: match[1],
                            workspace: match[2],
                            bundleId: match[3],
                            title: match[4]
                        });
                    }
                }
                this.log('FOCUS-JB', `Found ${allWindows.length} total windows across all workspaces`);
            }
        } catch (e) {
            this.log('FOCUS-JB', `Failed to list all windows: ${e}`);
        }

        // Find matching JetBrains window
        let targetWindow: { windowId: string, workspace: string, bundleId: string, title: string } | null = null;

        // First try: match by project name in title
        for (const win of allWindows) {
            if (bundleIds.includes(win.bundleId) || bundleIds.some(b => win.bundleId.startsWith(b))) {
                if (projectName && win.title.toLowerCase().includes(projectName.toLowerCase())) {
                    targetWindow = win;
                    this.log('FOCUS-JB', `Found matching window: ${win.windowId} in workspace ${win.workspace} - ${win.title}`);
                    break;
                }
            }
        }

        // Second try: any JetBrains window
        if (!targetWindow) {
            for (const win of allWindows) {
                if (bundleIds.includes(win.bundleId) || bundleIds.some(b => win.bundleId.startsWith(b))) {
                    targetWindow = win;
                    this.log('FOCUS-JB', `Using first JetBrains window: ${win.windowId} in workspace ${win.workspace} - ${win.title}`);
                    break;
                }
            }
        }

        // Third try: old method - per-bundle search (for backwards compatibility)
        if (!targetWindow) {
            for (const bundleId of bundleIds) {
                try {
                    const { stdout: listOutput } = await execAsync(
                        `aerospace list-windows --app-bundle-id "${bundleId}" --format "%{window-id} %{window-title}"`
                    );

                    if (!listOutput.trim()) {
                        continue;
                    }

                    const lines = listOutput.trim().split('\n').filter(l => l.trim());
                    for (const line of lines) {
                        const parts = line.trim().split(' ');
                        if (parts.length >= 2) {
                            const windowId = parts[0];
                            const windowTitle = parts.slice(1).join(' ');

                            if (projectName && windowTitle.toLowerCase().includes(projectName.toLowerCase())) {
                                // Need to find workspace for this window
                                const winInfo = allWindows.find(w => w.windowId === windowId);
                                targetWindow = {
                                    windowId,
                                    workspace: winInfo?.workspace || '1',
                                    bundleId,
                                    title: windowTitle
                                };
                                this.log('FOCUS-JB', `Found matching window (fallback): ${windowId} - ${windowTitle}`);
                                break;
                            }
                        }
                    }

                    if (!targetWindow && lines.length > 0) {
                        const firstLine = lines[0].trim().split(' ');
                        if (firstLine.length > 0) {
                            const windowId = firstLine[0];
                            const winInfo = allWindows.find(w => w.windowId === windowId);
                            targetWindow = {
                                windowId,
                                workspace: winInfo?.workspace || '1',
                                bundleId,
                                title: 'Unknown'
                            };
                            this.log('FOCUS-JB', `Using first window (fallback): ${windowId}`);
                        }
                    }

                    if (targetWindow) {
                        break;
                    }
                } catch (e) {
                    // Continue to next bundle ID
                }
            }
        }

        // Focus the window with workspace switch
        if (targetWindow) {
            try {
                // Step 1: Switch to the workspace where the window is
                this.log('FOCUS-JB', `Switching to workspace ${targetWindow.workspace}`);
                await execAsync(`aerospace workspace "${targetWindow.workspace}"`);

                // Small delay to let workspace switch complete
                await new Promise(resolve => setTimeout(resolve, 150));

                // Step 2: Focus the specific window
                await execAsync(`aerospace focus --window-id "${targetWindow.windowId}"`);
                this.log('FOCUS-JB', `Focused window ${targetWindow.windowId} in workspace ${targetWindow.workspace}`);
                return true;
            } catch (e) {
                this.log('FOCUS-JB', `Failed to focus window: ${e}`);
            }
        }

        // Ultimate fallback: try app-level focus
        for (const bundleId of bundleIds) {
            try {
                await execAsync(`aerospace focus --app-bundle-id "${bundleId}"`);
                this.log('FOCUS-JB', `Focused app ${bundleId} (fallback)`);
                return true;
            } catch (e) {
                // Continue to next bundle ID
            }
        }

        return false;
    }

    private focusJetBrainsWithAppleScript(): void {
        this.log('FOCUS-JB', 'Trying AppleScript for JetBrains IDE');

        const ideNames = [
            'PyCharm',
            'IntelliJ IDEA',
            'WebStorm',
            'Rider',
            'GoLand',
            'RubyMine',
            'CLion',
            'DataGrip',
            'PhpStorm',
            'AppCode'
        ];

        for (const ideName of ideNames) {
            try {
                const script = `tell application "${ideName}" to activate`;
                execSync(`osascript -e '${script}'`, { stdio: 'ignore' });
                this.log('FOCUS-JB', `AppleScript activated ${ideName}`);

                // Also set frontmost via System Events
                const frontScript = `tell application "System Events" to tell process "${ideName}" to set frontmost to true`;
                execSync(`osascript -e '${frontScript}'`, { stdio: 'ignore' });

                return;
            } catch (e) {
                // Try next IDE name
            }
        }

        this.log('FOCUS-JB', 'AppleScript focus failed for all IDE names');
    }

    // PHASE 1: Discovery server on port 3000 - only when looking for a pair
    private startDiscoveryServer() {
        if (this.discoveryWss || this.isConnected) {
            this.log('DISCOVERY', 'Discovery server already running or already connected');
            return;
        }
        try {
            this.discoveryWss = new WebSocketServer({ port: this.DISCOVERY_PORT });
            this.log('DISCOVERY', `Discovery server started on port ${this.DISCOVERY_PORT}`);
            vscode.window.showInformationMessage(`IDE Sync: Discovery server started on port ${this.DISCOVERY_PORT}. Waiting for JetBrains IDE...`);

            this.discoveryWss.on('connection', (ws: WebSocket, request) => {
                const clientType = request.url?.slice(1);
                this.log('DISCOVERY', `New connection on discovery port, clientType=${clientType}`);

                if (clientType === 'jetbrains') {
                    // Wait for hello message with projectPath before handling connection
                    ws.on('message', (data: RawData) => {
                        try {
                            const msg = JSON.parse(data.toString());
                            if (msg.type === 'hello' && msg.projectPath) {
                                const vscodeWorkspace = this.getWorkspacePath();
                                this.log('DISCOVERY', `JetBrains hello received: projectPath=${msg.projectPath}, VS Code workspace=${vscodeWorkspace}`);

                                // Workspace validation disabled - accept any connection
                                vscode.window.showInformationMessage('IDE Sync: JetBrains IDE found! Establishing connection...');
                                this.handleDiscoveryConnection(ws);
                            }
                        } catch (error) {
                            this.log('DISCOVERY', `Error parsing hello message: ${error}`);
                            ws.close(1008, 'Invalid message');
                        }
                    });

                    // Timeout if no hello message received within 5 seconds
                    setTimeout(() => {
                        if (ws.readyState === WebSocket.OPEN && !this.isConnected) {
                            this.log('DISCOVERY', 'No hello message received, closing connection');
                            ws.close(1008, 'No hello message');
                        }
                    }, 5000);
                } else {
                    ws.close();
                }
            });

            this.discoveryWss.on('error', (error: any) => {
                if (error.code === 'EADDRINUSE') {
                    this.log('DISCOVERY', `Port ${this.DISCOVERY_PORT} in use, discovery server already running elsewhere`);
                    vscode.window.showErrorMessage(
                        `IDE Sync: Failed to start - port ${this.DISCOVERY_PORT} is already in use. ` +
                        `Another VS Code instance is running discovery server. ` +
                        `Wait for the other instance to establish pair, then try again.`
                    );
                } else {
                    this.log('ERROR', `Discovery server error: ${error.message}`);
                    vscode.window.showErrorMessage(`IDE Sync: Discovery server failed - ${error.message}`);
                }
                // Reset state since discovery server failed to start
                this.discoveryWss = null;
                this.autoReconnect = false;
                this.updateStatusBarItem();
            });
        } catch (error: any) {
            this.log('ERROR', `Failed to start discovery server: ${error.message}`);
            vscode.window.showErrorMessage(
                `IDE Sync: Failed to start discovery server - ${error.message}. ` +
                `Check if port ${this.DISCOVERY_PORT} is available.`
            );
            this.discoveryWss = null;
            this.autoReconnect = false;
            this.updateStatusBarItem();
        }
    }

    private stopDiscoveryServer() {
        if (this.discoveryWss) {
            this.log('DISCOVERY', 'Stopping discovery server (pair established)');
            vscode.window.showInformationMessage(`IDE Sync: Discovery server stopped on port ${this.DISCOVERY_PORT}. Pair established.`);
            this.discoveryWss.close();
            this.discoveryWss = null;
        }
    }

    public killDiscoveryServer() {
        if (this.discoveryWss) {
            this.log('DISCOVERY', 'Manually killing discovery server');
            this.discoveryWss.close();
            this.discoveryWss = null;
            vscode.window.showInformationMessage(`IDE Sync: Discovery server killed on port ${this.DISCOVERY_PORT}`);
        } else {
            vscode.window.showInformationMessage('IDE Sync: Discovery server is not running');
        }
    }

    private handleDiscoveryConnection(ws: WebSocket) {
        // Generate unique port for this connection based on persistent counter
        let tempPort = this.nextAvailablePort++;

        // Check if this port is already used by current pair - if so, use next port
        if (this.assignedPort === tempPort) {
            this.log('DISCOVERY', `Port ${tempPort} is already used by current pair, using next port ${this.nextAvailablePort}`);
            tempPort = this.nextAvailablePort++;
        }

        // Also check if port is in pending connections
        while (this.pendingConnections.has(tempPort)) {
            this.log('DISCOVERY', `Port ${tempPort} is already in pending connections, using next port ${this.nextAvailablePort}`);
            tempPort = this.nextAvailablePort++;
        }

        this.log('DISCOVERY', `Assigning port ${tempPort} to new connection (next will be ${this.nextAvailablePort})`);

        // Store pending connection
        const pending: PendingConnection = {
            ws,
            workspacePath: this.getWorkspacePath(),
            tempPort
        };
        this.pendingConnections.set(tempPort, pending);

        // Send port assignment to JetBrains
        const portAssignment: PortAssignmentMessage = {
            type: 'port-assignment',
            port: tempPort,
            workspacePath: this.getWorkspacePath()
        };

        this.log('DISCOVERY', `Sending port assignment: ${JSON.stringify(portAssignment)}`);
        ws.send(JSON.stringify(portAssignment));

        // Wait for JetBrains to connect to the new port
        this.setupDataServer(tempPort);

        // Close discovery connection after timeout, but keep pending connection
        // until JetBrains successfully connects to data server
        setTimeout(() => {
            if (ws.readyState === WebSocket.OPEN) {
                ws.close();
            }
            // Don't delete pending connection here - data server will clean it up
            // when JetBrains connects or after longer timeout
        }, 5000);

        // Clean up pending connection after 30 seconds if no connection
        setTimeout(() => {
            if (this.pendingConnections.has(tempPort)) {
                this.log('DISCOVERY', `Cleaning up stale pending connection for port ${tempPort}`);
                this.pendingConnections.delete(tempPort);
            }
        }, 30000);
    }

    // PHASE 2: Data server on assigned port
    private setupDataServer(port: number) {
        this.log('SERVER', `Setting up data server on port ${port}`);

        try {
            this.dataWss = new WebSocketServer({ port });
            this.assignedPort = port;
            this.startFileLogging(port);

            this.dataWss.on('connection', (ws: WebSocket, request) => {
                const clientType = request.url?.slice(1);
                this.log('CONNECT', `New connection on data server port ${port}, clientType=${clientType}`);

                // Data server only accepts /data endpoint, not /jetbrains
                if (clientType !== 'data') {
                    this.log('CONNECT', `Rejecting connection: invalid endpoint '${clientType}', expected 'data'`);
                    ws.close(1008, 'Invalid endpoint - use /data');
                    return;
                }

                // Validate that we have a pending connection for this port
                const pending = this.pendingConnections.get(port);
                if (!pending) {
                    this.log('CONNECT', `Rejecting connection: no pending connection for port ${port}`);
                    ws.close(1008, 'No pending connection');
                    return;
                }

                // Workspace validation disabled - accept any connection that has pending entry
                this.log('CONNECT', `Accepting connection on port ${port}`);

                // Connection validated - accept it and clean up pending
                this.log('CONNECT', `Connection validated for port ${port}, accepting`);
                this.pendingConnections.delete(port);

                if (this.jetbrainsClient) {
                    this.log('CONNECT', 'Closing existing JetBrains client before accepting new connection');
                    this.jetbrainsClient.close();
                }
                this.jetbrainsClient = ws;
                this.isConnected = true;
                this.updateStatusBarItem();
                vscode.window.showInformationMessage(`JetBrains IDE connected on port ${port}`);

                // Stop discovery server - we have a pair now
                this.stopDiscoveryServer();

                const hello: HelloMessage = {
                    type: 'hello',
                    workspacePath: this.getWorkspacePath()
                };
                this.log('HELLO', `Sending: ${JSON.stringify(hello)}`);
                ws.send(JSON.stringify(hello));
                this.updatePairInfo();

                ws.on('message', async (data: RawData) => {
                    const raw = data.toString();
                    this.log('RECV', `Raw: ${raw}`);
                    try {
                        const parsed = JSON.parse(raw);
                        if (parsed.type === 'hello') {
                            return;
                        }
                        if (parsed.action === 'focus') {
                            this.log('FOCUS', 'Focus request received from JetBrains');
                            // Try Aerospace first, then AppleScript
                            const focused = await this.focusWithAerospace();
                            if (!focused) {
                                await this.focusWithAppleScript();
                            }
                            return;
                        }
                        const state = parsed as SyncState;
                        if (state.source === 'vscode') {
                            return;
                        }
                        await this.handleIncomingState(state);
                    } catch (error) {
                        this.log('RECV', `Parse error: ${error}`);
                    }
                });

                ws.on('close', () => {
                    if (this.jetbrainsClient === ws) {
                        this.jetbrainsClient = null;
                        this.isConnected = false;
                        this.assignedPort = null;
                        this.updateStatusBarItem();
                        this.log('DISCONNECT', `JetBrains disconnected from port ${port}`);
                        vscode.window.showInformationMessage('IDE Sync: Pair disconnected. Click "Turn IDE Sync On" to find a new pair.');
                    }
                });

                ws.on('error', (error: Error) => {
                    this.log('ERROR', `WebSocket error on port ${port}: ${error.message}`);
                });
            });

            this.dataWss.on('listening', () => {
                this.log('SERVER', `Data server listening on port ${port}`);
                vscode.window.showInformationMessage(`IDE Sync: Data server ready on port ${port}. Waiting for JetBrains connection...`);
            });

            this.dataWss.on('error', (error: any) => {
                this.log('ERROR', `Data server error on port ${port}: ${error.message}`);
                if (error.code === 'EADDRINUSE') {
                    // Try next port from persistent counter
                    const nextPort = this.nextAvailablePort++;
                    this.log('SERVER', `Port ${port} in use, trying port ${nextPort}`);
                    vscode.window.showWarningMessage(`IDE Sync: Port ${port} is busy, trying port ${nextPort}...`);
                    setTimeout(() => this.setupDataServer(nextPort), 100);
                } else {
                    vscode.window.showErrorMessage(`IDE Sync: Data server error - ${error.message}`);
                }
            });
        } catch (error: any) {
            this.log('ERROR', `Failed to setup data server on port ${port}: ${error.message}`);
        }
    }

    private setupEditorListeners() {
        this.disposables.push(
            vscode.window.onDidChangeActiveTextEditor((editor) => {
                if (editor && !this.isHandlingExternalUpdate) {
                    const document = editor.document;
                    const position = editor.selection.active;
                    this.currentState = {
                        filePath: document.uri.fsPath,
                        line: position.line,
                        column: position.character,
                        source: 'vscode',
                        isActive: this.isActive
                    };
                }
            })
        );

        this.disposables.push(
            vscode.window.onDidChangeTextEditorSelection((event) => {
                if (event.textEditor === vscode.window.activeTextEditor && !this.isHandlingExternalUpdate) {
                    const document = event.textEditor.document;
                    const position = event.selections[0].active;
                    this.currentState = {
                        filePath: document.uri.fsPath,
                        line: position.line,
                        column: position.character,
                        source: 'vscode',
                        isActive: this.isActive
                    };
                }
            })
        );
    }

    private setupWindowListeners() {
        this.disposables.push(
            vscode.window.onDidChangeWindowState((e) => {
                this.isActive = e.focused;
                if (this.currentState) {
                    this.currentState = {
                        ...this.currentState,
                        isActive: this.isActive,
                        source: 'vscode'
                    };
                }
            })
        );
    }

    private isHandlingExternalUpdate = false;

    private async handleIncomingState(state: SyncState) {
        this.log('RECV', `Applying state: filePath=${state.filePath}, line=${state.line}, column=${state.column}`);

        try {
            this.isHandlingExternalUpdate = true;
            const uri = vscode.Uri.file(state.filePath);
            const document = await vscode.workspace.openTextDocument(uri);
            const editor = await vscode.window.showTextDocument(document, { preview: false });

            const position = new vscode.Position(state.line, state.column);
            editor.selection = new vscode.Selection(position, position);

            editor.revealRange(
                new vscode.Range(position, position),
                vscode.TextEditorRevealType.InCenter
            );
            this.log('RECV', 'State applied successfully');
        } catch (error) {
            this.log('RECV', `Error applying state: ${error}`);
            vscode.window.showErrorMessage(`Failed to open file: ${state.filePath}`);
        } finally {
            this.isHandlingExternalUpdate = false;
        }
    }

    /**
     * Focus VS Code/Cursor window using Aerospace CLI (macOS only)
     * Returns true if successful
     */
    private async focusWithAerospace(): Promise<boolean> {
        if (os.platform() !== 'darwin') {
            return false;
        }

        this.log('FOCUS', 'Trying Aerospace focus for VS Code/Cursor');

        try {
            // Check if aerospace is available
            await execAsync('which aerospace');
        } catch (e) {
            this.log('FOCUS', 'Aerospace not found, skipping');
            return false;
        }

        try {
            // Get workspace path for matching
            const workspacePath = this.getWorkspacePath();
            const projectName = path.basename(workspacePath);

            // Cursor bundle ID: com.todesktop.230313mzl4w4u92
            // VS Code bundle ID: com.microsoft.VSCode
            const bundleIds = [
                'com.todesktop.230313mzl4w4u92',  // Cursor
                'com.microsoft.VSCode'              // VS Code
            ];

            for (const bundleId of bundleIds) {
                try {
                    // List windows for this app
                    const { stdout: listOutput } = await execAsync(
                        `aerospace list-windows --app-bundle-id "${bundleId}" --format "%{window-id} %{window-title}"`
                    );

                    this.log('FOCUS', `Aerospace list-windows for ${bundleId}: ${listOutput.trim()}`);

                    if (!listOutput.trim()) {
                        continue;
                    }

                    // Parse windows
                    const lines = listOutput.trim().split('\n').filter(l => l.trim());
                    let targetWindowId: string | null = null;

                    for (const line of lines) {
                        const parts = line.trim().split(' ');
                        if (parts.length >= 2) {
                            const windowId = parts[0];
                            const windowTitle = parts.slice(1).join(' ');

                            this.log('FOCUS', `Checking window: id=${windowId}, title=${windowTitle}`);

                            // Match by project name
                            if (projectName && windowTitle.toLowerCase().includes(projectName.toLowerCase())) {
                                targetWindowId = windowId;
                                this.log('FOCUS', `Found matching window: ${windowId}`);
                                break;
                            }
                        }
                    }

                    // Use first window if no match
                    if (!targetWindowId && lines.length > 0) {
                        const firstLine = lines[0].trim().split(' ');
                        if (firstLine.length > 0) {
                            targetWindowId = firstLine[0];
                            this.log('FOCUS', `Using first window: ${targetWindowId}`);
                        }
                    }

                    // Focus the window
                    if (targetWindowId) {
                        await execAsync(`aerospace focus --window-id "${targetWindowId}"`);
                        this.log('FOCUS', `Successfully focused window ${targetWindowId}`);
                        return true;
                    }
                } catch (e) {
                    this.log('FOCUS', `Aerospace error for ${bundleId}: ${e}`);
                }
            }

            // Fallback: focus by app bundle ID
            for (const bundleId of bundleIds) {
                try {
                    await execAsync(`aerospace focus --app-bundle-id "${bundleId}"`);
                    this.log('FOCUS', `Focused app ${bundleId}`);
                    return true;
                } catch (e) {
                    // Try next bundle ID
                }
            }
        } catch (e) {
            this.log('FOCUS', `Aerospace focus failed: ${e}`);
        }

        return false;
    }

    /**
     * Activate window using standard macOS methods (osascript)
     */
    private async focusWithAppleScript(): Promise<void> {
        this.log('FOCUS', 'Trying AppleScript focus');

        try {
            // Try to activate Cursor first, then VS Code
            const appNames = ['Cursor', 'Visual Studio Code', 'Code'];

            for (const appName of appNames) {
                try {
                    const script = `tell application "${appName}" to activate`;
                    await execAsync(`osascript -e '${script}'`);
                    this.log('FOCUS', `AppleScript activated ${appName}`);

                    // Also set frontmost via System Events
                    const frontScript = `tell application "System Events" to tell process "${appName}" to set frontmost to true`;
                    await execAsync(`osascript -e '${frontScript}'`);

                    return;
                } catch (e) {
                    // Try next app name
                }
            }
        } catch (e) {
            this.log('FOCUS', `AppleScript focus failed: ${e}`);
        }
    }

    public dispose() {
        if (this.discoveryWss) {
            this.discoveryWss.close();
        }
        this.cleanupConnections();
        this.statusBarItem.dispose();
        this.outputChannel.dispose();
        this.disposables.forEach(d => d.dispose());
    }
}

let syncInstance: VSCodeJetBrainsSync | null = null;

export function activate(context: vscode.ExtensionContext) {
    syncInstance = new VSCodeJetBrainsSync(context);

    context.subscriptions.push(
        vscode.commands.registerCommand('vscode-jetbrains-sync.toggleAutoReconnect', () => {
            syncInstance?.toggleAutoReconnect();
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('vscode-jetbrains-sync.switchToPairedIDE', () => {
            syncInstance?.switchToPairedIDE();
        })
    );

    context.subscriptions.push({
        dispose: () => syncInstance?.dispose()
    });
}

export function deactivate() {
    syncInstance?.dispose();
}
