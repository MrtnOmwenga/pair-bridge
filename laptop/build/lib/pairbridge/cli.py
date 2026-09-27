"""The `pairbridge` command."""

import argparse
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path
from urllib.parse import urlencode

from . import config as cfg

SERVICE = "pairbridge.service"


def unit_path() -> Path:
    config_home = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return config_home / "systemd" / "user" / SERVICE


def systemctl(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["systemctl", "--user", *args], check=check, capture_output=True, text=True)


def service_active() -> bool:
    return systemctl("is-active", "--quiet", SERVICE, check=False).returncode == 0


def restart_if_running() -> None:
    if service_active():
        systemctl("restart", SERVICE)
        print("Restarted the background service to apply the change.")
    else:
        print("Takes effect the next time the server starts.")



def cmd_serve(args: argparse.Namespace) -> None:
    from .server import serve

    config = cfg.load_config()
    print(f"Pairbridge on port {config['port']}, sharing:")
    for root in config["shared_roots"]:
        print(f"  {root['name']:<12} {root['path']}")
    sys.stdout.flush()
    serve(config, advertise=not args.no_mdns)


def pairing_uri(config: dict) -> str:
    params = {"host": cfg.primary_ipv4(), "port": config["port"], "token": config["token"], "id": config["server_id"]}
    return "pairbridge://pair?" + urlencode(params)


def cmd_pair(args: argparse.Namespace) -> None:
    import segno

    config = cfg.load_config()
    print("In the Pairbridge app, tap \"Scan pairing code\" and scan this:\n")
    segno.make(pairing_uri(config), error="m").terminal(compact=True)
    if args.show_token:
        print(f"\nManual pairing:  address {cfg.primary_ipv4()}  port {config['port']}  token {config['token']}")
    else:
        print("\nTo pair by hand instead, run: pairbridge pair --show-token")



def choose_roots_interactively(candidates: list[dict]) -> list[dict]:
    if not sys.stdin.isatty() or not candidates:
        return candidates
    print("Which folders should the tablet be able to see? (add more later with: pairbridge share add)")
    chosen = []
    for root in candidates:
        answer = input(f"  Share {root['path']}? [Y/n] ").strip().lower()
        if answer in ("", "y", "yes"):
            chosen.append(root)
    return chosen


def render_unit() -> str:
    # sys.executable is the interpreter of the environment pairbridge is installed in (a pipx
    # venv, typically), so the service keeps working without that venv being activated.
    return f"""[Unit]
Description=Pairbridge: share files with a paired Android device

[Service]
ExecStart={sys.executable} -m pairbridge serve
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
"""


def firewall_warnings(port: int) -> list[str]:
    """Blocked ports are the most common reason the tablet can't connect on Fedora."""
    if not shutil.which("firewall-cmd"):
        return []
    run = lambda *a: subprocess.run(["firewall-cmd", *a], capture_output=True, text=True)
    if run("--state").returncode != 0:
        return []

    def port_open(number: int, proto: str) -> bool:
        for entry in run("--list-ports").stdout.split():
            span, _, entry_proto = entry.partition("/")
            low, _, high = span.partition("-")
            if entry_proto == proto and int(low) <= number <= int(high or low):
                return True
        return False

    warnings = []
    if not port_open(port, "tcp"):
        warnings.append(f"sudo firewall-cmd --permanent --add-port={port}/tcp   # so the tablet can connect")
    if "mdns" not in run("--list-services").stdout.split() and not port_open(5353, "udp"):
        warnings.append("sudo firewall-cmd --permanent --add-service=mdns   # so the tablet can find this PC again")
    if warnings:
        warnings.append("sudo firewall-cmd --reload")
    return warnings


def cmd_install(args: argparse.Namespace) -> None:
    config = cfg.load_config(choose_roots=None if args.yes else choose_roots_interactively)
    unit_path().parent.mkdir(parents=True, exist_ok=True)
    unit_path().write_text(render_unit())
    systemctl("daemon-reload")
    systemctl("enable", "--now", SERVICE)
    systemctl("restart", SERVICE)  # pick up a changed unit or config on reinstall

    print(f"\nPairbridge is running in the background and starts when you log in ({unit_path()}).")
    print(f"Sharing: {', '.join(root['name'] for root in config['shared_roots'])}")
    print(f"Files sent from the tablet go to: {inbox_description(config)}")
    warnings = firewall_warnings(config["port"])
    if warnings:
        print("\nYour firewall may block the tablet. To open it:")
        for line in warnings:
            print(f"  {line}")
    print()
    cmd_pair(argparse.Namespace(show_token=False))


def cmd_uninstall(args: argparse.Namespace) -> None:
    systemctl("disable", "--now", SERVICE, check=False)
    unit_path().unlink(missing_ok=True)
    systemctl("daemon-reload", check=False)
    print("Background service removed.")
    if args.purge:
        shutil.rmtree(cfg.home(), ignore_errors=True)
        print(f"Deleted {cfg.home()}; the tablet will need to pair again.")
    else:
        print(f"Kept {cfg.config_path()} (shared folders and pairing). Remove it with --purge.")



