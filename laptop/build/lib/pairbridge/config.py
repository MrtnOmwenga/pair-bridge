"""Pairbridge's configuration file, ~/.pairbridge/config.json (or $PAIRBRIDGE_HOME/config.json).

Keys: "shared_roots" [{"id", "name", "path"}], "inbox" {"root", "path"} (where files sent from the
tablet land), "port", "token", "server_id", "max_upload_bytes", and an optional "name" that
overrides the PC name shown on the tablet. The server reads it once at startup.
"""

import json
import os
import re
import secrets
import socket
from collections.abc import Callable
from pathlib import Path

DEFAULT_PORT = 8765
DEFAULT_MAX_UPLOAD_BYTES = 4 * 1024**3
DEFAULT_INBOX_FOLDER = "From tablet"


def home() -> Path:
    return Path(os.environ.get("PAIRBRIDGE_HOME", Path.home() / ".pairbridge"))


def config_path() -> Path:
    return home() / "config.json"


def candidate_roots() -> list[dict]:
    """The usual user folders that exist on this machine, offered as the default shares."""
    candidates = [("downloads", "Downloads"), ("documents", "Documents"), ("pictures", "Pictures"), ("videos", "Videos")]
    return [
        {"id": root_id, "name": name, "path": str(Path.home() / name)}
        for root_id, name in candidates
        if (Path.home() / name).is_dir()
    ]


def load_config(choose_roots: Callable[[list[dict]], list[dict]] | None = None) -> dict:
    """Load the config, creating it (or filling in missing keys) on first use.

    `choose_roots` picks the initial shared folders from the candidates; without it every
    candidate is shared."""
    path = config_path()
    config = json.loads(path.read_text()) if path.exists() else {}
    changed = False

    if "shared_roots" not in config:
        candidates = candidate_roots()
        roots = choose_roots(candidates) if choose_roots else candidates
        if not roots:
            fallback = Path.home() / "Pairbridge"
            fallback.mkdir(exist_ok=True)
            roots = [{"id": "pairbridge", "name": "Pairbridge", "path": str(fallback)}]
        config["shared_roots"] = roots
        changed = True

    defaults = {
        "port": lambda: DEFAULT_PORT,
        "token": lambda: secrets.token_urlsafe(32),
        "server_id": lambda: secrets.token_hex(8),
        "max_upload_bytes": lambda: DEFAULT_MAX_UPLOAD_BYTES,
        "inbox": lambda: {"root": config["shared_roots"][0]["id"], "path": DEFAULT_INBOX_FOLDER},
    }
    for key, make_default in defaults.items():
        if key not in config:
            config[key] = make_default()
            changed = True

    if changed:
        save_config(config)
    elif path.stat().st_mode & 0o077:
        path.chmod(0o600)
    return config


def save_config(config: dict) -> None:
    # The token grants read/write access to every shared folder, so the file is private to
    # this user. Written to a temp file and renamed so a crash can't leave half a config.
    home().mkdir(mode=0o700, parents=True, exist_ok=True)
    tmp = config_path().with_suffix(".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(config, f, indent=2)
    os.replace(tmp, config_path())


def new_root_id(name: str, existing: set[str]) -> str:
    base = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") or "folder"
    root_id, n = base, 2
    while root_id in existing:
        root_id, n = f"{base}-{n}", n + 1
    return root_id


def machine_name() -> str:
    """The PC's display name: systemd's pretty hostname if set, else the short hostname."""
    try:
        for line in Path("/etc/machine-info").read_text().splitlines():
            if line.startswith("PRETTY_HOSTNAME="):
                pretty = line.split("=", 1)[1].strip().strip('"')
                if pretty:
                    return pretty
    except OSError:
        pass
    return socket.gethostname().split(".")[0]


def primary_ipv4() -> str:
    """The address of the interface that holds the default route, i.e. the one on the LAN."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        # connect() on UDP only picks a route; no packet is sent.
        s.connect(("192.0.2.1", 9))
        return s.getsockname()[0]
