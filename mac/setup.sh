#!/bin/bash
# Claude Push Mac — 安装 & 开机自启
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PYTHON="/opt/anaconda3/bin/python3"
LABEL="com.flow.claudepush.mac"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"

# Install rumps
echo "Installing rumps..."
"$PYTHON" -m pip install -q rumps

# Stop existing instance
launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true

# Create LaunchAgent
mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        <string>${PYTHON}</string>
        <string>${SCRIPT_DIR}/claude_push_mac.py</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>StandardErrorPath</key>
    <string>/tmp/claude-push-mac.err</string>
    <key>StandardOutPath</key>
    <string>/tmp/claude-push-mac.log</string>
</dict>
</plist>
PLIST

# Load
launchctl bootstrap "gui/$(id -u)" "$PLIST"

echo ""
echo "✓ Claude Push Mac is running in the menu bar"
echo "  Auto-starts on login"
echo "  Receive port: 18081"
echo "  Files → ~/Downloads/ClaudePush/"
echo ""
echo "To uninstall:"
echo "  launchctl bootout gui/\$(id -u)/${LABEL}"
echo "  rm ${PLIST}"
