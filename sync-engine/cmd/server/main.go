package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/CamilleHbp/Tasks/sync-engine/tasks"
	core "github.com/CamilleHbp/sync-core"
	"github.com/CamilleHbp/sync-core/privateclient"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	_ "time/tzdata"
)

type account struct {
	Bundle   core.Bundle `json:"bundle"`
	CodeHash string      `json:"codeHash"`
}
type session struct {
	Expires time.Time `json:"expires"`
}
type server struct {
	provisionFile string
	native        *privateclient.Client
	mu            sync.Mutex
	db            *core.Database
	e             *core.Engine
	account       account
	sessions      map[string]session
	attempts      []time.Time
	watchers      map[chan struct{}]struct{}
}

func digest(value string) string { h := sha256.Sum256([]byte(value)); return hex.EncodeToString(h[:]) }
func newServer(directory string) (*server, error) {
	db, err := core.OpenDatabase(filepath.Join(directory, "vervetwodo.sqlite"))
	if err != nil {
		return nil, err
	}
	s := &server{db: db, sessions: map[string]session{}}
	fail := func(err error) (*server, error) { db.Close(); return nil, err }
	if err = db.Get("account", &s.account); errors.Is(err, sql.ErrNoRows) {
		codePath := filepath.Join(directory, "access-code.json")
		raw, err := os.ReadFile(codePath)
		var code string
		if os.IsNotExist(err) {
			code = core.RandomID()
			if err = core.AtomicJSON(codePath, code); err != nil {
				return fail(err)
			}
		} else if err != nil {
			return fail(err)
		} else if err = json.Unmarshal(raw, &code); err != nil {
			return fail(err)
		}
		if len(code) != 48 {
			return fail(errors.New("invalid access-code file"))
		}
		bundles, err := core.NewWorkspace(tasks.Config(), []string{"Owner"})
		if err != nil {
			return fail(err)
		}
		s.account = account{Bundle: bundles[0], CodeHash: digest(code)}
		s.e, err = core.Open(tasks.Config(), bundles[0], nil)
		if err != nil {
			return fail(err)
		}
		if err = db.Commit(s.e, map[string]any{"account": s.account}, false, nil); err != nil {
			return fail(err)
		}
	} else if err != nil {
		return fail(err)
	} else {
		store, err := db.Load()
		if err != nil {
			return fail(err)
		}
		s.e, err = core.Open(tasks.Config(), s.account.Bundle, store)
		if err != nil {
			return fail(err)
		}
	}
	if err = db.Get("sessions", &s.sessions); err != nil && !errors.Is(err, sql.ErrNoRows) {
		return fail(err)
	}
	if err = s.openNative(directory); err != nil {
		return nil, err
	}
	return s, nil
}
func reply(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(value)
}
func problem(w http.ResponseWriter, status int, code string) {
	reply(w, status, map[string]string{"code": code})
}
func decode(w http.ResponseWriter, r *http.Request, out any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 4<<20)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(out); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return errors.New("invalid request")
	}
	return nil
}
func (s *server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/healthz" {
		engine := "legacy-compatibility"
		if s.native != nil {
			engine = "private-any-sync"
		}
		reply(w, 200, map[string]string{"status": "ok", "engine": engine, "namespace": tasks.Namespace})
		return
	}
	if r.Method != "POST" && !(r.Method == "GET" && r.URL.Path == "/api/v1/events") {
		problem(w, 405, "invalid_request")
		return
	}
	if r.Header.Get("X-Sync-Namespace") != tasks.Namespace {
		problem(w, 403, "namespace_mismatch")
		return
	}
	if r.Method == "POST" && !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		problem(w, 415, "invalid_request")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if r.URL.Path == "/api/v1/login" {
		now := time.Now()
		attempts := s.attempts[:0]
		for _, at := range s.attempts {
			if now.Sub(at) < time.Minute {
				attempts = append(attempts, at)
			}
		}
		s.attempts = attempts
		if len(attempts) >= 12 {
			problem(w, 429, "rate_limited")
			return
		}
		var input struct {
			Code string `json:"code"`
		}
		if decode(w, r, &input) != nil {
			problem(w, 400, "invalid_request")
			return
		}
		if subtle.ConstantTimeCompare([]byte(digest(strings.TrimSpace(input.Code))), []byte(s.account.CodeHash)) != 1 {
			s.attempts = append(s.attempts, now)
			problem(w, 401, "invalid_code")
			return
		}
		sessions := map[string]session{}
		for key, value := range s.sessions {
			if value.Expires.After(now) {
				sessions[key] = value
			}
		}
		if len(sessions) >= 100 {
			problem(w, 429, "session_limit")
			return
		}
		token := core.RandomID()
		sessions[digest(token)] = session{Expires: now.Add(30 * 24 * time.Hour)}
		if err := s.db.Put("sessions", sessions); err != nil {
			problem(w, 503, "storage_unavailable")
			return
		}
		s.sessions = sessions
		reply(w, 200, map[string]any{"token": token, "bundle": s.account.Bundle, "native": s.provisionFile != ""})
		return
	}
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		problem(w, 401, "sign_in_required")
		return
	}
	hash := digest(strings.TrimPrefix(auth, "Bearer "))
	active, ok := s.sessions[hash]
	if !ok || !active.Expires.After(time.Now()) {
		problem(w, 401, "sign_in_required")
		return
	}
	if err := s.flushNative(); err != nil {
		problem(w, 503, "storage_unavailable")
		return
	}
	switch r.URL.Path {
	case "/api/v1/events":
		s.events(w, r, hash)
	case "/api/v1/native":
		raw, err := os.ReadFile(s.provisionFile)
		if err != nil {
			problem(w, 404, "not_found")
			return
		}
		var metadata json.RawMessage = raw
		reply(w, 200, metadata)
	case "/api/v1/checkpoint":
		var request core.CheckpointRequest
		if decode(w, r, &request) != nil {
			problem(w, 400, "invalid_request")
			return
		}
		// flushNative above commits the gateway projection before we acknowledge it.
		view, err := s.e.View()
		if err != nil {
			problem(w, 503, "storage_unavailable")
			return
		}
		response, err := core.NewCheckpoint(request, tasks.Config().Namespace, s.account.Bundle.Root.Id, view.Records)
		if err != nil {
			problem(w, 400, "invalid_request")
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		reply(w, 200, response)
	case "/api/v1/sync":
		var request core.SyncRequest
		if decode(w, r, &request) != nil {
			problem(w, 400, "invalid_request")
			return
		}
		next, response, err := core.Exchange(s.e, s.db, request)
		if err != nil {
			log.Printf("sync rejected: %s", core.ErrorCode(err))
			problem(w, 409, core.ErrorCode(err))
			return
		}
		changed := len(s.e.Store.Changes) != len(next.Store.Changes)
		s.e = next
		if err := s.flushNative(); err != nil {
			problem(w, 503, "storage_unavailable")
			return
		}
		if changed {
			s.publishChanges()
		}
		reply(w, 200, response)
	case "/api/v1/logout":
		sessions := map[string]session{}
		for key, value := range s.sessions {
			if key != hash {
				sessions[key] = value
			}
		}
		if s.db.Put("sessions", sessions) != nil {
			problem(w, 503, "storage_unavailable")
			return
		}
		s.sessions = sessions
		s.publishChanges()
		reply(w, 200, map[string]bool{"ok": true})
	default:
		problem(w, 404, "not_found")
	}
}
func main() {
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		c := http.Client{Timeout: 3 * time.Second}
		r, err := c.Get("http://127.0.0.1:8080/healthz")
		if err != nil {
			os.Exit(1)
		}
		r.Body.Close()
		if r.StatusCode != 200 {
			os.Exit(1)
		}
		return
	}
	directory := os.Getenv("DATA_DIR")
	if directory == "" {
		directory = "./data"
	}
	s, err := newServer(directory)
	if err != nil {
		log.Fatal(err)
	}
	defer s.db.Close()
	if len(os.Args) > 1 && os.Args[1] == "prepare-native" {
		if err = s.prepareNative(directory); err != nil {
			log.Fatal(err)
		}
		log.Print("Verified native provisioning written; source data unchanged.")
		return
	}
	address := os.Getenv("LISTEN_ADDR")
	if address == "" {
		address = "127.0.0.1:8790"
	}
	log.Printf("VerveTwoDo sync listening on %s", address)
	h := &http.Server{Addr: address, Handler: s, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16384}
	log.Fatal(h.ListenAndServe())
}
