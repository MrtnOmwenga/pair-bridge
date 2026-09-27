"""The HTTP API the Android app talks to, plus the mDNS announcement that lets it find this PC."""

import logging
import os
import secrets
import shutil
import socket
import tempfile
import time
from contextlib import asynccontextmanager
from io import BytesIO
from pathlib import Path

import uvicorn
from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import FileResponse, Response

from .config import DEFAULT_MAX_UPLOAD_BYTES, machine_name, primary_ipv4

SERVICE_TYPE = "_pairbridge._tcp.local."

log = logging.getLogger("pairbridge")


def validate_name(name: str) -> str:
    if not name or name in (".", "..") or "/" in name or "\0" in name:
        raise HTTPException(status_code=400, detail="invalid name")
    return name


def unique_child(parent: Path, name: str, create) -> Path:
    """Create `name` under `parent` with `create(path)`, adding " (n)" before the extension
    until it doesn't collide. `create` must raise FileExistsError on a collision, which makes
    reserving the name atomic."""
    stem, suffix = Path(name).stem, Path(name).suffix
    candidate = parent / name
    for n in range(1, 10_000):
        try:
            create(candidate)
            return candidate
        except FileExistsError:
            candidate = parent / f"{stem} ({n}){suffix}"
    raise HTTPException(status_code=409, detail="too many files with this name")


def entry_json(path: Path) -> dict:
    st = path.stat()
    return {"name": path.name, "is_dir": path.is_dir(), "size": st.st_size, "mtime": int(st.st_mtime)}


async def write_stream(target: Path, request: Request, max_bytes: int) -> None:
    """Stream the request body into `target`, replacing it only once the body is complete.

    Writing to a temp file in the same directory and renaming keeps a dropped upload from
    leaving a truncated file behind (rename is atomic within one filesystem)."""
    declared = request.headers.get("content-length")
    if declared is not None and int(declared) > max_bytes:
        raise HTTPException(status_code=413, detail="file too large")

    fd, tmp_name = tempfile.mkstemp(dir=target.parent, prefix=f".{target.name}.", suffix=".part")
    tmp = Path(tmp_name)
    try:
        written = 0
        with os.fdopen(fd, "wb") as f:
            async for chunk in request.stream():
                written += len(chunk)
                if written > max_bytes:
                    raise HTTPException(status_code=413, detail="file too large")
                f.write(chunk)
        # mkstemp creates the file as 0600; keep the mode of the file being replaced instead.
        tmp.chmod(target.stat().st_mode & 0o777 if target.exists() else 0o644)
        os.replace(tmp, target)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


