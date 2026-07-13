# DayView sync endpoint

A minimal compare-and-set blob store for DayView state sync. It stores one
opaque, end-to-end-encrypted blob per user and arbitrates concurrent writes with
a revision token. **It never sees plaintext** — the app encrypts before upload
and decrypts after download.

`sync-endpoint.py` is a dependency-free reference (Python 3 stdlib). The protocol
is the contract; port or reimplement it in any language your server prefers.

## Protocol

`HttpSyncTransport` in the app speaks exactly this:

| Request | Preconditions | Success | Conflict |
|---|---|---|---|
| `GET /sync/{userId}` | `Authorization: Bearer <token>` | `200 {"revision","payload"}`, or `204` if nothing stored | — |
| `PUT /sync/{userId}` | `If-None-Match: *` (create only if absent) **or** `If-Match: <rev>` (update only if current == rev); body `{"payload":"<blob>"}` | `200 {"revision":"<new>"}` | `412 {"revision":"<current>","payload":"<blob>"}` |

Missing/invalid Bearer token → `401`. A `PUT` with neither precondition → `428`.
Revisions are an opaque per-user monotonic counter (string).

## Run locally

```bash
SYNC_TOKEN=dev-token SYNC_STORE=/tmp/dayview-sync.json python3 server/sync-endpoint.py 8787
```

Environment: `SYNC_TOKEN` (bearer token), `SYNC_STORE` (JSON persistence file,
default `./sync-store.json`), `SYNC_PORT` (or first CLI arg, default `8787`).

## Deploy on the existing 24/7 server

Run the process bound to localhost behind your existing TLS reverse proxy, e.g.
proxy `https://<your-host>/dayview-sync/` → `http://127.0.0.1:8787/`. Set a strong
`SYNC_TOKEN`. The app's Sync settings then use `baseUrl = https://<your-host>/dayview-sync`,
the same token, and any `userId` you pick. Keep the process supervised
(systemd / your usual runner) and the `SYNC_STORE` file on persistent disk.

Because payloads are E2EE, the server needs no knowledge of the schema and never
requires updates when the app's data model changes.

## Smoke test

Verified protocol behaviour (all pass against the reference):

```bash
H="Authorization: Bearer dev-token"; B="http://localhost:8787/sync/alice"
curl -s -o /dev/null -w "%{http_code}\n" -H "$H" "$B"                                              # 204 empty
curl -s -H "$H" -X PUT -H 'If-None-Match: *' -d '{"payload":"BLOB_ONE"}' "$B"                      # 200 {"revision":"1"}
curl -s -H "$H" "$B"                                                                               # 200 {"revision":"1","payload":"BLOB_ONE"}
curl -s -H "$H" -X PUT -H 'If-None-Match: *' -d '{"payload":"X"}' "$B"                             # 412 (already exists)
curl -s -H "$H" -X PUT -H 'If-Match: 99'    -d '{"payload":"X"}' "$B"                              # 412 (stale revision)
curl -s -H "$H" -X PUT -H 'If-Match: 1'     -d '{"payload":"BLOB_TWO"}' "$B"                       # 200 {"revision":"2"}
curl -s -o /dev/null -w "%{http_code}\n" -H 'Authorization: Bearer nope' "$B"                      # 401
```

`sync-store.json` (and its `.tmp`) are runtime state — do not commit them.
