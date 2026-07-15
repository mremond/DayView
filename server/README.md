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

### Add a device

The app uses a short-lived enrollment code so the QR code never contains a
long-lived access token:

| Request | Authorization | Success |
|---|---|---|
| `POST /pairing` with `{"userId":"…"}` | Existing admin or device Bearer token | `201 {"code":"…","expiresAtEpochSeconds":…}` |
| `POST /pairing/claim` with `{"code":"…"}` | The single-use code itself | `200 {"userId":"…","token":"…"}` |

Enrollment codes expire after two minutes, live only in server memory, and are
deleted on their first claim attempt. The resulting token is bound to one user;
only its SHA-256 hash is persisted. `SYNC_TOKEN` remains the administrator/
bootstrap credential and can create the first device enrollment.

Pairing must be exposed over HTTPS: the claim response contains the new device
credential. The synchronized document remains end-to-end encrypted and the
server never receives the encryption key (it travels directly in the QR code).

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

## Deploy on Synology (Container Manager)

DSM restricts user-managed systemd, so the container route is the natural fit.
A `Dockerfile` (multi-stage: build → `scratch`) and `docker-compose.yml` are
included, so no Go toolchain is needed on your Mac — Container Manager builds the
static binary itself, natively for the NAS's amd64 architecture.

1. Put this `server/` folder on the NAS (Git, or copy via File Station), and
   create the store's host folder (e.g. `/volume1/docker/dayview-sync`).
2. Edit `docker-compose.yml`: set a strong `SYNC_TOKEN`, and adjust the volume's
   host path if you didn't use `/volume1/docker/dayview-sync`.
3. Container Manager → **Project** → **Create** → point it at this folder → Build
   & Run. It listens on `http://<nas-host>:8787` and restarts automatically.

Plain HTTP is acceptable only when a trusted VPN already encrypts the complete
transport, including pairing credentials. Otherwise expose the endpoint over
HTTPS: although synchronized data is end-to-end encrypted, authentication
tokens are not. The app's Sync settings then use the endpoint URL, the same
`SYNC_TOKEN`, and any `userId`. (Android blocks cleartext HTTP by default, so a
direct `http://` endpoint needs a scoped `network_security_config` entry for
that host in the app.)

## Deploy (generic Linux, systemd)

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

The persistence loader automatically accepts the legacy flat `sync-store.json`
format and rewrites it to the version that also contains hashed device tokens on
the next successful write.

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
