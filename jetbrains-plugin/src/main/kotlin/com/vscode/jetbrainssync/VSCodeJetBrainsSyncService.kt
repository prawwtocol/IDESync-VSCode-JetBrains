package com.vscode.jetbrainssync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI

data class EditorState(
    val filePath: String,
    val line: Int,
    val column: Int,
    val source: String? = "jetbrains",
    val isActive: Boolean = false,
    val action: String? = null
)

data class HelloMessage(
    val type: String,
    val workspacePath: String
)

data class PortAssignmentMessage(
    val type: String,
    val port: Int,
    val workspacePath: String
)

data class PairData(
    val projectPath: String,
    val port: Int,
    val cursorWorkspacePath: String?,
    val lastConnectedAt: String,
    val lastSwitchAt: String?,
    val updatedAt: String
)

@Service(Service.Level.PROJECT)
class VSCodeJetBrainsSyncService(private val project: Project) {
    private var webSocket: WebSocketClient? = null
    private var discoverySocket: WebSocketClient? = null
    private var isActive = false
    private var isHandlingExternalUpdate = false
    private var isConnected = false
    private var isReconnecting = false
    private var autoReconnect = false
    private var cursorWorkspacePath: String? = null
    private var assignedPort: Int? = null
    private var currentState: EditorState? = null
    private val gson = Gson()
    private val log: Logger = Logger.getInstance(VSCodeJetBrainsSyncService::class.java)

    companion object {
        const val DISCOVERY_PORT = 3000
    }

    init {
        log.info("[INIT] Initializing VSCodeJetBrainsSyncService, projectPath=${project.basePath}")
        setupStatusBar()
        startDiscoveryPhase()
        setupEditorListeners()
        setupWindowListeners()
        loadPairs()
    }

    // Public methods for SyncStatusBarWidget
    fun isConnected(): Boolean = isConnected
    fun isReconnecting(): Boolean = isReconnecting
    fun isAutoReconnectEnabled(): Boolean = autoReconnect
    fun getAssignedPort(): Int? = assignedPort

