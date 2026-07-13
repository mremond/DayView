# DayView sync endpoint

A minimal compare-and-set blob store for DayView state sync, written in Go so it
builds to a **single static, dependency-free Linux binary** you can `scp` to a
server and run — no interpreter, no libc, no runtime.

It stores one opaque, **end-to-end-encrypted** blob per user and arbitrates
concurrent writes with a revision token. It never sees plaintext.

## Protocol

`HttpSyncTransport` in the app speaks exactly this:

| Request | Preconditions | Success | Conflict |
|---|---|---|---|
| `GET /sync/{userId}` | `Authorization: Bearer <token>` | `200 {"revision","payload"}`, or `204` if nothing stored | — |
| `PUT /sync/{userId}` | `If-None-Match: *` (create only if absent) **or** `If-Match: <rev>` (update only if current == rev); body `{"payload":"<blob>"}` | `200 {"revision":"<new>"}` | `412 {"revision":"<current>","payload":"<blob>"}` |

Missing/invalid Bearer token → `401`. A `PUT` with neither precondition → `428`.
Revisions are an opaque per-user monotonic counter (string).

## Build

Requires Go (build host only — the resulting binary needs nothing at runtime).

```bash
make build                 # static linux/amd64 binary -> ./sync-endpoint
make build GOARCH=arm64    # for an arm64 server
```

`make build` runs `CGO_ENABLED=0 GOOS=linux GOARCH=$(GOARCH) go build`. With CGO
off, `net/http` is pure Go, so the binary is fully static (`file` reports
"statically linked") and runs on any Linux of that architecture regardless of
glibc version. Cross-compiles cleanly from macOS.

## Deploy

```bash
scp sync-endpoint your-server:/usr/local/bin/
```

Run it bound to localhost behind your existing TLS reverse proxy (e.g. proxy
`https://<your-host>/dayview-sync/` → `http://127.0.0.1:8787/`). Set a strong
`SYNC_TOKEN`. The app's Sync settings then use
`baseUrl = https://<your-host>/dayview-sync`, the same token, and any `userId`.

A `dayview-sync.service` systemd unit is included (uses `DynamicUser` +
`StateDirectory`, so the store lives under `/var/lib/dayview-sync/`):

```bash
cp dayview-sync.service /etc/systemd/system/     # edit SYNC_TOKEN first
systemctl daemon-reload && systemctl enable --now dayview-sync
```

Config via environment: `SYNC_TOKEN` (bearer token, default `dev-token`),
`SYNC_STORE` (JSON persistence file, default `./sync-store.json`), `SYNC_PORT`
(or first CLI arg, default `8787`).

## Run locally

```bash
SYNC_TOKEN=dev-token make run       # or: go run . 8787
```

## Smoke test

Verified protocol behaviour (all pass against this server):

```bash
H="Authorization: Bearer dev-token"; B="http://localhost:8787/sync/alice"
curl -s -o /dev/null -w "%{http_code}\n" -H "$H" "$B"                              # 204 empty
curl -s -H "$H" -X PUT -H 'If-None-Match: *' -d '{"payload":"BLOB_ONE"}' "$B"      # 200 {"revision":"1"}
curl -s -H "$H" "$B"                                                               # 200 {"revision":"1","payload":"BLOB_ONE"}
curl -s -H "$H" -X PUT -H 'If-None-Match: *' -d '{"payload":"X"}' "$B"             # 412 (already exists)
curl -s -H "$H" -X PUT -H 'If-Match: 99'    -d '{"payload":"X"}' "$B"              # 412 (stale revision)
curl -s -H "$H" -X PUT -H 'If-Match: 1'     -d '{"payload":"BLOB_TWO"}' "$B"       # 200 {"revision":"2"}
curl -s -o /dev/null -w "%{http_code}\n" -H 'Authorization: Bearer nope' "$B"      # 401
```

The compiled `sync-endpoint` binary and the runtime `sync-store.json` are not
committed.
