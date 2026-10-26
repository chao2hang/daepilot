#!/usr/bin/env python3
"""Private receiver for manually uploaded DaePilot logs.

Listens on loopback only; Caddy exposes POST /api/logs through the public
download site. Uploaded logs are never served back over HTTP.
"""

from __future__ import annotations

import json
import os
import random
import re
import string
import sys
import threading
import time
from collections import defaultdict, deque
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

BIND = ("127.0.0.1", 9901)
LOG_DIR = Path("/home/chaos/projects/daepilot/dist/logs")
MAX_BODY = 2 * 1024 * 1024
RATE_LIMIT = 30
RATE_WINDOW = 3600.0

_rate_lock = threading.Lock()
_rate: dict[str, deque[float]] = defaultdict(deque)
_field_re = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def clean(value: object, limit: int = 400) -> str:
    text = str(value if value is not None else "")
    return _field_re.sub("", text).strip()[:limit]


def client_ip(handler: BaseHTTPRequestHandler) -> str:
    forwarded = handler.headers.get("X-Forwarded-For", "")
    if forwarded:
        return forwarded.split(",")[0].strip()[:64]
    return handler.client_address[0]


def allow(ip: str) -> bool:
    now = time.monotonic()
    with _rate_lock:
        bucket = _rate[ip]
        while bucket and now - bucket[0] > RATE_WINDOW:
            bucket.popleft()
        if len(bucket) >= RATE_LIMIT:
            return False
        bucket.append(now)
        return True


def new_id() -> str:
    suffix = "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(6))
    return f"{datetime.now():%Y%m%d-%H%M%S}-{suffix}"


class Handler(BaseHTTPRequestHandler):
    server_version = "DaePilotLogs/1"
    sys_version = ""

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.address_string()} {fmt % args}", flush=True)

    def reply(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if urlsplit(self.path).path == "/api/health":
            self.reply(200, {"ok": True, "service": "daepilot-logs"})
        else:
            self.reply(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:
        if urlsplit(self.path).path != "/api/logs":
            self.reply(404, {"ok": False, "error": "not found"})
            return

        ip = client_ip(self)
        if not allow(ip):
            self.reply(429, {"ok": False, "error": "上传过于频繁，请稍后再试"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0:
            self.reply(411, {"ok": False, "error": "缺少请求体"})
            return
        if length > MAX_BODY:
            self.reply(413, {"ok": False, "error": "日志文件过大"})
            return

        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("not an object")
            log = payload.get("log", "")
            if not isinstance(log, str):
                log = ""
            config = payload.get("config", "")
            events = payload.get("events", "")
            diagnostics = payload.get("diagnostics", "")
            if not isinstance(config, str):
                config = ""
            if not isinstance(events, str):
                events = ""
            if not isinstance(diagnostics, str):
                diagnostics = ""
            if not log.strip() and not config.strip() and not events.strip():
                self.reply(400, {"ok": False, "error": "日志内容为空"})
                return
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            self.reply(400, {"ok": False, "error": "请求格式错误"})
            return

        upload_id = new_id()
        received = datetime.now().astimezone().isoformat(timespec="seconds")
        header = {
            "app": clean(payload.get("app"), 80),
            "versionCode": clean(payload.get("versionCode"), 20),
            "device": clean(payload.get("device"), 120),
            "manufacturer": clean(payload.get("manufacturer"), 80),
            "android": clean(payload.get("android"), 80),
            "abi": clean(payload.get("abi"), 40),
            "mode": clean(payload.get("mode"), 40),
            "daeVersion": clean(payload.get("daeVersion"), 40),
            "moduleVersion": clean(payload.get("moduleVersion"), 40),
            "status": clean(payload.get("status"), 500),
            "note": clean(payload.get("note"), 200),
            "diagnostics": clean(diagnostics, 1000),
        }

        lines = [f"DaePilot log {upload_id}", f"received: {received}", f"remote: {clean(ip, 64)}"]
        lines += [f"{key}: {value}" for key, value in header.items()]
        if events.strip():
            lines.append("")
            lines.append("--- events ---")
            lines.append(_field_re.sub("", events)[:80000])
        if config.strip():
            lines.append("")
            lines.append("--- config (redacted) ---")
            lines.append(_field_re.sub("", config)[:200000])
        lines.append("")
        lines.append("--- log ---")
        body = "\n".join(lines) + "\n" + _field_re.sub("", log)

        try:
            LOG_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
            os.chmod(LOG_DIR, 0o700)
            path = LOG_DIR / f"{upload_id}.txt"
            with open(path, "x", encoding="utf-8") as handle:
                handle.write(body)
            os.chmod(path, 0o600)
        except OSError as error:
            print(f"write failed: {error}", file=sys.stderr, flush=True)
            self.reply(500, {"ok": False, "error": "服务器保存失败"})
            return

        print(f"saved {upload_id} from {ip} ({len(body)} bytes)", flush=True)
        self.reply(200, {"ok": True, "id": upload_id})


def main() -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(LOG_DIR, 0o700)
    server = ThreadingHTTPServer(BIND, Handler)
    print(f"listening on http://{BIND[0]}:{BIND[1]}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
