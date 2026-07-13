// Minimal compare-and-set blob store for DayView state sync (Go stdlib only).
//
// The server stores one opaque, end-to-end-encrypted blob per user and never
// inspects it. It exists only to arbitrate concurrent writes via a revision token.
//
// Protocol (matches the app's HttpSyncTransport):
//
//	GET  /sync/{userId}          Authorization: Bearer <token>
//	     -> 200 {"revision","payload"}   or   204 (nothing stored yet)
//
//	PUT  /sync/{userId}          Authorization: Bearer <token>
//	     If-None-Match: *        create only if nothing is stored
//	     If-Match: <rev>         update only if the current revision == <rev>
//	     body: {"payload":"<blob>"}
//	     -> 200 {"revision":"<new>"}                       (applied)
//	     -> 412 {"revision":"<current>","payload":"<blob>"} (precondition failed)
//
// Any request without a valid Bearer token -> 401. A PUT with neither
// precondition -> 428.
//
// Config via environment: SYNC_TOKEN (bearer token, default "dev-token"),
// SYNC_STORE (JSON persistence file, default "./sync-store.json"),
// SYNC_PORT (listen port, default 8787; a CLI arg overrides it).
//
// Deploy behind your existing TLS reverse proxy; this process speaks plain HTTP.
// Build a static, dependency-free Linux binary with:
//
//	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o sync-endpoint
package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
)

type entry struct {
	Revision string `json:"revision"`
	Payload  string `json:"payload"`
}

var (
	mu        sync.Mutex
	store     = map[string]entry{}
	token     = envOr("SYNC_TOKEN", "dev-token")
	storePath = envOr("SYNC_STORE", "./sync-store.json")
)

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// load reads the persisted store; a missing or malformed file yields an empty store.
func load() {
	b, err := os.ReadFile(storePath)
	if err != nil {
		return
	}
	if err := json.Unmarshal(b, &store); err != nil {
		store = map[string]entry{}
	}
}

// persist writes the store atomically (temp file + rename) with owner-only perms.
func persist() error {
	b, err := json.Marshal(store)
	if err != nil {
		return err
	}
	tmp := storePath + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, storePath)
}

// userID extracts {userId} from a /sync/{userId} path.
func userID(path string) (string, bool) {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) == 2 && parts[0] == "sync" && parts[1] != "" {
		return parts[1], true
	}
	return "", false
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func handle(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer "+token {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	uid, ok := userID(r.URL.Path)
	if !ok {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	switch r.Method {
	case http.MethodGet:
		mu.Lock()
		e, exists := store[uid]
		mu.Unlock()
		if !exists {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		writeJSON(w, http.StatusOK, entry{Revision: e.Revision, Payload: e.Payload})

	case http.MethodPut:
		var body struct {
			Payload *string `json:"payload"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Payload == nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		ifMatch := r.Header.Get("If-Match")
		ifNoneMatch := r.Header.Get("If-None-Match")

		mu.Lock()
		defer mu.Unlock()
		e, exists := store[uid]

		var newRev string
		switch {
		case ifNoneMatch == "*":
			if exists { // already exists -> conflict
				writeJSON(w, http.StatusPreconditionFailed, entry{Revision: e.Revision, Payload: e.Payload})
				return
			}
			newRev = "1"
		case ifMatch != "":
			if !exists || e.Revision != ifMatch { // moved under us
				cur := entry{Revision: "0"}
				if exists {
					cur = e
				}
				writeJSON(w, http.StatusPreconditionFailed, cur)
				return
			}
			n, _ := strconv.Atoi(e.Revision)
			newRev = strconv.Itoa(n + 1)
		default:
			w.WriteHeader(http.StatusPreconditionRequired) // 428
			return
		}

		store[uid] = entry{Revision: newRev, Payload: *body.Payload}
		if err := persist(); err != nil {
			log.Printf("persist: %v", err)
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"revision": newRev})

	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func main() {
	port := envOr("SYNC_PORT", "8787")
	if len(os.Args) > 1 {
		port = os.Args[1]
	}
	load()
	mux := http.NewServeMux()
	mux.HandleFunc("/", handle)
	log.Printf("DayView sync endpoint on :%s (store=%s)", port, storePath)
	log.Fatal(http.ListenAndServe(":"+port, mux))
}
