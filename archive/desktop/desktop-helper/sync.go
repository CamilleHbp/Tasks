package main

import (
	"encoding/json"
	"github.com/CamilleHbp/Tasks/sync-engine/mobile"
	"path/filepath"
	"strings"
	"sync"
)

type updateListener struct{ changed func() }

func (l updateListener) OnChange() {
	if l.changed != nil {
		l.changed()
	}
}

// Network exchanges run independently of stdin requests so offline editing stays responsive.
type syncBridge struct {
	client  *mobile.Client
	mu      sync.Mutex
	busy    bool
	failure string
}

func (s *Store) engineRequest(r Request) Response {
	out := Response{Version: protocolVersion, ID: r.ID}
	if s.sync == nil {
		client, err := mobile.NewClient(filepath.Join(s.directory, "sync-v1"))
		if err != nil {
			out.Error = "sync_storage_failed"
			return out
		}
		s.sync = &syncBridge{client: client}
		client.StartUpdates(updateListener{changed: s.notify})
	}
	b := s.sync
	method := strings.TrimPrefix(r.Method, "engine.")
	if method == "sync" || method == "check" {
		b.mu.Lock()
		if !b.busy {
			b.busy = true
			operation := method
			go func() {
				var result struct {
					Error string `json:"error"`
				}
				json.Unmarshal([]byte(b.client.Call(operation, "{}")), &result)
				b.mu.Lock()
				b.busy = false
				b.failure = result.Error
				b.mu.Unlock()
				// Completion (including an unchanged success or a transport failure) is UI state.
				if s.notify != nil {
					s.notify()
				}
			}()
		}
		b.mu.Unlock()
		method = "state"
	}
	var result map[string]any
	json.Unmarshal([]byte(b.client.Call(method, string(r.Payload))), &result)
	b.mu.Lock()
	result["busy"] = b.busy
	if method == "state" && b.failure != "" && result["error"] == nil {
		result["error"] = b.failure
	}
	b.mu.Unlock()
	out.Sync, _ = json.Marshal(result)
	return out
}
