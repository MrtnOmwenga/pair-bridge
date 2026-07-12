"""Mounts the tablet's shared folders (Camera, Download, Pictures, Documents) as a local,
read-only FUSE filesystem, using the same token issued during laptop->tablet pairing.

Requires "tablet_host" (and optionally "tablet_port", default 8766) in
~/.pairbridge/config.json — set it to the tablet's LAN IP once you know it (shown in the
Pairbridge app's "Share tablet files" section).

pyfuse3 needs libfuse3 at runtime; if it's not on the default library search path, run with:
    LD_LIBRARY_PATH=/usr/lib64 python3 pairbridge_mount.py <mountpoint>

Usage:
    python3 pairbridge_mount.py <mountpoint>
"""

import errno
import json
import os
import stat
import sys
import tempfile
from pathlib import Path

import pyfuse3
import requests
import trio

CONFIG_PATH = Path.home() / ".pairbridge" / "config.json"
DEFAULT_TABLET_PORT = 8766


class TabletClient:
    def __init__(self, host: str, port: int, token: str):
        self.base_url = f"http://{host}:{port}"
        self.headers = {"Authorization": f"Bearer {token}"}

    def roots(self) -> list[dict]:
        r = requests.get(f"{self.base_url}/roots", headers=self.headers, timeout=10)
        r.raise_for_status()
        return r.json()["roots"]

    def list_files(self, root: str, path: str) -> list[dict]:
        r = requests.get(
            f"{self.base_url}/files", params={"root": root, "path": path}, headers=self.headers, timeout=10
        )
        r.raise_for_status()
        return r.json()["entries"]

    def download(self, root: str, path: str, dest: Path) -> None:
        r = requests.get(
            f"{self.base_url}/files/download",
            params={"root": root, "path": path},
            headers=self.headers,
            timeout=60,
            stream=True,
        )
        r.raise_for_status()
        with dest.open("wb") as f:
            for chunk in r.iter_content(256 * 1024):
                f.write(chunk)


class Node:
    __slots__ = ("inode", "name", "root_id", "rel_path", "is_dir", "size", "mtime")

    def __init__(self, inode: int, name: str, root_id: str | None, rel_path: str, is_dir: bool, size=0, mtime=0):
        self.inode = inode
        self.name = name
        self.root_id = root_id  # None only for the synthetic filesystem root
        self.rel_path = rel_path
        self.is_dir = is_dir
        self.size = size
        self.mtime = mtime


