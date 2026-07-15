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
// Per-day write-once history archive, keyed by an opaque key (blind to the
// server):
//
//	PUT  /sync/{userId}/history/{opaqueKey}   Authorization: Bearer <token>
//	     If-None-Match: *        required; write-once, first writer wins
//	     body: {"payload":"<blob>"}
//	     -> 201 (created)   412 (key already exists)   428 (missing precondition)
//
//	GET  /sync/{userId}/history/{opaqueKey}   Authorization: Bearer <token>
//	     -> 200 {"payload":"<blob>"}   or   204 (nothing stored for that key)
//
// A configured device can enroll another one without putting its long-lived
// credential in the QR code:
//
//	POST /pairing        Authorization: Bearer <token>
//	     body: {"userId":"<user>"} -> 201 {"code","expiresAtEpochSeconds"}
//
//	POST /pairing/claim  body: {"code":"<single-use code>"}
//	     -> 200 {"userId","token"} or 410 (expired/already consumed)
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
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type entry struct {
	Revision string            `json:"revision"`
	Payload  string            `json:"payload"`
	History  map[string]string `json:"history,omitempty"`
}

type persistedState struct {
	Entries      map[string]entry  `json:"entries"`
	DeviceTokens map[string]string `json:"deviceTokens,omitempty"` // SHA-256(token) -> user ID
}

type pairingSession struct {
	UserID    string
	ExpiresAt time.Time
}

var (
	mu           sync.Mutex
	store        = map[string]entry{}
	deviceTokens = map[string]string{}
	pairings     = map[string]pairingSession{}
	token        = envOr("SYNC_TOKEN", "dev-token")
	storePath    = envOr("SYNC_STORE", "./sync-store.json")
)

const pairingTTL = 2 * time.Minute

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// load reads both the current wrapped format and the legacy user->entry map.
func load() {
	b, err := os.ReadFile(storePath)
	if err != nil {
		return
	}
	var state persistedState
	if err := json.Unmarshal(b, &state); err == nil && state.Entries != nil {
		store = state.Entries
		deviceTokens = state.DeviceTokens
		if deviceTokens == nil {
			deviceTokens = map[string]string{}
		}
		return
	}
	var legacy map[string]entry
	if err := json.Unmarshal(b, &legacy); err == nil {
		store = legacy
		deviceTokens = map[string]string{}
		return
	}
	store = map[string]entry{}
	deviceTokens = map[string]string{}
}

// persist writes the store atomically (temp file + rename) with owner-only perms.
func persist() error {
	b, err := json.Marshal(persistedState{Entries: store, DeviceTokens: deviceTokens})
	if err != nil {
		return err
	}
	tmp := storePath + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, storePath)
}

