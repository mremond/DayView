#!/usr/bin/env python3
"""Minimal compare-and-set blob store for DayView state sync (stdlib only).

The server stores one opaque, end-to-end-encrypted blob per user and never
inspects it. It exists only to arbitrate concurrent writes via a revision token.

Protocol (matches the app's HttpSyncTransport):

  GET  /sync/{userId}          Authorization: Bearer <token>
       -> 200 {"revision": "<rev>", "payload": "<blob>"}
       -> 204                                   (nothing stored yet)

  PUT  /sync/{userId}          Authorization: Bearer <token>
       If-None-Match: *        create only if nothing is stored
       If-Match: <rev>         update only if the current revision == <rev>
       body: {"payload": "<blob>"}
       -> 200 {"revision": "<new-rev>"}                       (applied)
       -> 412 {"revision": "<current>", "payload": "<blob>"}  (precondition failed)

  Any request without a valid Bearer token -> 401.

Config via environment:
  SYNC_TOKEN   shared bearer token (default "dev-token" — change for real use)
  SYNC_STORE   path to the JSON persistence file (default "./sync-store.json")
  SYNC_PORT    listen port (default 8787; a CLI arg overrides this)

Revisions are a per-user monotonic counter rendered as a string. Storage is an
in-memory dict mirrored to SYNC_STORE on every write, guarded by a lock. Deploy
behind your existing TLS reverse proxy; this process speaks plain HTTP.
"""

import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("SYNC_TOKEN", "dev-token")
STORE_PATH = os.environ.get("SYNC_STORE", "./sync-store.json")

_lock = threading.Lock()
_store = {}  # userId -> {"revision": str, "payload": str}


def _load():
    global _store
    try:
        with open(STORE_PATH, "r", encoding="utf-8") as f:
            _store = json.load(f)
    except (FileNotFoundError, ValueError):
        _store = {}


def _persist():
    tmp = STORE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(_store, f)
    os.replace(tmp, STORE_PATH)  # atomic


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep stdout quiet; override to enable logging
        pass

    def _authed(self):
        return self.headers.get("Authorization") == f"Bearer {TOKEN}"

    def _user_id(self):
        parts = self.path.strip("/").split("/")
        if len(parts) == 2 and parts[0] == "sync" and parts[1]:
            return parts[1]
        return None

    def _send_json(self, status, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_empty(self, status):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if not self._authed():
            return self._send_empty(401)
        user = self._user_id()
        if user is None:
            return self._send_empty(404)
        with _lock:
            entry = _store.get(user)
        if entry is None:
            return self._send_empty(204)
        self._send_json(200, {"revision": entry["revision"], "payload": entry["payload"]})

    def do_PUT(self):
        if not self._authed():
            return self._send_empty(401)
        user = self._user_id()
        if user is None:
            return self._send_empty(404)

        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8"))["payload"]
        except (ValueError, KeyError, TypeError):
            return self._send_empty(400)

        if_match = self.headers.get("If-Match")
        if_none_match = self.headers.get("If-None-Match")

        with _lock:
            entry = _store.get(user)
            current_rev = entry["revision"] if entry else None

            if if_none_match == "*":
                if entry is not None:  # already exists -> conflict
                    return self._send_json(412, {"revision": entry["revision"], "payload": entry["payload"]})
                new_rev = "1"
            elif if_match is not None:
                if entry is None or entry["revision"] != if_match:  # moved under us
                    return self._send_json(
                        412,
                        {"revision": current_rev or "0", "payload": entry["payload"] if entry else ""},
                    )
                new_rev = str(int(entry["revision"]) + 1)
            else:
                return self._send_empty(428)  # Precondition Required

            _store[user] = {"revision": new_rev, "payload": payload}
            _persist()

        self._send_json(200, {"revision": new_rev})


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else int(os.environ.get("SYNC_PORT", "8787"))
    _load()
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"DayView sync endpoint on :{port} (store={STORE_PATH})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
