"""Pairbridge laptop server.

Serves a configurable set of shared folders over HTTP so the Android app
can list, download, and upload files. Auth is a single bearer token stored
in ~/.pairbridge/config.json (generated on first run), alongside the list
of shared folders.

To add or remove a shared folder, edit the "shared_roots" list in
~/.pairbridge/config.json (each entry is {"id", "name", "path"}) and
restart the server.

Run with:
    python3 server.py
"""

import json
import logging
import secrets
import time
from pathlib import Path

import uvicorn
from fastapi import Depends, FastAPI, HTTPException, Header, Query, Request, UploadFile

CONFIG_DIR = Path.home() / ".pairbridge"
CONFIG_PATH = CONFIG_DIR / "config.json"
DEFAULT_PORT = 8765

DEFAULT_CANDIDATE_FOLDERS = [
    ("downloads", "Downloads", Path.home() / "Downloads"),
    ("documents", "Documents", Path.home() / "Documents"),
    ("pictures", "Pictures", Path.home() / "Pictures"),
    ("videos", "Videos", Path.home() / "Videos"),
]

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("pairbridge")


def default_shared_roots() -> list[dict]:
    roots = [
        {"id": root_id, "name": name, "path": str(path)}
        for root_id, name, path in DEFAULT_CANDIDATE_FOLDERS
        if path.is_dir()
    ]
    if not roots:
        fallback = Path.home() / "pairbridge"
        fallback.mkdir(parents=True, exist_ok=True)
        roots.append({"id": "pairbridge", "name": "Pairbridge", "path": str(fallback)})
    return roots


def load_config() -> dict:
    if CONFIG_PATH.exists():
        config = json.loads(CONFIG_PATH.read_text())
    else:
        config = {}

    changed = False
    if "shared_roots" not in config:
        if "shared_root" in config:
            config["shared_roots"] = [
                {"id": "pairbridge", "name": "Pairbridge", "path": config.pop("shared_root")}
            ]
        else:
            config["shared_roots"] = default_shared_roots()
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

    for root in config["shared_roots"]:
        Path(root["path"]).expanduser().mkdir(parents=True, exist_ok=True)

    return config


config = load_config()
ROOTS = {r["id"]: Path(r["path"]).expanduser().resolve() for r in config["shared_roots"]}
ROOT_NAMES = {r["id"]: r["name"] for r in config["shared_roots"]}
TOKEN = config["token"]

app = FastAPI(title="Pairbridge")


@app.middleware("http")
async def log_requests(request: Request, call_next):
    start = time.monotonic()
    response = await call_next(request)
    elapsed_ms = (time.monotonic() - start) * 1000
    log.info("%s %s -> %s in %.1fms", request.method, request.url.path, response.status_code, elapsed_ms)
    return response


def resolve_path(root_id: str, relative_path: str) -> Path:
    """Resolve a client-supplied path against the named root, rejecting escapes."""
    base = ROOTS.get(root_id)
    if base is None:
        raise HTTPException(status_code=404, detail="unknown root")
    candidate = (base / relative_path.lstrip("/")).resolve()
    if candidate != base and base not in candidate.parents:
        raise HTTPException(status_code=400, detail="path escapes shared root")
    return candidate


def require_token(authorization: str = Header(default="")) -> None:
    expected = f"Bearer {TOKEN}"
    if not secrets.compare_digest(authorization, expected):
        raise HTTPException(status_code=401, detail="invalid or missing token")


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/roots")
def list_roots(_: None = Depends(require_token)):
    return {"roots": [{"id": root_id, "name": ROOT_NAMES[root_id]} for root_id in ROOTS]}


@app.get("/files")
def list_files(root: str = Query(...), path: str = "", _: None = Depends(require_token)):
    target = resolve_path(root, path)
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
    return {"root": root, "path": path, "entries": entries}


@app.get("/files/stat")
def stat_file(root: str = Query(...), path: str = Query(...), _: None = Depends(require_token)):
    target = resolve_path(root, path)
    if not target.exists():
        raise HTTPException(status_code=404, detail="not found")
    stat = target.stat()
    return {
        "name": target.name,
        "is_dir": target.is_dir(),
        "size": stat.st_size,
        "mtime": int(stat.st_mtime),
    }


@app.get("/files/download")
def download_file(root: str = Query(...), path: str = Query(...), _: None = Depends(require_token)):
    from fastapi.responses import FileResponse

    target = resolve_path(root, path)
    if not target.is_file():
        raise HTTPException(status_code=404, detail="not found")
    return FileResponse(target, filename=target.name)


@app.post("/files/upload")
async def upload_file(
    root: str = Query(...),
    path: str = Query(...),
    file: UploadFile = None,
    _: None = Depends(require_token),
):
    if file is None:
        raise HTTPException(status_code=400, detail="file is required")
    target = resolve_path(root, path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("wb") as f:
        while chunk := await file.read(1024 * 1024):
            f.write(chunk)
    return {"root": root, "path": path, "size": target.stat().st_size}


if __name__ == "__main__":
    print("Pairbridge server starting")
    for root_id, path in ROOTS.items():
        print(f"  root   : {root_id} ({ROOT_NAMES[root_id]}) -> {path}")
    print(f"  port   : {config['port']}")
    print(f"  token  : {TOKEN}")
    uvicorn.run(app, host="0.0.0.0", port=config["port"])
