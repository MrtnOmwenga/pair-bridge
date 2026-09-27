import subprocess
import sys

import pytest

from pairbridge import cli
from pairbridge import config as cfg


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("PAIRBRIDGE_HOME", str(tmp_path / "pb"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg"))
    monkeypatch.setattr(cfg, "primary_ipv4", lambda: "192.168.1.5")
    monkeypatch.setattr(cli, "firewall_warnings", lambda port: [])
    calls = []

    def fake_systemctl(*args, check=True):
        calls.append(args)
        return subprocess.CompletedProcess(args, returncode=3, stdout="", stderr="")

    monkeypatch.setattr(cli, "systemctl", fake_systemctl)

    first = tmp_path / "first"
    first.mkdir()
    cfg.load_config(choose_roots=lambda candidates: [{"id": "first", "name": "First", "path": str(first)}])
    return tmp_path, calls


def test_share_add_list_remove(env, capsys):
    tmp_path, _ = env
    (tmp_path / "Projects").mkdir()
    cli.main(["share", "add", str(tmp_path / "Projects")])
    roots = cfg.load_config()["shared_roots"]
    assert [r["id"] for r in roots] == ["first", "projects"]

    cli.main(["share", "list"])
    assert "Projects" in capsys.readouterr().out

    cli.main(["share", "remove", "projects"])
    assert [r["id"] for r in cfg.load_config()["shared_roots"]] == ["first"]


def test_share_add_rejects_duplicates_home_and_files(env, tmp_path):
    with pytest.raises(SystemExit, match="already shared"):
        cli.main(["share", "add", str(tmp_path / "first")])
    with pytest.raises(SystemExit, match="home folder"):
        cli.main(["share", "add", "~"])
    (tmp_path / "file.txt").write_text("")
    with pytest.raises(SystemExit, match="not a folder"):
        cli.main(["share", "add", str(tmp_path / "file.txt")])


def test_share_ids_stay_unique(env, tmp_path):
    for parent in ("a", "b"):
        (tmp_path / parent / "Music").mkdir(parents=True)
        cli.main(["share", "add", str(tmp_path / parent / "Music")])
    assert [r["id"] for r in cfg.load_config()["shared_roots"]] == ["first", "music", "music-2"]


def test_removing_the_inbox_folder_moves_the_inbox(env, tmp_path):
    (tmp_path / "second").mkdir()
    cli.main(["share", "add", str(tmp_path / "second")])
    cli.main(["share", "remove", "first"])
    assert cfg.load_config()["inbox"] == {"root": "second", "path": cfg.DEFAULT_INBOX_FOLDER}
    with pytest.raises(SystemExit, match="last shared folder"):
        cli.main(["share", "remove", "second"])


def test_install_writes_unit_for_this_interpreter_and_enables_it(env, capsys):
    _, calls = env
    cli.main(["install", "--yes"])
    unit = cli.unit_path().read_text()
    assert f"ExecStart={sys.executable} -m pairbridge serve" in unit
    assert ("enable", "--now", cli.SERVICE) in calls
    assert "Scan pairing code" in capsys.readouterr().out


def test_uninstall_keeps_config_unless_purged(env):
    cli.main(["install", "--yes"])
    cli.main(["uninstall"])
    assert not cli.unit_path().exists()
    assert cfg.config_path().exists()
    cli.main(["uninstall", "--purge"])
    assert not cfg.home().exists()


def test_pair_hides_the_token_unless_asked(env, capsys):
    token = cfg.load_config()["token"]
    cli.main(["pair"])
    assert token not in capsys.readouterr().out
    cli.main(["pair", "--show-token"])
    assert token in capsys.readouterr().out
    assert f"token={token}" in cli.pairing_uri(cfg.load_config())


def test_firewall_warnings_understand_port_ranges(monkeypatch):
    responses = {"--state": (0, "running"), "--list-ports": (0, "1025-65535/tcp 1025-65535/udp"), "--list-services": (0, "ssh")}

    def fake_run(cmd, **kwargs):
        code, out = responses[cmd[1]]
        return subprocess.CompletedProcess(cmd, code, stdout=out, stderr="")

    monkeypatch.setattr(cli.shutil, "which", lambda name: "/usr/bin/firewall-cmd")
    monkeypatch.setattr(cli.subprocess, "run", fake_run)
    assert cli.firewall_warnings(8765) == []

    responses["--list-ports"] = (0, "22/tcp")
    warnings = cli.firewall_warnings(8765)
    assert any("8765/tcp" in w for w in warnings)
    assert any("mdns" in w for w in warnings)
