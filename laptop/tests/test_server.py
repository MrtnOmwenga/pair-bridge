import io
import stat

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from pairbridge import config as cfg
from pairbridge.server import create_app

TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture
def shared(tmp_path):
    root = tmp_path / "shared"
    root.mkdir()
    (root / "docs").mkdir()
    (root / "docs" / "a.txt").write_text("hello")
    (tmp_path / "outside.txt").write_text("secret")
    return root


@pytest.fixture
def client(shared):
    config = {
        "shared_roots": [{"id": "shared", "name": "Shared", "path": str(shared)}],
        "port": 0,
        "token": TOKEN,
        "server_id": "abc",
        "name": "Test PC",
        "max_upload_bytes": 1024,
        "inbox": {"root": "shared", "path": "From tablet"},
    }
    return TestClient(create_app(config))


def test_health_needs_no_token(client):
    assert client.get("/health").status_code == 200


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}, {"Authorization": TOKEN}])
def test_api_rejects_missing_or_wrong_token(client, headers):
    assert client.get("/roots", headers=headers).status_code == 401


def test_list_roots(client):
    assert client.get("/roots", headers=AUTH).json() == {
        "server_id": "abc",
        "server_name": "Test PC",
        "roots": [{"id": "shared", "name": "Shared"}],
    }


@pytest.mark.parametrize("path", ["../outside.txt", "docs/../../outside.txt", "/../outside.txt"])
def test_path_traversal_is_rejected(client, path):
    r = client.get("/files/download", params={"root": "shared", "path": path}, headers=AUTH)
    assert r.status_code == 400


def test_symlink_pointing_outside_root_is_rejected(client, shared, tmp_path):
    (shared / "escape").symlink_to(tmp_path / "outside.txt")
    r = client.get("/files/download", params={"root": "shared", "path": "escape"}, headers=AUTH)
    assert r.status_code == 400


def test_unknown_root(client):
    assert client.get("/files", params={"root": "nope"}, headers=AUTH).status_code == 404


def test_listing_skips_broken_symlinks(client, shared):
    (shared / "dangling").symlink_to(shared / "missing")
    r = client.get("/files", params={"root": "shared"}, headers=AUTH)
    assert r.status_code == 200
    assert [e["name"] for e in r.json()["entries"]] == ["docs"]


def test_download(client):
    r = client.get("/files/download", params={"root": "shared", "path": "docs/a.txt"}, headers=AUTH)
    assert r.content == b"hello"


def test_write_replaces_content_and_keeps_mode(client, shared):
    target = shared / "docs" / "a.txt"
    target.chmod(0o640)
    r = client.put("/files/content", params={"root": "shared", "path": "docs/a.txt"}, content=b"new", headers=AUTH)
    assert r.status_code == 200
    assert target.read_bytes() == b"new"
    assert stat.S_IMODE(target.stat().st_mode) == 0o640


def test_write_over_limit_leaves_original_and_no_temp_files(client, shared):
    r = client.put(
        "/files/content", params={"root": "shared", "path": "docs/a.txt"}, content=b"x" * 2048, headers=AUTH
    )
    assert r.status_code == 413
    assert (shared / "docs" / "a.txt").read_text() == "hello"
    assert sorted(p.name for p in (shared / "docs").iterdir()) == ["a.txt"]


def test_write_to_directory_or_missing_parent(client):
    params = {"root": "shared", "path": "docs"}
    assert client.put("/files/content", params=params, content=b"x", headers=AUTH).status_code == 400
    params = {"root": "shared", "path": "nope/b.txt"}
    assert client.put("/files/content", params=params, content=b"x", headers=AUTH).status_code == 404


def test_create_picks_unique_names(client, shared):
    params = {"root": "shared", "parent": "docs", "name": "a.txt"}
    assert client.post("/files/create", params=params, headers=AUTH).json()["path"] == "docs/a (1).txt"
    params = {"root": "shared", "parent": "docs", "name": "new", "kind": "dir"}
    assert client.post("/files/create", params=params, headers=AUTH).json()["is_dir"] is True
    assert (shared / "docs" / "new").is_dir()


@pytest.mark.parametrize("name", ["", ".", "..", "a/b"])
def test_create_rejects_invalid_names(client, name):
    params = {"root": "shared", "parent": "docs", "name": name}
    assert client.post("/files/create", params=params, headers=AUTH).status_code in (400, 422)


def test_rename(client, shared):
    params = {"root": "shared", "path": "docs/a.txt", "name": "b.txt"}
    assert client.post("/files/rename", params=params, headers=AUTH).json()["path"] == "docs/b.txt"
    (shared / "docs" / "c.txt").write_text("")
    params = {"root": "shared", "path": "docs/b.txt", "name": "c.txt"}
    assert client.post("/files/rename", params=params, headers=AUTH).status_code == 409


def test_delete_file_and_folder_but_never_a_root(client, shared):
    assert client.delete("/files", params={"root": "shared", "path": "docs/a.txt"}, headers=AUTH).status_code == 200
    assert client.delete("/files", params={"root": "shared", "path": "docs"}, headers=AUTH).status_code == 200
    assert not (shared / "docs").exists()
    assert client.delete("/files", params={"root": "shared", "path": ""}, headers=AUTH).status_code == 400


def test_deleting_a_symlink_removes_only_the_link(client, shared):
    (shared / "link").symlink_to(shared / "docs")
    assert client.delete("/files", params={"root": "shared", "path": "link"}, headers=AUTH).status_code == 200
    assert (shared / "docs" / "a.txt").exists()


def test_inbox_creates_folder_and_never_overwrites(client, shared):
    first = client.post("/inbox", params={"name": "IMG.jpg"}, content=b"one", headers=AUTH).json()
    second = client.post("/inbox", params={"name": "IMG.jpg"}, content=b"two", headers=AUTH).json()
    assert (first["path"], second["path"]) == ("From tablet/IMG.jpg", "From tablet/IMG (1).jpg")
    assert (shared / "From tablet" / "IMG.jpg").read_bytes() == b"one"


def test_inbox_failed_upload_leaves_nothing(client, shared):
    r = client.post("/inbox", params={"name": "big.bin"}, content=b"x" * 2048, headers=AUTH)
    assert r.status_code == 413
    assert list((shared / "From tablet").iterdir()) == []


def test_thumbnail(client, shared):
    Image.new("RGB", (2000, 1000), "red").save(shared / "photo.jpg")
    r = client.get("/files/thumb", params={"root": "shared", "path": "photo.jpg", "size": 200}, headers=AUTH)
    assert r.headers["content-type"] == "image/jpeg"
    assert Image.open(io.BytesIO(r.content)).size == (200, 100)
    r = client.get("/files/thumb", params={"root": "shared", "path": "docs/a.txt"}, headers=AUTH)
    assert r.status_code == 415


def test_config_is_private(tmp_path, monkeypatch):
    monkeypatch.setenv("PAIRBRIDGE_HOME", str(tmp_path / "home"))
    shared = {"id": "s", "name": "S", "path": str(tmp_path)}
    config = cfg.load_config(choose_roots=lambda candidates: [shared])
    assert stat.S_IMODE(cfg.config_path().stat().st_mode) == 0o600
    assert stat.S_IMODE(cfg.home().stat().st_mode) == 0o700
    assert len(config["token"]) >= 40

    cfg.config_path().chmod(0o644)
    cfg.load_config()
    assert stat.S_IMODE(cfg.config_path().stat().st_mode) == 0o600
