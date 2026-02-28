jetbrains-plugin:
	cd /Users/seliverstow/Desktop/switch-plugins-2/IDESync-VSCode-JetBrains/jetbrains-plugin && ./gradlew build --no-daemon

vscode-extension:
	cd /Users/seliverstow/Desktop/switch-plugins-2/IDESync-VSCode-JetBrains/vscode-extension && npm run build && npm run package

install-in-cursor:
    cursor --install-extension /Users/seliverstow/Desktop/switch-plugins-2/IDESync-VSCode-JetBrains/vscode-extension/vscode-jetbrains-sync-1.2.0.vsix

all: jetbrains-plugin vscode-extension