func secureEqual(a, b string) bool {
	return len(a) == len(b) && subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func tokenHash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func bearerToken(r *http.Request) string {
	return strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
}

func authorizedForUser(r *http.Request, uid string) bool {
	presented := bearerToken(r)
	if presented == "" {
		return false
	}
	if secureEqual(presented, token) {
		return true
	}
	mu.Lock()
	boundUser := deviceTokens[tokenHash(presented)]
	mu.Unlock()
	return boundUser == uid
}

func randomToken(size int) (string, error) {
	b := make([]byte, size)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// userID extracts {userId} from a /sync/{userId} path.
func userID(path string) (string, bool) {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) == 2 && parts[0] == "sync" && parts[1] != "" {
		return parts[1], true
	}
	return "", false
}

// historyRef extracts {userId},{opaqueKey} from a /sync/{userId}/history/{opaqueKey} path.
func historyRef(path string) (uid, key string, ok bool) {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) == 4 && parts[0] == "sync" && parts[2] == "history" && parts[1] != "" && parts[3] != "" {
		return parts[1], parts[3], true
	}
	return "", "", false
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func handle(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/pairing" {
		handlePairingCreate(w, r)
		return
	}
	if r.URL.Path == "/pairing/claim" {
		handlePairingClaim(w, r)
		return
	}
	if uid, key, ok := historyRef(r.URL.Path); ok {
		if !authorizedForUser(r, uid) {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		handleHistory(w, r, uid, key)
		return
	}
	uid, ok := userID(r.URL.Path)
	if !ok {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	if !authorizedForUser(r, uid) {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}

	switch r.Method {
	case http.MethodGet:
		mu.Lock()
		e, exists := store[uid]
		mu.Unlock()
		// A real main document always has a non-empty revision (>= "1"); an
		// entry present only to hold history has Revision == "" and counts as
		// "no main document stored yet".
		if !exists || e.Revision == "" {
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
		// A real main document has a non-empty revision. An entry present only
		// to hold history (Revision == "") is treated as "no main doc yet".
		hasDoc := exists && e.Revision != ""

		var newRev string
		switch {
		case ifNoneMatch == "*":
			if hasDoc { // already exists -> conflict
				writeJSON(w, http.StatusPreconditionFailed, entry{Revision: e.Revision, Payload: e.Payload})
				return
			}
			newRev = "1"
		case ifMatch != "":
			if !hasDoc || e.Revision != ifMatch { // moved under us
				cur := entry{Revision: "0"}
				if hasDoc {
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

		// Preserve any history already stored for this user.
		e.Revision = newRev
		e.Payload = *body.Payload
		store[uid] = e
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

// handlePairingCreate issues a short-lived, single-use enrollment code. The
// caller must already hold a credential valid for the requested user.
func handlePairingCreate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		UserID string `json:"userId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.UserID == "" {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	if !authorizedForUser(r, body.UserID) {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	code, err := randomToken(24)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	expiresAt := time.Now().Add(pairingTTL)
	mu.Lock()
	for ref, session := range pairings {
		if time.Now().After(session.ExpiresAt) {
			delete(pairings, ref)
		}
	}
	pairings[tokenHash(code)] = pairingSession{UserID: body.UserID, ExpiresAt: expiresAt}
	mu.Unlock()
	writeJSON(w, http.StatusCreated, map[string]any{
		"code":                  code,
		"expiresAtEpochSeconds": expiresAt.Unix(),
	})
}

// handlePairingClaim consumes an enrollment code and returns a credential bound
// to that user. Only the hash of the new device token is persisted.
func handlePairingClaim(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Code == "" {
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	mu.Lock()
	ref := tokenHash(body.Code)
	session, ok := pairings[ref]
	delete(pairings, ref) // one attempt consumes the code
	if !ok || time.Now().After(session.ExpiresAt) {
		mu.Unlock()
		w.WriteHeader(http.StatusGone)
		return
	}
	deviceToken, err := randomToken(32)
	if err != nil {
		mu.Unlock()
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	deviceTokens[tokenHash(deviceToken)] = session.UserID
	if err := persist(); err != nil {
		delete(deviceTokens, tokenHash(deviceToken))
		mu.Unlock()
		log.Printf("persist pairing: %v", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]string{"userId": session.UserID, "token": deviceToken})
}

func handleHistory(w http.ResponseWriter, r *http.Request, uid, key string) {
	switch r.Method {
	case http.MethodGet:
		mu.Lock()
		blob, ok := store[uid].History[key]
		mu.Unlock()
		if !ok {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"payload": blob})

	case http.MethodPut:
		var body struct {
			Payload *string `json:"payload"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Payload == nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if r.Header.Get("If-None-Match") != "*" {
			w.WriteHeader(http.StatusPreconditionRequired) // 428
			return
		}
		mu.Lock()
		defer mu.Unlock()
		e := store[uid]
		if _, exists := e.History[key]; exists {
			w.WriteHeader(http.StatusPreconditionFailed) // write-once: first writer wins
			return
		}
		if e.History == nil {
			e.History = map[string]string{}
		}
		e.History[key] = *body.Payload
		store[uid] = e
		if err := persist(); err != nil {
			log.Printf("persist: %v", err)
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusCreated)

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
