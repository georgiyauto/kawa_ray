#!/usr/bin/env python3
"""Kawa VPN hub: receives error reports (sent only with the user's consent) and
serves signed update files. Standard library only.

Listens on 127.0.0.1 behind the loopback nginx TLS reverse proxy. Stores reports in SQLite under
$STATE_DIRECTORY. Sender IP is stored only for reports that explicitly
declare the device/IP policy; legacy reports keep their previous policy.

  POST /v1/report                  JSON report -> {"id": "DR-XXXXXX"}
  GET  /v1/update/manifest.json    update manifest (signed, see manifest.sig)
  GET  /v1/update/manifest.sig
  GET  /v1/update/files/<name>     installers / AppImages
  GET  /arch/<arch>/<file>          signed pacman repository (duoray.db, packages)
  GET  /v1/ip                     observed public IP; no storage, no cache
  GET  /health
"""

import hashlib
import ipaddress
import json
import os
import secrets
import shutil
import sqlite3
import threading
import time
import re
import urllib.request
from urllib.parse import urlsplit, parse_qs
from contextlib import contextmanager
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = os.environ.get("STATE_DIRECTORY", "/var/lib/kawa-hub")
DB_PATH = os.path.join(STATE, "reports.db")
UPDATES = os.path.join(STATE, "updates")
LISTEN = os.environ.get("HUB_LISTEN", "127.0.0.1:5090")
BOT_TOKEN = os.environ.get("KAWA_BOT_TOKEN", "")
ADMIN_CHAT_ID = os.environ.get("KAWA_ADMIN_CHAT_ID", "")

MAX_BODY = 256 * 1024
RETENTION_DAYS = 90
MAX_DB_BYTES = 1024 * 1024 * 1024
RATE_PER_HOUR = 30
ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I

_db_lock = threading.Lock()
_rate = defaultdict(deque)
_rate_lock = threading.Lock()
_delivery_lock = threading.Lock()


@contextmanager
def db():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        with conn:
            yield conn
    finally:
        conn.close()


def init_db():
    os.makedirs(os.path.join(UPDATES, "files"), exist_ok=True)
    with db() as c:
        c.execute(
            """CREATE TABLE IF NOT EXISTS reports (
                id TEXT PRIMARY KEY,
                received INTEGER NOT NULL,
                install_id TEXT, version TEXT, os TEXT, kind TEXT,
                signature TEXT, message TEXT, payload TEXT NOT NULL)"""
        )
        c.execute("CREATE INDEX IF NOT EXISTS reports_received ON reports(received)")
        c.execute("CREATE INDEX IF NOT EXISTS reports_signature ON reports(signature)")
        c.execute("CREATE TABLE IF NOT EXISTS notifications (report_id TEXT PRIMARY KEY, delivered INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0)")


def safe_name(name):
    """A plain file name: no paths, no hidden files."""
    return bool(name) and all(c.isalnum() or c in "._-+" for c in name) and not name.startswith(".")


def clip(value, limit):
    return value[:limit] if isinstance(value, str) else ""


def allowed(ip):
    now = time.time()
    with _rate_lock:
        hits = _rate[ip]
        while hits and hits[0] < now - 3600:
            hits.popleft()
        if len(hits) >= RATE_PER_HOUR:
            return False
        hits.append(now)
        return True


def new_id(conn):
    while True:
        rid = "KW-" + "".join(secrets.choice(ID_ALPHABET) for _ in range(6))
        if not conn.execute("SELECT 1 FROM reports WHERE id=?", (rid,)).fetchone():
            return rid


def public_ip(value):
    if not isinstance(value, str):
        return ""
    try:
        address = ipaddress.ip_address(value)
        return str(address) if address.is_global and not address.is_multicast else ""
    except (ValueError, TypeError):
        return ""


