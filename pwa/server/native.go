package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"strings"
)

// Route only to a configured workspace. Each backend still authenticates its
// own bearer token. The prefix survives gateway restarts without a token store.
func (ws workspaces) native(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Header.Get("X-Sync-Namespace") != "studio.camille.vervetwodo.task.v1" {
		fail(w, 403)
		return
	}
	route := strings.TrimPrefix(r.URL.Path, "/api/v1/")
	login := route == "login"
	switch route {
	case "login", "logout", "checkpoint", "sync", "native":
		if r.Method != http.MethodPost || !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
			fail(w, 405)
			return
		}
	case "events":
		if r.Method != http.MethodGet {
			fail(w, 405)
			return
		}
	default:
		fail(w, 404)
		return
	}
	g := ws.primary
	token := ""
	if login {
		var input struct {
			Code string `json:"code"`
		}
		if decode(w, r, &input) != nil || len(input.Code) > 200 {
			fail(w, 400)
			return
		}
		if len(ws.byCode) > 0 {
			g = ws.byCode[hash(strings.TrimSpace(input.Code))]
			if g == nil {
				if !ws.primary.allowLoginAttempt() {
					fail(w, 429)
					return
				}
				send(w, 401, map[string]string{"code": "invalid_code"})
				return
			}
		}
		b, _ := json.Marshal(input)
		r.Body = io.NopCloser(bytes.NewReader(b))
		r.ContentLength = int64(len(b))
	} else {
		bearer, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		shard, raw, found := strings.Cut(bearer, ".")
		if !ok || !found || raw == "" {
			fail(w, 401)
			return
		}
		if shard != hash(g.upstream) {
			g = nil
			for _, candidate := range ws.byOrigin {
				if hash(candidate.upstream) == shard {
					g = candidate
					break
				}
			}
			if g == nil {
				fail(w, 401)
				return
			}
		}
		token = raw
		r.Body = http.MaxBytesReader(w, r.Body, 4<<20)
	}
	target, err := url.Parse(g.upstream)
	if err != nil {
		fail(w, 503)
		return
	}
	proxy := httputil.ReverseProxy{Rewrite: func(p *httputil.ProxyRequest) {
		p.SetURL(target)
		p.Out.Host = target.Host
		p.Out.Header = make(http.Header)
		p.Out.Header.Set("Content-Type", "application/json")
		p.Out.Header.Set("X-Sync-Namespace", "studio.camille.vervetwodo.task.v1")
		if token != "" {
			p.Out.Header.Set("Authorization", "Bearer "+token)
		}
		p.Out.URL.RawQuery = ""
	}}
	if login {
		proxy.ModifyResponse = func(resp *http.Response) error {
			if resp.StatusCode != 200 {
				return nil
			}
			defer resp.Body.Close()
			var result map[string]json.RawMessage
			if err := json.NewDecoder(io.LimitReader(resp.Body, 8<<20)).Decode(&result); err != nil {
				return err
			}
			var raw string
			if err := json.Unmarshal(result["token"], &raw); err != nil {
				return err
			}
			if raw == "" {
				return io.ErrUnexpectedEOF
			}
			result["token"], _ = json.Marshal(hash(g.upstream) + "." + raw)
			b, err := json.Marshal(result)
			if err != nil {
				return err
			}
			resp.Body = io.NopCloser(bytes.NewReader(b))
			resp.ContentLength = int64(len(b))
			resp.Header.Set("Content-Length", strconv.Itoa(len(b)))
			return nil
		}
	}
	proxy.ServeHTTP(w, r)
}
