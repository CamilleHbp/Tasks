package main

import (
	"fmt"
	"net/http"
	"time"
)

// publishChanges runs under s.mu after a durable commit. Coalesce notifications:
// clients fetch every page after their durable cursor, not individual events.
func (s *server) publishChanges() {
	for ch := range s.watchers {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}

// events is entered and returned with s.mu held, but never holds it while streaming.
func (s *server) events(w http.ResponseWriter, r *http.Request, sessionHash string) {
	if r.Method != http.MethodGet {
		w.WriteHeader(405)
		return
	}
	if _, ok := w.(http.Flusher); !ok {
		w.WriteHeader(500)
		return
	}
	if s.watchers == nil {
		s.watchers = make(map[chan struct{}]struct{})
	}
	ch := make(chan struct{}, 1)
	s.watchers[ch] = struct{}{}
	s.mu.Unlock()
	defer func() { s.mu.Lock(); delete(s.watchers, ch) }()
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	controller := http.NewResponseController(w)
	send := func(data string) bool {
		_ = controller.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if _, err := fmt.Fprint(w, data); err != nil {
			return false
		}
		if err := controller.Flush(); err != nil {
			return false
		}
		_ = controller.SetWriteDeadline(time.Time{})
		return true
	}
	// Subscribe before initial catch-up so a concurrent commit cannot be missed.
	if !send("retry: 5000\nevent: change\ndata: {}\n\n") {
		return
	}
	heartbeat := time.NewTicker(25 * time.Second)
	defer heartbeat.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ch:
			s.mu.Lock()
			ss, exists := s.sessions[sessionHash]
			revoked := !exists || !ss.Expires.After(time.Now())
			s.mu.Unlock()
			if revoked {
				send("event: revoked\ndata: {}\n\n")
				return
			}
			if !send("event: change\ndata: {}\n\n") {
				return
			}
		case <-heartbeat.C:
			// Transport keepalive only: no database access or client state fetch.
			if !send(": keepalive\n\n") {
				return
			}
		}
	}
}