def store(report, client_ip=""):
    report = dict(report)
    # Request-observed and client-reported addresses have different provenance.
    # Strip supplied fields before applying the consent policy.
    supplied = report.pop("provider_ip", None)
    report.pop("client_ip", None)
    report.pop("observed_ip", None)
    privacy = report.get("privacy")
    if isinstance(privacy, dict) and privacy.get("device_info_and_ip") is True:
        observed = public_ip(client_ip)
        if observed:
            report["client_ip"] = observed
        if privacy.get("provider_ip_outside_vpn") is True and isinstance(supplied, dict):
            address = public_ip(supplied.get("address"))
            at = supplied.get("observed_at")
            if address and isinstance(at, int) and not isinstance(at, bool) and 0 <= at <= int(time.time()) + 60 and supplied.get("source") == "kawa_hub_physical_uplink":
                report["provider_ip"] = {"address": address, "observed_at": at,
                                         "source": "kawa_hub_physical_uplink", "verification": "client_reported"}
    kind = clip(report.get("kind"), 32) or "unknown"
    message = clip(report.get("message"), 8192)
    first = message.splitlines()[0] if message else ""
    signature = clip(report.get("signature"), 64) or hashlib.sha1(f"{kind}\n{first}".encode()).hexdigest()[:12]
    with _db_lock, db() as conn:
        source_id = report.get("report_id", "")
        rid = source_id if isinstance(source_id, str) and re.fullmatch(r"KW-[0-9A-F]{16}", source_id) else new_id(conn)
        if conn.execute("SELECT 1 FROM reports WHERE id=?", (rid,)).fetchone():
            return rid
        conn.execute(
            "INSERT INTO reports VALUES (?,?,?,?,?,?,?,?,?)",
            (
                rid,
                int(time.time()),
                clip(report.get("install_id"), 64),
                clip(report.get("version"), 32),
                clip(report.get("os"), 96),
                kind,
                signature,
                message,
                json.dumps(report, ensure_ascii=False),
            ),
        )
        conn.execute("INSERT INTO notifications (report_id) VALUES (?)", (rid,))
    return rid


def notification_text(rid, report):
    device = report.get("device")
    device = device if isinstance(device, dict) else {}
    ram = device.get("ram_bytes")
    ram = f"{ram / 1024**3:.1f} GB" if isinstance(ram, (int, float)) and ram > 0 else "—"
    provider = report.get("provider_ip")
    provider = provider if isinstance(provider, dict) else {}
    return (f"Kawa VPN • {rid}\n"
            f"Тип: {clip(report.get('kind'), 40)}\n"
            f"Версия: {clip(report.get('version'), 32)}\n"
            f"ОС: {clip(report.get('os'), 96)} / {clip(report.get('arch'), 24)}\n"
            f"Устройство: {clip(device.get('model'), 100) or '—'}\n"
            f"CPU: {clip(device.get('cpu'), 120) or '—'} • RAM: {ram}\n"
            f"IP провайдера (со слов приложения): {clip(provider.get('address'), 64) or 'не определён'}\n"
            f"IP соединения с сервером: {clip(report.get('client_ip'), 64) or 'недоступен / не сохраняется'}\n"
            f"{clip(report.get('note') or report.get('message'), 300)}")[:1000]


def send_telegram(rid, report):
    if not BOT_TOKEN or not ADMIN_CHAT_ID:
        raise RuntimeError("notification configuration missing")
    boundary = "kawa-" + secrets.token_hex(16)
    chunks = []
    for name, value in [("chat_id", ADMIN_CHAT_ID), ("caption", notification_text(rid, report))]:
        chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="document"; filename="{rid}.json"\r\nContent-Type: application/json\r\n\r\n'.encode())
    chunks.append(json.dumps(report, ensure_ascii=False, indent=2).encode())
    chunks.append(f"\r\n--{boundary}--\r\n".encode())
    req = urllib.request.Request(f"https://api.telegram.org/bot{BOT_TOKEN}/sendDocument", data=b"".join(chunks), headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=20) as response:
        if not json.loads(response.read(1024*1024)).get("ok"):
            raise RuntimeError("notification refused")


def deliver_pending():
    if not _delivery_lock.acquire(blocking=False):
        return
    try:
        with db() as c:
            rows = c.execute("SELECT n.report_id, r.payload, n.attempts FROM notifications n JOIN reports r ON r.id=n.report_id WHERE n.delivered=0 AND n.next_attempt<=? ORDER BY r.received LIMIT 10", (int(time.time()),)).fetchall()
        for rid, payload, attempts in rows:
            try:
                send_telegram(rid, json.loads(payload))
            except Exception as e:
                # Never log request URLs: the Telegram credential is in them.
                print(f"notification retry {rid}: {type(e).__name__}", flush=True)
                delay = min(3600, 30 * 2**min(attempts, 7))
                with db() as c:
                    c.execute("UPDATE notifications SET attempts=attempts+1, next_attempt=? WHERE report_id=?", (int(time.time())+delay, rid))
            else:
                with db() as c:
                    c.execute("UPDATE notifications SET delivered=1 WHERE report_id=?", (rid,))
    finally:
        _delivery_lock.release()


def notify_forever():
    while True:
        try:
            deliver_pending()
        except Exception as e:
            print(f"notification worker: {type(e).__name__}", flush=True)
        time.sleep(5)


