#!/usr/bin/env python3
"""Long-polling bridge for BYOB Telegram bots when webhooks are unreliable."""

from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ENV_FILE = Path(os.environ.get("LOYALTY_ENV_FILE", os.path.expanduser("~/loyalty-bot/.env")))
WEBHOOK_BASE = os.environ.get("POLLER_WEBHOOK_BASE", "http://127.0.0.1:8080/tg/webhook")
POLL_TIMEOUT = int(os.environ.get("POLLER_TIMEOUT_SEC", "5"))
BOT_REFRESH_SEC = int(os.environ.get("POLLER_BOT_REFRESH_SEC", "30"))
FORWARD_TIMEOUT = int(os.environ.get("POLLER_FORWARD_TIMEOUT_SEC", "15"))


def load_env() -> dict[str, str]:
    env: dict[str, str] = {}
    if not ENV_FILE.exists():
        raise FileNotFoundError(f"Env file not found: {ENV_FILE}")
    for line in ENV_FILE.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            env[key] = value
    return env


def decrypt_token(encrypted: str, key_b64: str) -> str:
    key = base64.b64decode(key_b64)
    data = base64.b64decode(encrypted)
    return AESGCM(key).decrypt(data[:12], data[12:], None).decode()


def psql(query: str) -> str:
    return subprocess.check_output(
        ["docker", "exec", "loyalty-db", "psql", "-U", "postgres", "-d", "loyalty_db", "-t", "-A", "-c", query],
        text=True,
    ).strip()


def load_bots(key_b64: str) -> list[dict]:
    rows = psql(
        "SELECT id, bot_token, webhook_secret FROM bot_instances "
        "WHERE is_active = true AND status = 'ACTIVE' AND platform = 'TELEGRAM';"
    )
    bots: list[dict] = []
    if not rows:
        return bots
    for row in rows.splitlines():
        bot_id, token_enc, secret = row.split("|", 2)
        bots.append(
            {
                "id": int(bot_id),
                "token": decrypt_token(token_enc, key_b64),
                "secret": secret,
            }
        )
    return bots


def tg_call(token: str, method: str, payload: dict | None = None, timeout: int | None = None) -> dict:
    wait = timeout if timeout is not None else POLL_TIMEOUT + 10
    url = f"https://api.telegram.org/bot{token}/{method}"
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {"Content-Type": "application/json"} if payload is not None else {}
    req = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=wait) as resp:
        return json.loads(resp.read().decode())


def delete_webhook(token: str) -> None:
    tg_call(token, "deleteWebhook", {"drop_pending_updates": False}, timeout=20)


def get_updates(token: str, offset: int) -> list[dict]:
    query = (
        f"https://api.telegram.org/bot{token}/getUpdates"
        f"?offset={offset}&timeout={POLL_TIMEOUT}"
        f"&allowed_updates={urllib.parse.quote(json.dumps(['message', 'callback_query', 'my_chat_member']))}"
    )
    with urllib.request.urlopen(query, timeout=POLL_TIMEOUT + 5) as resp:
        body = json.loads(resp.read().decode())
    if not body.get("ok"):
        return []
    return body.get("result") or []


def forward_update(bot_id: int, secret: str, update: dict) -> None:
    url = f"{WEBHOOK_BASE}/{bot_id}/{secret}"
    data = json.dumps(update).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=FORWARD_TIMEOUT) as resp:
        resp.read()


class BotPoller(threading.Thread):
    def __init__(self, bot: dict) -> None:
        super().__init__(name=f"poller-bot-{bot['id']}", daemon=True)
        self.bot_id = bot["id"]
        self.token = bot["token"]
        self.secret = bot["secret"]
        self.offset = 0
        self._stop = threading.Event()
        self._webhook_deleted = False

    def stop(self) -> None:
        self._stop.set()

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                if not self._webhook_deleted:
                    try:
                        delete_webhook(self.token)
                        self._webhook_deleted = True
                        print(f"webhook deleted for bot {self.bot_id}", flush=True)
                    except Exception as exc:
                        print(f"deleteWebhook failed for bot {self.bot_id}: {exc}", file=sys.stderr, flush=True)
                        time.sleep(2)
                        continue

                updates = get_updates(self.token, self.offset)
                for update in updates:
                    update_id = int(update["update_id"])
                    try:
                        forward_update(self.bot_id, self.secret, update)
                    except Exception as exc:
                        print(
                            f"forward failed bot={self.bot_id} update={update_id}: {exc}",
                            file=sys.stderr,
                            flush=True,
                        )
                        continue
                    self.offset = update_id + 1
            except Exception as exc:
                print(f"poll error bot={self.bot_id}: {exc}", file=sys.stderr, flush=True)
                time.sleep(1)


def main() -> int:
    env = load_env()
    key_b64 = env.get("ENCRYPTION_KEY")
    if not key_b64:
        print("ENCRYPTION_KEY missing", file=sys.stderr)
        return 1

    workers: dict[int, BotPoller] = {}
    print("telegram-bot-poller started (parallel)", flush=True)

    try:
        while True:
            try:
                bots = {bot["id"]: bot for bot in load_bots(key_b64)}
            except Exception as exc:
                print(f"load bots failed: {exc}", file=sys.stderr, flush=True)
                time.sleep(5)
                continue

            for bot_id, worker in list(workers.items()):
                if bot_id not in bots:
                    worker.stop()
                    del workers[bot_id]

            for bot_id, bot in bots.items():
                if bot_id not in workers:
                    worker = BotPoller(bot)
                    workers[bot_id] = worker
                    worker.start()
                    print(f"started poller for bot {bot_id}", flush=True)

            time.sleep(BOT_REFRESH_SEC)
    except KeyboardInterrupt:
        for worker in workers.values():
            worker.stop()
        print("stopped", flush=True)
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
