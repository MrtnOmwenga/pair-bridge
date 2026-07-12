"""Pairbridge laptop server — Phase 1.

Serves a directory tree over HTTP so the Android app can list, download,
and upload files. Auth is a single bearer token stored in
~/.pairbridge/config.json (generated on first run).

Run with:
    .venv/bin/python server.py
"""

import json
import secrets
from pathlib import Path

import uvicorn
from fastapi import Depends, FastAPI, HTTPException, Header, Query, UploadFile

CONFIG_DIR = Path.home() / ".pairbridge"
CONFIG_PATH = CONFIG_DIR / "config.json"
DEFAULT_SHARED_ROOT = Path.home() / "pairbridge"
DEFAULT_PORT = 8765


def load_config() -> dict:
    if CONFIG_PATH.exists():
        config = json.loads(CONFIG_PATH.read_text())
    else:
        config = {}

    changed = False
    if "shared_root" not in config:
        config["shared_root"] = str(DEFAULT_SHARED_ROOT)
        changed = True
    if "port" not in config:
        config["port"] = DEFAULT_PORT
        changed = True
    if "token" not in config:
        config["token"] = secrets.token_urlsafe(32)
        changed = True

    if changed:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(config, indent=2))

    Path(config["shared_root"]).mkdir(parents=True, exist_ok=True)
    return config


config = load_config()
SHARED_ROOT = Path(config["shared_root"]).resolve()
TOKEN = config["token"]

app = FastAPI(title="Pairbridge")


def resolve_path(relative_path: str) -> Path:
    """Resolve a client-supplied path against SHARED_ROOT, rejecting escapes."""
    candidate = (SHARED_ROOT / relative_path.lstrip("/")).resolve()
    if candidate != SHARED_ROOT and SHARED_ROOT not in candidate.parents:
        raise HTTPException(status_code=400, detail="path escapes shared root")
    return candidate


def require_token(authorization: str = Header(default="")) -> None:
    expected = f"Bearer {TOKEN}"
    if not secrets.compare_digest(authorization, expected):
        raise HTTPException(status_code=401, detail="invalid or missing token")


@app.get("/health")
def health():
    return {"status": "ok", "shared_root": str(SHARED_ROOT)}


@app.get("/files")
def list_files(path: str = "", _: None = Depends(require_token)):
    target = resolve_path(path)
    if not target.exists():
        raise HTTPException(status_code=404, detail="not found")
    if not target.is_dir():
        raise HTTPException(status_code=400, detail="not a directory")

    entries = []
    for entry in sorted(target.iterdir(), key=lambda e: (not e.is_dir(), e.name.lower())):
        stat = entry.stat()
        entries.append(
            {
                "name": entry.name,
                "is_dir": entry.is_dir(),
                "size": stat.st_size,
                "mtime": int(stat.st_mtime),
            }
        )
    return {"path": path, "entries": entries}


@app.get("/files/download")
def download_file(path: str = Query(...), _: None = Depends(require_token)):
    from fastapi.responses import FileResponse

    target = resolve_path(path)
    if not target.is_file():
        raise HTTPException(status_code=404, detail="not found")
    return FileResponse(target, filename=target.name)


@app.post("/files/upload")
async def upload_file(
    path: str = Query(...), file: UploadFile = None, _: None = Depends(require_token)
):
    if file is None:
        raise HTTPException(status_code=400, detail="file is required")
    target = resolve_path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("wb") as f:
        while chunk := await file.read(1024 * 1024):
            f.write(chunk)
    return {"path": path, "size": target.stat().st_size}


if __name__ == "__main__":
    print(f"Pairbridge server starting")
    print(f"  shared root : {SHARED_ROOT}")
    print(f"  port        : {config['port']}")
    print(f"  token       : {TOKEN}")
    uvicorn.run(app, host="0.0.0.0", port=config["port"])
