package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNativeAddressKeepsWorkspacesSeparate(t *testing.T) {
	makeBackend := func(code string) *httptest.Server {
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/api/v1/login" {
				var in struct{ Code string }
				_ = json.NewDecoder(r.Body).Decode(&in)
				if in.Code != code {
					t.Error("code sent to wrong workspace")
					fail(w, 401)
					return
				}
				send(w, 200, map[string]string{"token": "private-" + code})
				return
			}
			if r.Header.Get("Authorization") != "Bearer private-"+code {
				fail(w, 401)
				return
			}
			send(w, 200, map[string]string{"server": code})
		}))
	}
	a, b := makeBackend("one"), makeBackend("two")
	defer a.Close()
	defer b.Close()
	first, second := setup(t), setup(t)
	first.upstream = a.URL
	second.upstream = b.URL
	ws := workspaces{primary: first, byOrigin: map[string]*gateway{b.URL: second}, byCode: map[string]*gateway{hash("one"): first, hash("two"): second}}
	call := func(route, body, token string) (int, map[string]string) {
		r := httptest.NewRequest("POST", "/api/v1/"+route, strings.NewReader(body))
		r.Header.Set("X-Sync-Namespace", "studio.camille.vervetwodo.task.v1")
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		ws.ServeHTTP(w, r)
		var out map[string]string
		_ = json.Unmarshal(w.Body.Bytes(), &out)
		return w.Code, out
	}
	for _, code := range []string{"one", "two"} {
		status, out := call("login", `{"code":"`+code+`"}`, "")
		if status != 200 {
			t.Fatal(status)
		}
		token := out["token"]
		status, out = call("checkpoint", "{}", token)
		if status != 200 || out["server"] != code {
			t.Fatal("wrong workspace")
		}
	}
	if status, _ := call("checkpoint", "{}", hash(a.URL)+".private-two"); status != 401 {
		t.Fatal("cross-workspace session accepted")
	}
	if status, _ := call("login", `{"code":"wrong"}`, ""); status != 401 {
		t.Fatal("unknown code accepted")
	}
}