def purge_forever():
    while True:
        try:
            with _db_lock, db() as conn:
                conn.execute("DELETE FROM reports WHERE received < ?", (int(time.time()) - RETENTION_DAYS * 86400,))
                conn.execute("DELETE FROM notifications WHERE report_id NOT IN (SELECT id FROM reports)")
                if os.path.getsize(DB_PATH) > MAX_DB_BYTES:
                    conn.execute(
                        "DELETE FROM reports WHERE id IN "
                        "(SELECT id FROM reports ORDER BY received LIMIT (SELECT COUNT(*) / 10 FROM reports))"
                    )
            with db() as conn:
                conn.execute("VACUUM")
        except Exception as e:  # keep serving even if a purge fails
            print(f"purge failed: {e}", flush=True)
        time.sleep(6 * 3600)


class Handler(BaseHTTPRequestHandler):
    server_version = "kawa-hub"
    sys_version = ""

    def log_message(self, fmt, *args):  # no client addresses in the journal
        pass

    def client_ip(self):
        # Only a local, trusted proxy may provide this overwritten header.
        if self.client_address[0] in ("127.0.0.1", "::1"):
            return self.headers.get("X-Real-IP", "") or self.client_address[0]
        return self.client_address[0]

    def reply(self, status, body=b"", ctype="application/json", extra=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def reply_json(self, status, obj):
        self.reply(status, json.dumps(obj).encode())

    def do_POST(self):
        if self.path != "/v1/report":
            return self.reply_json(404, {"error": "not found"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if not 0 < length <= MAX_BODY:
            return self.reply_json(413, {"error": "too large"})
        if not allowed(self.client_ip()):
            return self.reply_json(429, {"error": "too many reports"})
        try:
            report = json.loads(self.rfile.read(length))
            if not isinstance(report, dict):
                raise ValueError
        except ValueError:
            return self.reply_json(400, {"error": "bad json"})
        self.reply_json(201, {"id": store(report, self.client_ip())})

    def do_GET(self):
        url = urlsplit(self.path)
        if self.path == "/v1/ip":
            address = public_ip(self.client_ip())
            return self.reply(200 if address else 503, json.dumps({"ip": address, "observed_at": int(time.time())}).encode(), extra={"Cache-Control": "no-store"})
        if url.path == "/v1/update/manifest.sig" and url.query:
            digest = parse_qs(url.query).get("sha256", [""])[0]
            if re.fullmatch(r"[0-9a-f]{64}", digest):
                return self.send_file(os.path.join(UPDATES, "signatures", digest+".sig"), "text/plain", {"Cache-Control": "public, max-age=31536000, immutable"})
            return self.reply_json(400, {"error": "bad digest"})
        if self.path == "/health":
            return self.reply(200, b"ok", "text/plain")
        names = {
            "/v1/update/manifest.json": ("manifest.json", "application/json"),
            "/v1/update/manifest.sig": ("manifest.sig", "text/plain"),
        }
        if self.path in names:
            name, ctype = names[self.path]
            return self.send_file(os.path.join(UPDATES, name), ctype, {"Cache-Control": "no-cache"})
        prefix = "/v1/update/files/"
        if self.path.startswith(prefix):
            name = self.path[len(prefix):]
            if safe_name(name):
                return self.send_file(
                    os.path.join(UPDATES, "files", name),
                    "application/octet-stream",
                    {"Content-Disposition": f'attachment; filename="{name}"'},
                )
        # pacman repository: Server = https://<hub>/arch/$arch
        if self.path.startswith("/arch/"):
            arch, _, name = self.path[len("/arch/"):].partition("/")
            if arch in ("x86_64", "aarch64", "any") and safe_name(name):
                return self.send_file(os.path.join(UPDATES, "arch", arch, name), "application/octet-stream", {})
            if arch == "duoray.asc":
                return self.send_file(os.path.join(UPDATES, "arch", "duoray.asc"), "text/plain", {})
        self.reply_json(404, {"error": "not found"})

    do_HEAD = do_GET

    def send_file(self, path, ctype, extra):
        try:
            f = open(path, "rb")
        except OSError:
            return self.reply_json(404, {"error": "not found"})
        with f:
            size = os.fstat(f.fileno()).st_size
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(size))
            for k, v in extra.items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                shutil.copyfileobj(f, self.wfile, 256 * 1024)


def main():
    init_db()
    threading.Thread(target=purge_forever, daemon=True).start()
    threading.Thread(target=notify_forever, daemon=True).start()
    host, port = LISTEN.rsplit(":", 1)
    server = ThreadingHTTPServer((host, int(port)), Handler)
    server.daemon_threads = True
    print(f"kawa-hub listening on {LISTEN}, state in {STATE}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