def inbox_description(config: dict) -> str:
    roots = {root["id"]: root for root in config["shared_roots"]}
    inbox = config["inbox"]
    root = roots.get(inbox["root"])
    return str(Path(root["path"]) / inbox["path"]) if root else f"(missing folder '{inbox['root']}')"


def cmd_status(args: argparse.Namespace) -> None:
    config = cfg.load_config()
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{config['port']}/health", timeout=2):
            reachable = True
    except OSError:
        reachable = False

    print(f"Service      {'running' if service_active() else 'not running'} (background)")
    print(f"Server       {'answering' if reachable else 'not answering'} on port {config['port']}")
    try:
        print(f"Address      {cfg.primary_ipv4()}")
    except OSError:
        print("Address      no network")
    print(f"PC name      {config.get('name') or cfg.machine_name()}")
    print(f"Inbox        {inbox_description(config)}")
    print("Shared       " + "\n             ".join(f"{r['id']:<12} {r['path']}" for r in config["shared_roots"]))
    print(f"Config       {cfg.config_path()}")


def cmd_logs(args: argparse.Namespace) -> None:
    os.execvp("journalctl", ["journalctl", "--user", "-u", SERVICE, "-f", "-n", "50"])



def cmd_share_list(args: argparse.Namespace) -> None:
    config = cfg.load_config()
    for root in config["shared_roots"]:
        inbox = "  (inbox)" if root["id"] == config["inbox"]["root"] else ""
        print(f"{root['id']:<12} {root['name']:<16} {root['path']}{inbox}")


def cmd_share_add(args: argparse.Namespace) -> None:
    path = Path(args.path).expanduser().resolve()
    if not path.is_dir():
        raise SystemExit(f"{path} is not a folder")
    if path == Path.home():
        raise SystemExit("Refusing to share your whole home folder; share specific folders instead.")

    config = cfg.load_config()
    if any(Path(root["path"]).resolve() == path for root in config["shared_roots"]):
        raise SystemExit(f"{path} is already shared")
    name = args.name or path.name
    root_id = cfg.new_root_id(name, {root["id"] for root in config["shared_roots"]})
    config["shared_roots"].append({"id": root_id, "name": name, "path": str(path)})
    cfg.save_config(config)
    print(f"Sharing {path} as \"{name}\" (id: {root_id}).")
    restart_if_running()


def cmd_share_remove(args: argparse.Namespace) -> None:
    config = cfg.load_config()
    remaining = [root for root in config["shared_roots"] if root["id"] != args.id]
    if len(remaining) == len(config["shared_roots"]):
        raise SystemExit(f"No shared folder with id '{args.id}'. See: pairbridge share list")
    if not remaining:
        raise SystemExit("Can't remove the last shared folder.")
    config["shared_roots"] = remaining
    if config["inbox"]["root"] == args.id:
        config["inbox"] = {"root": remaining[0]["id"], "path": cfg.DEFAULT_INBOX_FOLDER}
        print(f"Files from the tablet will now go to {inbox_description(config)}.")
    cfg.save_config(config)
    print(f"Stopped sharing '{args.id}'.")
    restart_if_running()


def cmd_mount(args: argparse.Namespace) -> None:
    try:
        from .mount import mount
    except ImportError:
        raise SystemExit("Mounting needs the optional extra: pipx install 'pairbridge[mount]'")
    mount(Path(args.mountpoint))



def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="pairbridge", description="Share files between this PC and an Android tablet.")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("install", help="run the server in the background at login, then show the pairing code")
    p.add_argument("-y", "--yes", action="store_true", help="share the default folders without asking")
    p.set_defaults(func=cmd_install)

    p = sub.add_parser("uninstall", help="remove the background service")
    p.add_argument("--purge", action="store_true", help="also delete the config and pairing")
    p.set_defaults(func=cmd_uninstall)

    p = sub.add_parser("pair", help="show the QR code to pair the tablet")
    p.add_argument("--show-token", action="store_true", help="also print the address, port and token")
    p.set_defaults(func=cmd_pair)

    sub.add_parser("status", help="show whether the server is running and what it shares").set_defaults(func=cmd_status)
    sub.add_parser("logs", help="follow the background service's log").set_defaults(func=cmd_logs)

    p = sub.add_parser("serve", help="run the server in the foreground")
    p.add_argument("--no-mdns", action="store_true", help="don't announce this PC on the local network")
    p.set_defaults(func=cmd_serve)

    share = sub.add_parser("share", help="manage the folders the tablet can see").add_subparsers(dest="action", required=True)
    share.add_parser("list", help="list shared folders").set_defaults(func=cmd_share_list)
    p = share.add_parser("add", help="share a folder")
    p.add_argument("path")
    p.add_argument("--name", help="name shown on the tablet (default: the folder's name)")
    p.set_defaults(func=cmd_share_add)
    p = share.add_parser("remove", help="stop sharing a folder")
    p.add_argument("id", help="the folder's id, from: pairbridge share list")
    p.set_defaults(func=cmd_share_remove)

    p = sub.add_parser("mount", help="(experimental) mount the tablet's folders here")
    p.add_argument("mountpoint")
    p.set_defaults(func=cmd_mount)
    return parser


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    args.func(args)
