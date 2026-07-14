package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

// Point persistence at a real writable temp file so persist() exercises its
// real code path (atomic temp-file + rename) instead of being bypassed. Each
// test run gets its own unique file so concurrent `go test` runs don't
// collide and no stray fixed-name file is left behind.
func init() {
	f, err := os.CreateTemp("", "dayview-sync-test-store-*.json")
	if err != nil {
		panic(err)
	}
	f.Close()
	storePath = f.Name()
}

func req(t *testing.T, method, path, body string, headers map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+token)
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	handle(w, r)
	return w
}

func TestHistoryPutThenGet(t *testing.T) {
	store = map[string]entry{}
	put := req(t, http.MethodPut, "/sync/u1/history/abc", `{"payload":"blob1"}`, map[string]string{"If-None-Match": "*"})
	if put.Code != http.StatusCreated {
		t.Fatalf("PUT want 201, got %d", put.Code)
	}
	get := req(t, http.MethodGet, "/sync/u1/history/abc", "", nil)
	if get.Code != http.StatusOK || !strings.Contains(get.Body.String(), "blob1") {
		t.Fatalf("GET want 200 with blob1, got %d %s", get.Code, get.Body.String())
	}
}

func TestHistoryWriteOnceRejectsSecondWrite(t *testing.T) {
	store = map[string]entry{}
	req(t, http.MethodPut, "/sync/u1/history/abc", `{"payload":"first"}`, map[string]string{"If-None-Match": "*"})
	second := req(t, http.MethodPut, "/sync/u1/history/abc", `{"payload":"second"}`, map[string]string{"If-None-Match": "*"})
	if second.Code != http.StatusPreconditionFailed {
		t.Fatalf("second PUT want 412, got %d", second.Code)
	}
	get := req(t, http.MethodGet, "/sync/u1/history/abc", "", nil)
	if !strings.Contains(get.Body.String(), "first") {
		t.Fatalf("write-once violated: %s", get.Body.String())
	}
}

func TestHistoryGetMissingReturns204(t *testing.T) {
	store = map[string]entry{}
	get := req(t, http.MethodGet, "/sync/u1/history/nope", "", nil)
	if get.Code != http.StatusNoContent {
		t.Fatalf("want 204, got %d", get.Code)
	}
}

func TestHistoryPutPreservesMainDoc(t *testing.T) {
	store = map[string]entry{"u1": {Revision: "3", Payload: "doc"}}
	req(t, http.MethodPut, "/sync/u1/history/abc", `{"payload":"blob"}`, map[string]string{"If-None-Match": "*"})
	if store["u1"].Payload != "doc" || store["u1"].Revision != "3" {
		t.Fatalf("main doc clobbered: %+v", store["u1"])
	}
}

// A history PUT for a fresh user must not create a phantom main document.
// Clients upload history blobs before the main-doc push in the same sync cycle,
// so a first-ever sync exercises exactly this ordering.
func TestHistoryWriteDoesNotCreatePhantomMainDoc(t *testing.T) {
	store = map[string]entry{}
	req(t, http.MethodPut, "/sync/u1/history/abc", `{"payload":"blob"}`, map[string]string{"If-None-Match": "*"})

	get := req(t, http.MethodGet, "/sync/u1", "", nil)
	if get.Code != http.StatusNoContent {
		t.Fatalf("main GET after history write want 204, got %d %s", get.Code, get.Body.String())
	}

	put := req(t, http.MethodPut, "/sync/u1", `{"payload":"doc"}`, map[string]string{"If-None-Match": "*"})
	if put.Code != http.StatusOK {
		t.Fatalf("first main PUT want 200 (creates doc), got %d %s", put.Code, put.Body.String())
	}

	// History for that user must survive the main-doc creation.
	hist := req(t, http.MethodGet, "/sync/u1/history/abc", "", nil)
	if !strings.Contains(hist.Body.String(), "blob") {
		t.Fatalf("history lost after main-doc creation: %s", hist.Body.String())
	}
}
