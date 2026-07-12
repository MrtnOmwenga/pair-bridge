#!/usr/bin/env bash
# Removes the Pairbridge systemd --user service installed by install.sh.
# Does not remove ~/.pairbridge/config.json (shared folders, token) or the
# installed Python packages.
set -euo pipefail

SERVICE_NAME="pairbridge"
UNIT_PATH="$HOME/.config/systemd/user/${SERVICE_NAME}.service"

systemctl --user disable --now "${SERVICE_NAME}.service" 2>/dev/null || true
rm -f "$UNIT_PATH"
systemctl --user daemon-reload

echo "Pairbridge service removed. Config at ~/.pairbridge/config.json was left in place."