def create_app(config: dict, advertise: bool = False) -> FastAPI:
    roots = {r["id"]: Path(r["path"]).expanduser().resolve() for r in config["shared_roots"]}
    root_names = {r["id"]: r["name"] for r in config["shared_roots"]}
    expected_auth = f"Bearer {config['token']}"
    max_upload = config.get("max_upload_bytes", DEFAULT_MAX_UPLOAD_BYTES)
    inbox_root, inbox_path = config["inbox"]["root"], config["inbox"]["path"]

    for path in roots.values():
        path.mkdir(parents=True, exist_ok=True)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        zc = await start_advertising(config) if advertise else None
        yield
        if zc is not None:
            await zc.async_close()

    app = FastAPI(title="Pairbridge", lifespan=lifespan)

    @app.middleware("http")
    async def log_requests(request: Request, call_next):
        start = time.monotonic()
        response = await call_next(request)
        elapsed_ms = (time.monotonic() - start) * 1000
        log.info("%s %s -> %s in %.1fms", request.method, request.url.path, response.status_code, elapsed_ms)
        return response

    def require_token(authorization: str = Header(default="")) -> None:
        if not secrets.compare_digest(authorization.encode(), expected_auth.encode()):
            raise HTTPException(status_code=401, detail="invalid or missing token")

    def resolve_path(root_id: str, relative_path: str) -> Path:
        """Resolve a client-supplied path against the named root, rejecting escapes.

        resolve() follows symlinks, so a link inside a shared folder that points outside it
        is rejected too."""
        base = roots.get(root_id)
        if base is None:
            raise HTTPException(status_code=404, detail="unknown root")
        candidate = (base / relative_path.lstrip("/")).resolve()
        if candidate != base and base not in candidate.parents:
            raise HTTPException(status_code=400, detail="path escapes shared root")
        return candidate

    def relative_to_root(root_id: str, path: Path) -> str:
        return str(path.relative_to(roots[root_id]))

    @app.get("/health")
    def health():
        return {"status": "ok"}

    api = APIRouter(dependencies=[Depends(require_token)])

    @api.get("/roots")
    def list_roots():
        return {
            "server_id": config["server_id"],
            "server_name": config.get("name") or machine_name(),
            "roots": [{"id": root_id, "name": root_names[root_id]} for root_id in roots],
        }

    @api.get("/files")
    def list_files(root: str = Query(...), path: str = ""):
        target = resolve_path(root, path)
        if not target.exists():
            raise HTTPException(status_code=404, detail="not found")
        if not target.is_dir():
            raise HTTPException(status_code=400, detail="not a directory")

        entries = []
        for entry in target.iterdir():
            try:
                entries.append(entry_json(entry))
            except OSError:
                continue  # broken symlink, or removed while listing
        entries.sort(key=lambda e: (not e["is_dir"], e["name"].lower()))
        return {"root": root, "path": path, "entries": entries}

    @api.get("/files/stat")
    def stat_file(root: str = Query(...), path: str = Query(...)):
        target = resolve_path(root, path)
        if not target.exists():
            raise HTTPException(status_code=404, detail="not found")
        return entry_json(target)

    @api.get("/files/download")
    def download_file(root: str = Query(...), path: str = Query(...)):
        target = resolve_path(root, path)
        if not target.is_file():
            raise HTTPException(status_code=404, detail="not found")
        return FileResponse(target, filename=target.name)

    @api.get("/files/thumb")
    def thumbnail(root: str = Query(...), path: str = Query(...), size: int = Query(256, ge=32, le=1024)):
        from PIL import Image, ImageOps, UnidentifiedImageError

        target = resolve_path(root, path)
        if not target.is_file():
            raise HTTPException(status_code=404, detail="not found")
        try:
            with Image.open(target) as img:
                # draft() lets the JPEG decoder downscale while decoding, which is most of the
                # speedup for camera photos.
                img.draft("RGB", (size, size))
                thumb = ImageOps.exif_transpose(img)
                thumb.thumbnail((size, size))
                out = BytesIO()
                thumb.convert("RGB").save(out, "JPEG", quality=82)
        except (UnidentifiedImageError, Image.DecompressionBombError, OSError):
            raise HTTPException(status_code=415, detail="not a supported image")
        return Response(out.getvalue(), media_type="image/jpeg")

    @api.put("/files/content")
    async def write_file(request: Request, root: str = Query(...), path: str = Query(...)):
        target = resolve_path(root, path)
        if target.is_dir():
            raise HTTPException(status_code=400, detail="target is a directory")
        if not target.parent.is_dir():
            raise HTTPException(status_code=404, detail="parent folder not found")
        await write_stream(target, request, max_upload)
        return {"path": relative_to_root(root, target), **entry_json(target)}

    @api.post("/files/create")
    def create_entry(
        root: str = Query(...),
        parent: str = "",
        name: str = Query(...),
        kind: str = Query("file", pattern="^(file|dir)$"),
    ):
        parent_dir = resolve_path(root, parent)
        if not parent_dir.is_dir():
            raise HTTPException(status_code=404, detail="parent folder not found")
        validate_name(name)
        if kind == "dir":
            created = unique_child(parent_dir, name, lambda p: p.mkdir())
        else:
            created = unique_child(parent_dir, name, lambda p: p.open("x").close())
        return {"path": relative_to_root(root, created), **entry_json(created)}

    @api.post("/files/rename")
    def rename_entry(root: str = Query(...), path: str = Query(...), name: str = Query(...)):
        target = resolve_path(root, path)
        if target == roots[root]:
            raise HTTPException(status_code=400, detail="cannot rename a shared folder")
        if not target.exists():
            raise HTTPException(status_code=404, detail="not found")
        destination = target.parent / validate_name(name)
        if destination.exists():
            raise HTTPException(status_code=409, detail="name already taken")
        target.rename(destination)
        return {"path": relative_to_root(root, destination), **entry_json(destination)}

    @api.delete("/files")
    def delete_entry(root: str = Query(...), path: str = Query(...)):
        target = resolve_path(root, path)
        if target == roots[root]:
            raise HTTPException(status_code=400, detail="cannot delete a shared folder")
        # resolve_path() followed any symlink; deleting a link must remove the link itself,
        # never the folder it points to.
        unresolved = roots[root] / path.lstrip("/")
        if unresolved.is_symlink():
            unresolved.unlink()
        elif target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()
        else:
            raise HTTPException(status_code=404, detail="not found")
        return {"deleted": path}

    @api.post("/inbox")
    async def receive_into_inbox(request: Request, name: str = Query(...)):
        """Files sent from the tablet's share sheet. The server picks the final name so two
        sends of "IMG_0001.jpg" never overwrite each other."""
        inbox = resolve_path(inbox_root, inbox_path)
        inbox.mkdir(parents=True, exist_ok=True)
        target = unique_child(inbox, validate_name(name), lambda p: p.open("x").close())
        try:
            await write_stream(target, request, max_upload)
        except BaseException:
            target.unlink(missing_ok=True)
            raise
        return {"root": inbox_root, "path": relative_to_root(inbox_root, target), **entry_json(target)}

    app.include_router(api)
    return app


async def start_advertising(config: dict):
    """Announce the server over mDNS so the app can find it again after the laptop's DHCP
    address changes. The TXT record carries only the server id, never the token."""
    from zeroconf import ServiceInfo
    from zeroconf.asyncio import AsyncZeroconf

    server_id = config["server_id"]
    zc = AsyncZeroconf()
    try:
        info = ServiceInfo(
            SERVICE_TYPE,
            f"{socket.gethostname()} {server_id[:4]}.{SERVICE_TYPE}",
            port=config["port"],
            properties={"id": server_id},
            # A host name of our own; reusing the machine's hostname would clash with avahi's record.
            server=f"pairbridge-{server_id}.local.",
            parsed_addresses=[primary_ipv4()],
        )
        await zc.async_register_service(info)
    except Exception as e:  # discovery is a convenience; the server works without it
        log.warning("mDNS advertising failed: %s", e)
        await zc.async_close()
        return None
    return zc


def serve(config: dict, advertise: bool = True) -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    uvicorn.run(create_app(config, advertise=advertise), host="0.0.0.0", port=config["port"])
