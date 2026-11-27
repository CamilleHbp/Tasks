package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type fakeEngine struct{ calls []string }

func (e *fakeEngine) Call(method, payload string) string {
	e.calls = append(e.calls, method)
	return `{"state":{"authenticated":true,"records":[]},"telemetry":{"confirmed":true}}`
}
func setup(t *testing.T) *gateway {
	t.Helper()
	dir := t.TempDir()
	return &gateway{engine: &fakeEngine{}, origin: "https://web.example", upstream: "https://sync.example", directory: dir, assets: dir, secure: true, sessions: map[string]int64{hash("valid"): time.Now().Add(time.Hour).Unix()}}
}
func request(g *gateway, method, path, origin, cookie, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Origin", origin)
	r.Header.Set("Content-Type", "application/json")
	if cookie != "" {
		r.AddCookie(&http.Cookie{Name: "vervetwodo_session", Value: cookie})
	}
	w := httptest.NewRecorder()
	g.ServeHTTP(w, r)
	return w
}
func TestOriginAndAuthentication(t *testing.T) {
	g := setup(t)
	for _, test := range []struct {
		origin, cookie string
		status         int
	}{{"https://attacker.example", "valid", 403}, {"", "valid", 403}, {g.origin, "", 401}, {g.origin, "wrong", 401}, {g.origin, "valid", 200}} {
		r := request(g, "POST", "/api/state", test.origin, test.cookie, "{}")
		if r.Code != test.status {
			t.Fatalf("%+v got %d", test, r.Code)
		}
		if r.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("private data cacheable")
		}
	}
}
func TestNoProvisionOrArbitraryEngineAccess(t *testing.T) {
	g := setup(t)
	for _, path := range []string{"native", "connect", "../data/sessions.json"} {
		if request(g, "POST", "/api/"+path, g.origin, "valid", "{}").Code != 404 {
			t.Fatal(path)
		}
	}
	if len(g.engine.(*fakeEngine).calls) != 0 {
		t.Fatal("unapproved engine method called")
	}
}
func TestLogoutRevokesAndPersistsSession(t *testing.T) {
	g := setup(t)
	r := request(g, "POST", "/api/logout", g.origin, "valid", "{}")
	if r.Code != 200 {
		t.Fatal(r.Code)
	}
	if request(g, "POST", "/api/state", g.origin, "valid", "{}").Code != 401 {
		t.Fatal("session still valid")
	}
	var saved map[string]int64
	b, _ := os.ReadFile(filepath.Join(g.directory, "sessions.json"))
	if json.Unmarshal(b, &saved) != nil || len(saved) != 0 {
		t.Fatal("revocation not saved")
	}
	c := r.Result().Cookies()[0]
	if !c.Secure || !c.HttpOnly || c.SameSite != http.SameSiteStrictMode {
		t.Fatal("insecure cookie")
	}
}
func TestMalformedAndOversizeBody(t *testing.T) {
	g := setup(t)
	for _, body := range []string{"{} {}", strings.Repeat("x", 220000), `{"operations":[]}`} {
		if request(g, "POST", "/api/apply", g.origin, "valid", body).Code != 400 {
			t.Fatal("invalid payload accepted")
		}
	}
}
func TestStaticFilesDoNotExposeDirectories(t *testing.T) {
	g := setup(t)
	_ = os.Mkdir(filepath.Join(g.assets, "assets"), 0700)
	_ = os.WriteFile(filepath.Join(g.assets, "index.html"), []byte("shell"), 0600)
	for _, path := range []string{"/assets/", "/../sessions.json", "/.hidden"} {
		if request(g, "GET", path, "", "", "").Code != 404 {
			t.Fatal(path)
		}
	}
	r := request(g, "GET", "/", "", "", "")
	if r.Code != 200 || r.Header().Get("Content-Security-Policy") == "" {
		t.Fatal("missing protected shell")
	}
}
func TestExpiredSessionRejected(t *testing.T) {
	g := setup(t)
	g.sessions[hash("valid")] = time.Now().Add(-time.Second).Unix()
	if request(g, "POST", "/api/state", g.origin, "valid", "{}").Code != 401 {
		t.Fatal("expired session accepted")
	}
}
func TestLoginRateLimit(t *testing.T) {
	g := setup(t)
	for i := 0; i < 10; i++ {
		g.attempts = append(g.attempts, time.Now())
	}
	if request(g, "POST", "/api/login", g.origin, "", `{"code":"bad"}`).Code != 429 {
		t.Fatal("missing limit")
	}
}

func TestLegacyOriginTransition(t *testing.T) {
	g := setup(t)
	g.legacyOrigin = "https://legacy.example"
	g.migrationOrigin = "https://old-tasks.example"
	for _, test := range []struct {
		origin string
		status int
	}{
		{g.origin, 200}, {g.legacyOrigin, 200}, {g.migrationOrigin, 200}, {"", 403},
		{"https://old-tasks.example.attacker.example", 403},
		{"https://legacy.example.attacker.example", 403}, {"https://attacker.example", 403},
	} {
		if r := request(g, "POST", "/api/state", test.origin, "valid", "{}"); r.Code != test.status {
			t.Errorf("origin %q: got %d, want %d", test.origin, r.Code, test.status)
		}
	}
}

func TestSyncDiscovery(t *testing.T) {
	g := setup(t)
	r := request(g, "GET", "/.well-known/vervetwodo-sync", "", "", "")
	var body map[string]string
	if r.Code != 200 || json.Unmarshal(r.Body.Bytes(), &body) != nil || body["syncOrigin"] != g.origin || body["primaryWorkspace"] != g.upstream || body["publicOrigin"] != g.origin {
		t.Fatalf("%d %s", r.Code, r.Body.String())
	}
	if r := request(g, "POST", "/.well-known/vervetwodo-sync", g.origin, "", "{}"); r.Code != 405 {
		t.Fatal(r.Code)
	}
}

func TestWorkspaceRoutingKeepsSessionsAndReplicasSeparate(t *testing.T) {
	primary, second := setup(t), setup(t)
	second.upstream = "https://second.example"
	second.sessionName = "tasks_session_second"
	ws := workspaces{primary: primary, byOrigin: map[string]*gateway{second.upstream: second}}
	call := func(origin, cookie string) int {
		r := httptest.NewRequest("POST", "/api/state?workspace="+origin, strings.NewReader("{}"))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Origin", primary.origin)
		r.AddCookie(&http.Cookie{Name: cookie, Value: "valid"})
		w := httptest.NewRecorder()
		ws.ServeHTTP(w, r)
		return w.Code
	}
	if call(second.upstream, primary.cookieName()) != 401 || call(primary.upstream, second.cookieName()) != 401 {
		t.Fatal("cross-workspace cookie accepted")
	}
	if call("https://unknown.example", primary.cookieName()) != 400 {
		t.Fatal("unconfigured server accepted")
	}
	if call(second.upstream, second.cookieName()) != 200 || len(second.engine.(*fakeEngine).calls) != 1 || len(primary.engine.(*fakeEngine).calls) != 0 {
		t.Fatal("wrong replica")
	}
	if call(primary.origin, primary.cookieName()) != 200 {
		t.Fatal("old web address unavailable")
	}
}