class PairbridgeFS(pyfuse3.Operations):
    def __init__(self, client: TabletClient):
        super().__init__()
        self.client = client
        self.next_inode = pyfuse3.ROOT_INODE + 1
        self.nodes: dict[int, Node] = {pyfuse3.ROOT_INODE: Node(pyfuse3.ROOT_INODE, "", None, "", True)}
        self.children: dict[int, dict[str, int]] = {}
        self.open_files: dict[int, tuple[object, Path]] = {}
        self.next_fh = 1

    def _alloc_inode(self) -> int:
        inode = self.next_inode
        self.next_inode += 1
        return inode

    def _attr(self, node: Node) -> pyfuse3.EntryAttributes:
        entry = pyfuse3.EntryAttributes()
        entry.st_mode = (stat.S_IFDIR | 0o755) if node.is_dir else (stat.S_IFREG | 0o444)
        entry.st_size = 0 if node.is_dir else node.size
        entry.st_atime_ns = entry.st_mtime_ns = entry.st_ctime_ns = int(node.mtime) * 10**9
        entry.st_gid = os.getgid()
        entry.st_uid = os.getuid()
        entry.st_ino = node.inode
        entry.entry_timeout = 5
        entry.attr_timeout = 5
        return entry

    async def _ensure_children(self, parent_inode: int) -> None:
        if parent_inode in self.children:
            return
        node = self.nodes[parent_inode]
        entries_map: dict[str, int] = {}
        if node.root_id is None:
            roots = await trio.to_thread.run_sync(self.client.roots)
            for r in roots:
                inode = self._alloc_inode()
                self.nodes[inode] = Node(inode, r["name"], r["id"], "", True)
                entries_map[r["name"]] = inode
        else:
            entries = await trio.to_thread.run_sync(self.client.list_files, node.root_id, node.rel_path)
            for e in entries:
                inode = self._alloc_inode()
                child_path = f"{node.rel_path}/{e['name']}" if node.rel_path else e["name"]
                self.nodes[inode] = Node(
                    inode, e["name"], node.root_id, child_path, e["is_dir"], e["size"], e["mtime"]
                )
                entries_map[e["name"]] = inode
        self.children[parent_inode] = entries_map

    async def lookup(self, parent_inode, name, ctx=None):
        name_str = name.decode("utf-8") if isinstance(name, (bytes, bytearray)) else name
        await self._ensure_children(parent_inode)
        inode = self.children[parent_inode].get(name_str)
        if inode is None:
            raise pyfuse3.FUSEError(errno.ENOENT)
        return self._attr(self.nodes[inode])

    async def getattr(self, inode, ctx=None):
        node = self.nodes.get(inode)
        if node is None:
            raise pyfuse3.FUSEError(errno.ENOENT)
        return self._attr(node)

    async def opendir(self, inode, ctx):
        await self._ensure_children(inode)
        return inode

    async def readdir(self, inode, start_id, token):
        await self._ensure_children(inode)
        for i, (name, child_inode) in enumerate(self.children[inode].items()):
            if i < start_id:
                continue
            pyfuse3.readdir_reply(token, name.encode("utf-8"), self._attr(self.nodes[child_inode]), i + 1)

    async def open(self, inode, flags, ctx):
        if flags & (os.O_WRONLY | os.O_RDWR):
            raise pyfuse3.FUSEError(errno.EROFS)
        node = self.nodes[inode]
        if node.is_dir:
            raise pyfuse3.FUSEError(errno.EISDIR)

        fd, tmp_name = tempfile.mkstemp(prefix="pairbridge-")
        os.close(fd)
        tmp_path = Path(tmp_name)
        await trio.to_thread.run_sync(self.client.download, node.root_id, node.rel_path, tmp_path)

        fh = self.next_fh
        self.next_fh += 1
        self.open_files[fh] = (open(tmp_path, "rb"), tmp_path)
        return pyfuse3.FileInfo(fh=fh)

    async def read(self, fh, offset, size):
        f, _ = self.open_files[fh]
        f.seek(offset)
        return f.read(size)

    async def release(self, fh):
        entry = self.open_files.pop(fh, None)
        if entry is None:
            return
        f, tmp_path = entry
        f.close()
        try:
            tmp_path.unlink()
        except OSError:
            pass


def load_tablet_client() -> TabletClient:
    config = json.loads(CONFIG_PATH.read_text())
    host = config.get("tablet_host")
    if not host:
        print(f'Set "tablet_host" to the tablet\'s LAN IP in {CONFIG_PATH} first.')
        sys.exit(1)
    port = config.get("tablet_port", DEFAULT_TABLET_PORT)
    return TabletClient(host, port, config["token"])


def main() -> None:
    if len(sys.argv) != 2:
        print("usage: pairbridge_mount.py <mountpoint>")
        sys.exit(1)

    mountpoint = Path(sys.argv[1])
    mountpoint.mkdir(parents=True, exist_ok=True)

    fs = PairbridgeFS(load_tablet_client())

    fuse_options = set(pyfuse3.default_options)
    fuse_options.add("fsname=pairbridge")
    fuse_options.discard("default_permissions")
    pyfuse3.init(fs, str(mountpoint), fuse_options)
    try:
        trio.run(pyfuse3.main)
    finally:
        pyfuse3.close(unmount=True)


if __name__ == "__main__":
    main()