    private fun log(action: String, details: String) {
        val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME).take(12)
        log.info("[$timestamp] [$action] $details")
    }

    private fun getPairsDir(): File {
        val configPath = PathManager.getConfigPath()
        val dir = File(configPath, "ide-sync")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getPairsFile(): File = File(getPairsDir(), "pairs.json")

    private fun loadPairs() {
        try {
            val file = getPairsFile()
            if (file.exists()) {
                val data = gson.fromJson(file.readText(), PairData::class.java)
                log("PERSIST", "Loaded pair from ${file.absolutePath}: $data")
            }
        } catch (e: Exception) {
            log("PERSIST", "Error loading pairs: ${e.message}")
        }
    }

    private fun savePairs(isSwitch: Boolean = false) {
        try {
            val file = getPairsFile()
            val data = PairData(
                projectPath = project.basePath ?: "",
                port = assignedPort ?: DISCOVERY_PORT,
                cursorWorkspacePath = cursorWorkspacePath,
                lastConnectedAt = java.time.Instant.now().toString(),
                lastSwitchAt = if (isSwitch) java.time.Instant.now().toString() else null,
                updatedAt = java.time.Instant.now().toString()
            )
            file.writeText(gson.toJson(data))
            log("PERSIST", "Saved pair to ${file.absolutePath}: $data")
        } catch (e: Exception) {
            log("PERSIST", "Error saving pairs: ${e.message}")
        }
    }

    private fun setupStatusBar() {
        log("INIT", "Setting up status bar widget")
        ApplicationManager.getApplication().invokeLater {
            log("INIT", "Creating status bar widget via factory")
            val widget = SyncStatusBarWidgetFactory().createWidget(project)
            log("INIT", "Status bar widget created: ${widget.ID()}")
            // Widget will be added to status bar automatically by IntelliJ
        }
    }

    private fun updateStatusBarWidget() {
        ApplicationManager.getApplication().invokeLater {
            val statusBar = WindowManager.getInstance().getStatusBar(project)
            val widget = statusBar?.getWidget(SyncStatusBarWidget.ID) as? SyncStatusBarWidget
            widget?.updateUI()
        }
    }

    // PHASE 1: Discovery - connect to port 3000 to get assigned port
    private fun startDiscoveryPhase() {
        if (!autoReconnect) {
            log("DISCOVERY", "Auto-reconnect disabled, skipping discovery")
            return
        }

        // Close and cleanup any existing discovery socket to prevent reuse errors
        if (discoverySocket != null) {
            log("DISCOVERY", "Cleaning up existing discovery socket")
            try {
                discoverySocket?.close()
            } catch (e: Exception) {
                log("DISCOVERY", "Error closing existing socket: ${e.message}")
            }
            discoverySocket = null
        }

        isReconnecting = true
        updateStatusBarWidget()
        log("DISCOVERY", "Starting discovery phase, connecting to port $DISCOVERY_PORT")

        try {
            discoverySocket = object : WebSocketClient(URI("ws://localhost:${DISCOVERY_PORT}/jetbrains")) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    log("DISCOVERY", "Connected to discovery server on port $DISCOVERY_PORT")
                    // Send projectPath for workspace validation
                    val helloMsg = mapOf("type" to "hello", "projectPath" to project.basePath)
                    send(gson.toJson(helloMsg))
                    log("DISCOVERY", "Sent hello message with projectPath=${project.basePath}")
                }

                override fun onMessage(message: String?) {
                    log("DISCOVERY", "Received: $message")
                    message?.let {
                        try {
                            val json = gson.fromJson(it, JsonObject::class.java)
                            if (json.has("type") && json.get("type").asString == "port-assignment") {
                                val assignedPort = json.get("port").asInt
                                val workspacePath = json.get("workspacePath")?.asString
                                log("DISCOVERY", "Received port assignment: port=$assignedPort, workspacePath=$workspacePath")

                                // Close discovery connection
                                discoverySocket?.close()
                                discoverySocket = null

                                // Connect to assigned port
                                connectToDataServer(assignedPort, workspacePath)
                            }
                        } catch (e: Exception) {
                            log("DISCOVERY", "Error parsing port assignment: ${e.message}")
                        }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    log("DISCOVERY", "Discovery connection closed: code=$code, reason=$reason")
                    // If we didn't get port assignment, retry discovery
                    if (assignedPort == null && autoReconnect) {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            Thread.sleep(3000)
                            ApplicationManager.getApplication().invokeLater {
                                startDiscoveryPhase()
                            }
                        }
                    }
                }

                override fun onError(ex: Exception?) {
                    log("DISCOVERY", "Discovery error: ${ex?.message}")
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("VSCode JetBrains Sync")
                        .createNotification("Cannot connect to VSCode discovery server (port 3000). Make sure VSCode sync is enabled.", NotificationType.WARNING)
                        .notify(project)
                    isReconnecting = false
                    updateStatusBarWidget()
                    // Retry discovery after delay
                    ApplicationManager.getApplication().executeOnPooledThread {
                        Thread.sleep(10000) // Increased to 10 seconds to prevent rapid retries
                        ApplicationManager.getApplication().invokeLater {
                            if (autoReconnect && assignedPort == null) {
                                log("DISCOVERY", "Retrying discovery after error...")
                                startDiscoveryPhase()
                            }
                        }
                    }
                }
            }

            discoverySocket?.connectionLostTimeout = 0
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = discoverySocket?.connectBlocking()
                log("DISCOVERY", "Discovery connect result: $result")
            }
        } catch (e: Exception) {
            log("DISCOVERY", "Error starting discovery: ${e.message}")
            isReconnecting = false
            updateStatusBarWidget()
        }
    }

    // PHASE 2: Data connection - connect to assigned port for real communication
    private fun connectToDataServer(port: Int, workspacePath: String?) {
        log("CONNECT", "Connecting to data server on port $port")

        // Close and cleanup any existing WebSocket to prevent reuse errors
        if (webSocket != null) {
            log("CONNECT", "Cleaning up existing WebSocket")
            try {
                webSocket?.close()
            } catch (e: Exception) {
                log("CONNECT", "Error closing existing WebSocket: ${e.message}")
            }
            webSocket = null
        }

        this.assignedPort = port
        this.cursorWorkspacePath = workspacePath

        try {
            // Data server uses /data endpoint (different from discovery server's /jetbrains)
            webSocket = object : WebSocketClient(URI("ws://localhost:${port}/data")) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    log("CONNECT", "Successfully connected to VSCode data server on port $port")
                    isConnected = true
                    isReconnecting = false
                    updateStatusBarWidget()
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("VSCode JetBrains Sync")
                        .createNotification("Connected to VSCode on port $port", NotificationType.INFORMATION)
                        .notify(project)
                    savePairs()
                }

                override fun onMessage(message: String?) {
                    log("RECV", "Raw: $message")
                    message?.let {
                        try {
                            val json = gson.fromJson(it, JsonObject::class.java)
                            when {
                                json.has("type") && json.get("type").asString == "hello" -> {
                                    cursorWorkspacePath = json.get("workspacePath")?.asString
                                    log("HELLO", "Received workspacePath=$cursorWorkspacePath")
                                    savePairs()
                                }
                                json.has("action") && json.get("action").asString == "focus" -> {
                                    log("RECV", "Focus request received, bringing JetBrains window to front")
                                    ApplicationManager.getApplication().invokeLater {
                                        bringWindowToFront()
                                    }
                                }
                                else -> {
                                    val state = gson.fromJson(it, EditorState::class.java)
                                    if (state.source != "jetbrains") {
                                        handleIncomingState(state)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            log("RECV", "Parse error: ${e.message}, stack: ${e.stackTraceToString()}")
                        }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    log("DISCONNECT", "Disconnected from VSCode data server. Code: $code, Reason: $reason, Remote: $remote")
                    isConnected = false
                    updateStatusBarWidget()
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("VSCode JetBrains Sync")
                        .createNotification("Disconnected from VSCode", NotificationType.WARNING)
                        .notify(project)

                    // If still in auto-reconnect mode, restart discovery
                    if (autoReconnect && !isReconnecting) {
                        assignedPort = null
                        isReconnecting = true
                        updateStatusBarWidget()
                        ApplicationManager.getApplication().executeOnPooledThread {
                            Thread.sleep(5000)
                            ApplicationManager.getApplication().invokeLater {
                                startDiscoveryPhase()
                            }
                        }
                    }
                }

                override fun onError(ex: Exception?) {
                    log("ERROR", "WebSocket error on port $port: ${ex?.message}, stack: ${ex?.stackTraceToString()}")
                    isConnected = false
                    updateStatusBarWidget()
                }
            }

            webSocket?.connectionLostTimeout = 0
            ApplicationManager.getApplication().executeOnPooledThread {
                log("CONNECT", "Connecting to data server on port $port...")
                val result = webSocket?.connectBlocking()
                log("CONNECT", "Data server connect result: $result")
            }
        } catch (e: Exception) {
            log("ERROR", "Error connecting to data server: ${e.message}")
            // If data connection fails, restart discovery
            if (autoReconnect) {
                assignedPort = null
                ApplicationManager.getApplication().executeOnPooledThread {
                    Thread.sleep(3000)
                    ApplicationManager.getApplication().invokeLater {
                        startDiscoveryPhase()
                    }
                }
            }
        }
    }

    fun switchToPairedIDE() {
        log("CMD-I", "switchToPairedIDE called")
        if (!isConnected || webSocket?.isOpen != true) {
            log("CMD-I", "Not connected to VSCode, cannot switch")
            return
        }

        try {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            if (editor == null || file == null) {
                log("CMD-I", "No active editor or file")
                return
            }

            val caret = editor.caretModel.primaryCaret
            val state = EditorState(
                filePath = file.path,
                line = caret.logicalPosition.line,
                column = caret.logicalPosition.column,
                source = "jetbrains",
                action = "switch"
            )

            val stateJson = gson.toJson(state)
            log("CMD-I", "Sending state: $stateJson")
            webSocket?.send(stateJson)
            savePairs(isSwitch = true)

            log("CMD-I", "Sending focus request to VSCode")
            val focusRequest = JsonObject().apply { addProperty("action", "focus") }
            webSocket?.send(focusRequest.toString())

            log("CMD-I", "Calling focusCursorWindow with workspacePath=$cursorWorkspacePath")
            focusCursorWindow(cursorWorkspacePath)
        } catch (e: Exception) {
            log("CMD-I", "Error: ${e.message}, stack: ${e.stackTraceToString()}")
        }
    }

    /**
     * Focus Cursor window using Aerospace CLI with workspace awareness
     * Returns true if successful, false if Aerospace is not installed or focus failed
     */
    private fun focusCursorWithAerospace(workspacePath: String?): Boolean {
        log("FOCUS", "Trying Aerospace focus with workspace awareness, workspacePath=$workspacePath")

        // First, check if aerospace is available
        val checkProcess = Runtime.getRuntime().exec(arrayOf("which", "aerospace"))
        checkProcess.waitFor()
        if (checkProcess.exitValue() != 0) {
            log("FOCUS", "Aerospace not found (which aerospace failed)")
            return false
        }

        // Get project name from workspace path for matching
        val projectName = workspacePath?.substringAfterLast(File.separator) ?: ""
        if (projectName.isBlank()) {
            log("FOCUS", "Aerospace: no project name available, falling back to app-level focus")
            return focusCursorAppWithAerospace()
        }

        // Step 1: Get ALL windows across ALL workspaces with workspace info
        // Format: window-id workspace app-bundle-id window-title
        val allWindowsCmd = arrayOf(
            "aerospace", "list-windows",
            "--all",
            "--format", "%{window-id} %{workspace} %{app-bundle-id} %{window-title}"
        )
        log("FOCUS", "Executing: ${allWindowsCmd.joinToString(" ")}")

        val allWindowsProcess = Runtime.getRuntime().exec(allWindowsCmd)
        val allWindowsOutput = allWindowsProcess.inputStream.bufferedReader().readText()
        val allWindowsExitCode = allWindowsProcess.waitFor()

        log("FOCUS", "aerospace list-windows --all exit code: $allWindowsExitCode")

        // Data class to hold window info
        data class WindowInfo(val windowId: String, val workspace: String, val bundleId: String, val title: String)
        val allWindows = mutableListOf<WindowInfo>()

        if (allWindowsExitCode == 0 && allWindowsOutput.isNotBlank()) {
            val lines = allWindowsOutput.lines().filter { it.isNotBlank() }
            for (line in lines) {
                // Parse: window-id workspace bundle-id title (title may have spaces)
                val regex = "^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(.+)$".toRegex()
                val match = regex.find(line.trim())
                if (match != null) {
                    allWindows.add(WindowInfo(
                        windowId = match.groupValues[1],
                        workspace = match.groupValues[2],
                        bundleId = match.groupValues[3],
                        title = match.groupValues[4]
                    ))
                }
            }
            log("FOCUS", "Found ${allWindows.size} total windows across all workspaces")
        }

        // Step 2: Find matching Cursor window
        val cursorBundleIds = listOf(
            "com.todesktop.230313mzl4w4u92",  // Cursor
            "com.microsoft.VSCode"              // VS Code
        )

        var targetWindow: WindowInfo? = null

        // First try: match by project name in title
        for (win in allWindows) {
            if (cursorBundleIds.any { win.bundleId == it || win.bundleId.startsWith(it) }) {
                if (win.title.contains(projectName, ignoreCase = true)) {
                    targetWindow = win
                    log("FOCUS", "Found matching window: id=${win.windowId}, workspace=${win.workspace}, title=${win.title}")
                    break
                }
            }
        }

        // Second try: any Cursor/VS Code window
        if (targetWindow == null) {
            for (win in allWindows) {
                if (cursorBundleIds.any { win.bundleId == it || win.bundleId.startsWith(it) }) {
                    targetWindow = win
                    log("FOCUS", "Using first Cursor/VS Code window: id=${win.windowId}, workspace=${win.workspace}, title=${win.title}")
                    break
                }
            }
        }

        // Third try: old method - per-bundle search
        if (targetWindow == null) {
            for (bundleId in cursorBundleIds) {
                val listCmd = arrayOf(
                    "aerospace", "list-windows",
                    "--app-bundle-id", bundleId,
                    "--format", "%{window-id} %{window-title}"
                )
                try {
                    val listProcess = Runtime.getRuntime().exec(listCmd)
                    val output = listProcess.inputStream.bufferedReader().readText()
                    val exitCode = listProcess.waitFor()

                    if (exitCode == 0 && output.isNotBlank()) {
                        val lines = output.lines().filter { it.isNotBlank() }
                        for (line in lines) {
                            val parts = line.trim().split(" ", limit = 2)
                            if (parts.size >= 2) {
                                val windowId = parts[0]
                                val windowTitle = parts[1]

                                if (windowTitle.contains(projectName, ignoreCase = true)) {
                                    // Find workspace from allWindows
                                    val workspace = allWindows.find { it.windowId == windowId }?.workspace ?: "1"
                                    targetWindow = WindowInfo(windowId, workspace, bundleId, windowTitle)
                                    log("FOCUS", "Found matching window (fallback): id=$windowId, workspace=$workspace")
                                    break
                                }
                            }
                        }

                        if (targetWindow == null && lines.isNotEmpty()) {
                            val firstLine = lines.first().trim().split(" ", limit = 2)
                            if (firstLine.isNotEmpty()) {
                                val windowId = firstLine[0]
                                val workspace = allWindows.find { it.windowId == windowId }?.workspace ?: "1"
                                targetWindow = WindowInfo(windowId, workspace, bundleId, "Unknown")
                                log("FOCUS", "Using first window (fallback): id=$windowId, workspace=$workspace")
                            }
                        }

                        if (targetWindow != null) break
                    }
                } catch (e: Exception) {
                    // Continue to next bundle ID
                }
            }
        }

        // Step 3: Focus with workspace switch
        if (targetWindow != null) {
            try {
                // Switch to the workspace first
                val workspaceCmd = arrayOf("aerospace", "workspace", targetWindow.workspace)
                log("FOCUS", "Executing: ${workspaceCmd.joinToString(" ")}")
                val workspaceProcess = Runtime.getRuntime().exec(workspaceCmd)
                val workspaceExitCode = workspaceProcess.waitFor()
                log("FOCUS", "aerospace workspace exit code: $workspaceExitCode")

                // Small delay for workspace switch
                Thread.sleep(150)

                // Focus the window
                val focusCmd = arrayOf("aerospace", "focus", "--window-id", targetWindow.windowId)
                log("FOCUS", "Executing: ${focusCmd.joinToString(" ")}")
                val focusProcess = Runtime.getRuntime().exec(focusCmd)
                val focusExitCode = focusProcess.waitFor()
                log("FOCUS", "aerospace focus exit code: $focusExitCode")

                if (focusExitCode == 0) {
                    return true
                }
            } catch (e: Exception) {
                log("FOCUS", "Aerospace workspace/window focus failed: ${e.message}")
            }
        }

        log("FOCUS", "Aerospace window focus with workspace failed, trying app-level focus")
        return focusCursorAppWithAerospace()
    }

    /**
     * Focus Cursor at app level using Aerospace (when window-specific focus fails)
     */
    private fun focusCursorAppWithAerospace(): Boolean {
        log("FOCUS", "Trying Aerospace app-level focus for Cursor")

        return try {
            // Try to focus by app-bundle-id
            val focusCmd = arrayOf("aerospace", "focus", "--app-bundle-id", "com.todesktop.230313mzl4w4u92")
            log("FOCUS", "Executing: ${focusCmd.joinToString(" ")}")
            val focusProcess = Runtime.getRuntime().exec(focusCmd)
            val exitCode = focusProcess.waitFor()
            log("FOCUS", "aerospace focus app exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            log("FOCUS", "Aerospace app-level focus failed: ${e.message}")
            false
        }
    }

    private fun focusCursorWindow(workspacePath: String?) {
        val os = System.getProperty("os.name").lowercase()
        log("FOCUS", "focusCursorWindow called, os=$os, workspacePath=$workspacePath")

        if (os.contains("mac")) {
            // Strategy 1: Aerospace window manager (if installed)
            // This is the most reliable method when using Aerospace
            try {
                if (focusCursorWithAerospace(workspacePath)) {
                    log("FOCUS", "Successfully activated Cursor with Aerospace")
                    return
                }
            } catch (e: Exception) {
                log("FOCUS", "Aerospace focus failed: ${e.message}, trying other methods")
            }

            // Strategy 2: Use 'open' command with workspace path
            // This activates existing window with this project or opens new one
            if (!workspacePath.isNullOrBlank()) {
                try {
                    val openCmd = arrayOf("open", "-a", "Cursor", workspacePath)
                    log("FOCUS", "Executing: ${openCmd.joinToString(" ")}")
                    val process = Runtime.getRuntime().exec(openCmd)
                    val exitCode = process.waitFor()
                    log("FOCUS", "open command exit code: $exitCode")

                    if (exitCode == 0) {
                        // Give Cursor time to activate, then ensure it's frontmost
                        Thread.sleep(300)
                        bringCursorToFrontAppleScript()
                        log("FOCUS", "Successfully activated Cursor with open command")
                        return
                    }
                } catch (e: Exception) {
                    log("FOCUS", "open command failed: ${e.message}, trying AppleScript fallback")
                }
            }

            // Strategy 3: AppleScript with click-based window activation
            try {
                activateCursorWindowAppleScript(workspacePath)
                return
            } catch (e: Exception) {
                log("FOCUS", "AppleScript failed: ${e.message}, using simple activate")
            }

            // Strategy 4: Simple activate as last resort
            try {
                Runtime.getRuntime().exec(arrayOf("osascript", "-e", "tell application \"Cursor\" to activate"))
                log("FOCUS", "Simple activate executed")
            } catch (e: Exception) {
                log("FOCUS", "Simple activate also failed: ${e.message}")
            }
        } else {
            log("FOCUS", "Non-macOS: using platform-specific activation")
            try {
                if (os.contains("win")) {
                    if (!workspacePath.isNullOrBlank()) {
                        Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "cursor", workspacePath))
                    } else {
                        Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "cursor"))
                    }
                } else {
                    // Linux: try to activate by window title or use xdg-open
                    if (!workspacePath.isNullOrBlank()) {
                        Runtime.getRuntime().exec(arrayOf("xdg-open", workspacePath))
                    } else {
                        Runtime.getRuntime().exec(arrayOf("xdg-open", "cursor:"))
                    }
                }
            } catch (e: Exception) {
                log("FOCUS", "Error: ${e.message}")
            }
        }
    }

    private fun activateCursorWindowAppleScript(workspacePath: String?) {
        val escapedPath = workspacePath?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
        val projectName = workspacePath?.substringAfterLast(File.separator) ?: ""

        val script = if (!workspacePath.isNullOrBlank()) {
            """
            tell application "Cursor" to activate
            delay 0.2
            tell application "System Events"
                tell process "Cursor"
                    set frontmost to true
                    -- Try to find window by project name first (more reliable than full path)
                    set wins to every window
                    repeat with win in wins
                        set winName to name of win
                        if winName contains "$projectName" or winName contains "$escapedPath" then
                            perform action "AXRaise" of win
                            click win
                            set value of attribute "AXMain" of win to true
                            return
                        end if
                    end repeat
                    -- If no matching window found, just ensure frontmost
                    set frontmost to true
                end tell
            end tell
            """.trimIndent()
        } else {
            """
            tell application "Cursor" to activate
            delay 0.1
            tell application "System Events"
                tell process "Cursor"
                    set frontmost to true
                end tell
            end tell
            """.trimIndent()
        }

        log("FOCUS", "Executing AppleScript (truncated): ${script.take(150)}...")
        val process = Runtime.getRuntime().exec(arrayOf("osascript", "-e", script))
        val exitCode = process.waitFor()
        log("FOCUS", "AppleScript exit code: $exitCode")

        if (exitCode != 0) {
            throw RuntimeException("AppleScript failed with exit code $exitCode")
        }
    }

    private fun bringCursorToFrontAppleScript() {
        val script = """
        tell application "System Events"
            tell process "Cursor"
                set frontmost to true
            end tell
        end tell
        """.trimIndent()

        try {
            Runtime.getRuntime().exec(arrayOf("osascript", "-e", script))
            log("FOCUS", "bringCursorToFrontAppleScript executed")
        } catch (e: Exception) {
            log("FOCUS", "bringCursorToFrontAppleScript failed: ${e.message}")
        }
    }

    /**
     * Bring JetBrains IDE window to front using Aerospace with workspace awareness
     * Returns true if successful
     */
    private fun bringWindowToFrontWithAerospace(): Boolean {
        log("FOCUS", "Trying Aerospace for JetBrains IDE with workspace awareness")

        // Check if aerospace is available
        try {
            val checkProcess = Runtime.getRuntime().exec(arrayOf("which", "aerospace"))
            checkProcess.waitFor()
            if (checkProcess.exitValue() != 0) {
                return false
            }
        } catch (e: Exception) {
            return false
        }

        // Get project name for matching
        val projectName = project.name
        log("FOCUS", "Aerospace: looking for window with project name: $projectName")

        // Get bundle IDs for common JetBrains IDEs
        val bundleIds = listOf(
            "com.jetbrains.pycharm",     // PyCharm
            "com.jetbrains.intellij",    // IntelliJ IDEA
            "com.jetbrains.goland",      // GoLand
            "com.jetbrains.rubymine",    // RubyMine
            "com.jetbrains.webstorm",    // WebStorm
            "com.jetbrains.clion",       // CLion
            "com.jetbrains.datagrip",    // DataGrip
            "com.jetbrains.phpstorm",    // PhpStorm
            "com.jetbrains.rider",       // Rider
            "com.jetbrains.appcode",     // AppCode
            "com.jetbrains.ide"          // Generic
        )

        // Step 1: Get ALL windows across ALL workspaces with workspace info
        data class WindowInfo(val windowId: String, val workspace: String, val bundleId: String, val title: String)
        val allWindows = mutableListOf<WindowInfo>()

        try {
            val allWindowsCmd = arrayOf(
                "aerospace", "list-windows",
                "--all",
                "--format", "%{window-id} %{workspace} %{app-bundle-id} %{window-title}"
            )
            val allWindowsProcess = Runtime.getRuntime().exec(allWindowsCmd)
            val allWindowsOutput = allWindowsProcess.inputStream.bufferedReader().readText()
            val allWindowsExitCode = allWindowsProcess.waitFor()

            if (allWindowsExitCode == 0 && allWindowsOutput.isNotBlank()) {
                val lines = allWindowsOutput.lines().filter { it.isNotBlank() }
                for (line in lines) {
                    val regex = "^(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(.+)$".toRegex()
                    val match = regex.find(line.trim())
                    if (match != null) {
                        allWindows.add(WindowInfo(
                            windowId = match.groupValues[1],
                            workspace = match.groupValues[2],
                            bundleId = match.groupValues[3],
                            title = match.groupValues[4]
                        ))
                    }
                }
                log("FOCUS", "Found ${allWindows.size} total windows across all workspaces")
            }
        } catch (e: Exception) {
            log("FOCUS", "Failed to list all windows: ${e.message}")
        }

        // Step 2: Find matching JetBrains window
        var targetWindow: WindowInfo? = null

        // First try: match by project name in title
        for (win in allWindows) {
            if (bundleIds.any { win.bundleId == it || win.bundleId.startsWith(it) }) {
                if (win.title.contains(projectName, ignoreCase = true)) {
                    targetWindow = win
                    log("FOCUS", "Found matching IDE window: id=${win.windowId}, workspace=${win.workspace}, title=${win.title}")
                    break
                }
            }
        }

        // Second try: any JetBrains window
        if (targetWindow == null) {
            for (win in allWindows) {
                if (bundleIds.any { win.bundleId == it || win.bundleId.startsWith(it) }) {
                    targetWindow = win
                    log("FOCUS", "Using first IDE window: id=${win.windowId}, workspace=${win.workspace}, title=${win.title}")
                    break
                }
            }
        }

        // Third try: old method - per-bundle search
        if (targetWindow == null) {
            for (bundleId in bundleIds) {
                try {
                    val listCmd = arrayOf(
                        "aerospace", "list-windows",
                        "--app-bundle-id", bundleId,
                        "--format", "%{window-id} %{window-title}"
                    )
                    val listProcess = Runtime.getRuntime().exec(listCmd)
                    val output = listProcess.inputStream.bufferedReader().readText()
                    val exitCode = listProcess.waitFor()

                    if (exitCode == 0 && output.isNotBlank()) {
                        val lines = output.lines().filter { it.isNotBlank() }
                        for (line in lines) {
                            val parts = line.trim().split(" ", limit = 2)
                            if (parts.size >= 2) {
                                val windowId = parts[0]
                                val windowTitle = parts[1]

                                if (windowTitle.contains(projectName, ignoreCase = true)) {
                                    val workspace = allWindows.find { it.windowId == windowId }?.workspace ?: "1"
                                    targetWindow = WindowInfo(windowId, workspace, bundleId, windowTitle)
                                    log("FOCUS", "Found matching IDE window (fallback): id=$windowId, workspace=$workspace")
                                    break
                                }
                            }
                        }

                        if (targetWindow == null && lines.isNotEmpty()) {
                            val firstLine = lines.first().trim().split(" ", limit = 2)
                            if (firstLine.isNotEmpty()) {
                                val windowId = firstLine[0]
                                val workspace = allWindows.find { it.windowId == windowId }?.workspace ?: "1"
                                targetWindow = WindowInfo(windowId, workspace, bundleId, "Unknown")
                                log("FOCUS", "Using first IDE window (fallback): id=$windowId, workspace=$workspace")
                            }
                        }

                        if (targetWindow != null) break
                    }
                } catch (e: Exception) {
                    log("FOCUS", "Aerospace error with bundle $bundleId: ${e.message}")
                }
            }
        }

        // Step 3: Focus with workspace switch
        if (targetWindow != null) {
            try {
                // Switch to the workspace first
                val workspaceCmd = arrayOf("aerospace", "workspace", targetWindow.workspace)
                log("FOCUS", "Executing: ${workspaceCmd.joinToString(" ")}")
                val workspaceProcess = Runtime.getRuntime().exec(workspaceCmd)
                val workspaceExitCode = workspaceProcess.waitFor()
                log("FOCUS", "aerospace workspace exit code: $workspaceExitCode")

                // Small delay for workspace switch
                Thread.sleep(150)

                // Focus the window
                val focusCmd = arrayOf("aerospace", "focus", "--window-id", targetWindow.windowId)
                log("FOCUS", "Executing: ${focusCmd.joinToString(" ")}")
                val focusProcess = Runtime.getRuntime().exec(focusCmd)
                val focusExitCode = focusProcess.waitFor()

                if (focusExitCode == 0) {
                    return true
                }
            } catch (e: Exception) {
                log("FOCUS", "Aerospace focus failed: ${e.message}")
            }
        }

        log("FOCUS", "Aerospace window focus failed for IDE")
        return false
    }

    private fun bringWindowToFront() {
        val os = System.getProperty("os.name").lowercase()
        log("FOCUS", "bringWindowToFront called, os=$os")

        val ideName = ApplicationNamesInfo.getInstance().productName
        log("FOCUS", "IDE name: $ideName")

        if (os.contains("mac")) {
            // Strategy 1: Try Aerospace if available
            if (bringWindowToFrontWithAerospace()) {
                log("FOCUS", "Successfully brought $ideName to front with Aerospace")
                return
            }

            // Strategy 2: AppleScript
            try {
                val script = """
                    tell application "$ideName" to activate
                    delay 0.1
                    tell application "System Events"
                        tell process "$ideName"
                            set frontmost to true
                        end tell
                    end tell
                """.trimIndent()
                log("FOCUS", "Executing AppleScript for $ideName")
                Runtime.getRuntime().exec(arrayOf("osascript", "-e", script))
                log("FOCUS", "AppleScript executed successfully")
            } catch (e: Exception) {
                log("FOCUS", "AppleScript error: ${e.message}, trying simple activate")
                try {
                    Runtime.getRuntime().exec(arrayOf("osascript", "-e", "tell application \"$ideName\" to activate"))
                } catch (e2: Exception) {
                    log("FOCUS", "Simple activate also failed: ${e2.message}")
                    WindowManager.getInstance().getFrame(project)?.toFront()
                }
            }
        } else {
            val frame = WindowManager.getInstance().getFrame(project)
            frame?.let {
                it.toFront()
                it.requestFocus()
                if (os.contains("win")) {
                    try {
                        val robot = java.awt.Robot()
                        robot.keyPress(java.awt.event.KeyEvent.VK_ALT)
                        robot.keyPress(java.awt.event.KeyEvent.VK_TAB)
                        robot.keyRelease(java.awt.event.KeyEvent.VK_TAB)
                        robot.keyRelease(java.awt.event.KeyEvent.VK_ALT)
                    } catch (e: Exception) {
                        log("FOCUS", "Robot error: ${e.message}")
                    }
                }
            }
        }
    }

    fun restartConnection() {
        log("RESTART", "Restarting connection")
        // Close existing connections
        webSocket?.close()
        discoverySocket?.close()
        webSocket = null
        discoverySocket = null
        isConnected = false
        assignedPort = null

        if (autoReconnect) {
            isReconnecting = true
            updateStatusBarWidget()
            startDiscoveryPhase()
        }
    }

    private var lastToggleTime = 0L
    
    fun toggleAutoReconnect() {
        val now = System.currentTimeMillis()
        if (now - lastToggleTime < 1500) { // 1.5 second debounce
            log("TOGGLE", "Toggle requested too fast, ignoring")
            return
        }
        lastToggleTime = now
        
        autoReconnect = !autoReconnect
        log("TOGGLE", "Auto-reconnect: $autoReconnect")

        if (!autoReconnect) {
            webSocket?.close()
            discoverySocket?.close()
            webSocket = null
            discoverySocket = null
            isConnected = false
            assignedPort = null
            updateStatusBarWidget()
            NotificationGroupManager.getInstance()
                .getNotificationGroup("VSCode JetBrains Sync")
                .createNotification("Sync disabled", NotificationType.INFORMATION)
                .notify(project)
        } else {
            updateStatusBarWidget()
            NotificationGroupManager.getInstance()
                .getNotificationGroup("VSCode JetBrains Sync")
                .createNotification("Sync enabled, starting discovery...", NotificationType.INFORMATION)
                .notify(project)
            startDiscoveryPhase()
        }
    }

    private fun handleIncomingState(state: EditorState) {
        log("RECV", "Applying state: filePath=${state.filePath}, line=${state.line}, column=${state.column}")

        ApplicationManager.getApplication().invokeLater {
            try {
                isHandlingExternalUpdate = true

                val virtualFile = LocalFileSystem.getInstance().findFileByPath(state.filePath)
                if (virtualFile != null) {
                    val fileEditorManager = FileEditorManager.getInstance(project)
                    val editors = fileEditorManager.openFile(virtualFile, true)

                    editors.forEach { editor ->
                        if (editor is TextEditor) {
                            val caretModel = editor.editor.caretModel
                            val logicalPosition = LogicalPosition(state.line, state.column)
                            caretModel.moveToLogicalPosition(logicalPosition)
                            caretModel.primaryCaret.moveToLogicalPosition(logicalPosition)
                            editor.editor.scrollingModel.scrollTo(logicalPosition, ScrollType.CENTER)
                        }
                    }
                    log("RECV", "State applied successfully")
                } else {
                    log("RECV", "File not found: ${state.filePath}")
                }
            } catch (e: Exception) {
                log("RECV", "Error applying state: ${e.message}, stack: ${e.stackTraceToString()}")
            } finally {
                isHandlingExternalUpdate = false
            }
        }
    }

    private fun setupEditorListeners() {
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (!isHandlingExternalUpdate && isConnected) {
                        val newFile = event.newFile
                        val editor = event.newEditor
                        if (newFile != null && editor is TextEditor) {
                            val caret = editor.editor.caretModel.primaryCaret
                            currentState = EditorState(
                                filePath = newFile.path,
                                line = caret.logicalPosition.line,
                                column = caret.logicalPosition.column,
                                source = "jetbrains",
                                isActive = isActive
                            )
                        }
                    }
                }
            }
        )
    }

    private fun setupWindowListeners() {
        val frame = WindowManager.getInstance().getFrame(project)
        frame?.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                isActive = true
            }
            override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                isActive = false
            }
        })
    }
}
