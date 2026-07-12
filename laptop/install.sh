#!/usr/bin/env bash
# Installs Pairbridge's laptop server as a systemd --user service that starts
# automatically at login and restarts if it crashes.
#
# Usage:
#   ./install.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_NAME="pairbridge"
UNIT_PATH="$HOME/.config/systemd/user/${SERVICE_NAME}.service"
PYTHON_BIN="$(command -v python3)"

echo "Installing Python dependencies..."
"$PYTHON_BIN" -m pip install --user --quiet -r "$SCRIPT_DIR/requirements.txt"

echo "Writing systemd user service to $UNIT_PATH..."
mkdir -p "$(dirname "$UNIT_PATH")"
cat > "$UNIT_PATH" <<EOF
[Unit]
Description=Pairbridge laptop file-sharing server
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$PYTHON_BIN $SCRIPT_DIR/server.py
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now "${SERVICE_NAME}.service"

echo
echo "Pairbridge server installed and running as a systemd user service."
echo "It will start automatically next time you log in."
echo
echo "  status:  systemctl --user status ${SERVICE_NAME}"
echo "  logs:    journalctl --user -u ${SERVICE_NAME} -f"
echo "  stop:    systemctl --user disable --now ${SERVICE_NAME}"
echo

# Give the service a moment to write its config on first run.
sleep 1
TOKEN=$("$PYTHON_BIN" -c "
import json
from pathlib import Path
config = json.loads((Path.home() / '.pairbridge' / 'config.json').read_text())
print(config['token'])
" 2>/dev/null || echo "(not started yet — check 'systemctl --user status pairbridge')")
echo "Pairing token: $TOKEN"
echo "Enter this (and this machine's LAN IP) in the Pairbridge Android app to pair."
