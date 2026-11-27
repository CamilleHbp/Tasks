package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	core "github.com/CamilleHbp/sync-core"
)

func record(id, kind string, fields map[string]string) core.Record {
	r := core.Record{ID: id, Kind: kind, Fields: map[string][]core.Version{}}
	for field, v := range fields {
		r.Fields[field] = []core.Version{{ID: id + ":" + field, Value: v}}
	}
	return r
}

func TestNameUUIDMatchesJava(t *testing.T) {
	// java.util.UUID.nameUUIDFromBytes("abc".getBytes(UTF_8))
	if got := nameUUID("abc"); got != "90015098-3cd2-3fb0-9696-3f7d28e17f72" {
		t.Fatal(got)
	}
}

func TestNextDateClampsToAnchor(t *testing.T) {
	cases := []struct{ date, frequency, anchor, want string }{
		{"2027-01-31", "monthly", "2027-01-31", "2027-02-28"},
		{"2027-02-28", "monthly", "2027-01-31", "2027-03-31"},
		{"2028-02-29", "yearly", "2028-02-29", "2029-02-28"},
		{"2027-03-01", "weekly", "2027-03-01", "2027-03-08"},
		{"2027-03-01", "daily", "2027-03-01", "2027-03-02"},
	}
	for _, c := range cases {
		date, _ := time.Parse(time.DateOnly, c.date)
		got, err := nextDate(date, Recurrence{Frequency: c.frequency, Interval: 1, Anchor: c.anchor})
		if err != nil || got.Format(time.DateOnly) != c.want {
			t.Errorf("%+v: %v %v", c, got, err)
		}
	}
}

func TestProjectionMatchesSharedRules(t *testing.T) {
	records := []core.Record{
		record("task-0000000000000001", "task", map[string]string{"title": "Pay <rent>", "dueDate": "", "dueTime": "09:00", "tags": `["t1"]`}),
		record("task-0000000000000002", "task", map[string]string{"title": "Gone", "deleted": "true"}),
		record("t1", "tag", map[string]string{"name": "Home"}),
		record("t2", "tag", map[string]string{"name": "Old", "deleted": "true"}),
	}
	// Concurrent values resolve to the lowest version ID.
	records[0].Fields["title"] = append(records[0].Fields["title"], core.Version{ID: "a", Value: "Winner"})
	s := project(records, Snapshot{Version: 1, Locale: "fr"})
	if len(s.Tasks) != 1 || s.Tasks[0].Title != "Winner" || s.Tasks[0].DueTime != "" || s.Tasks[0].Tags[0] != "t1" {
		t.Fatalf("%+v", s.Tasks)
	}
	if len(s.Tags) != 1 || s.Tags[0].Color != "#3877B8" || s.Tags[0].Icon != "tag" || s.Locale != "fr" || s.Revision != 1 {
		t.Fatalf("%+v", s)
	}
	if again := project(records, s); again.Revision != 1 {
		t.Fatal("unchanged content must keep its revision")
	}
}

func TestRecurringCompletionCreatesSuccessorOnce(t *testing.T) {
	id := "task-0000000000000001"
	r := record(id, "task", map[string]string{"title": "Water plants", "completed": "false", "dueDate": "2027-01-31",
		"recurrence": `{"frequency":"monthly","interval":1,"anchor":"2027-01-31"}`, "checklist": `[{"id":"c","title":"Fern","completed":true}]`,
		"createdAt": "5", "tags": "[]"})
	n := 0
	ids := func() string { n++; return "op-" + strconv.Itoa(n) }
	ops, err := completion([]core.Record{r}, id, nil, ids)
	if err != nil || len(ops) < 2 || ops[0].Field != "completed" || ops[0].Replaces[0] != id+":completed" {
		t.Fatalf("%+v %v", ops, err)
	}
	successor := nameUUID(id + ":2027-02-28")
	fields := map[string]string{}
	for _, op := range ops[1:] {
		if op.Record != successor {
			t.Fatalf("%+v", op)
		}
		fields[op.Field] = op.Value
	}
	if fields["dueDate"] != "2027-02-28" || fields["parentId"] != id || fields["completed"] != "false" ||
		fields["checklist"] != `[{"id":"c","title":"Fern","completed":false}]` || fields["createdAt"] != "5" {
		t.Fatalf("%+v", fields)
	}
	existing := record(successor, "task", map[string]string{"title": "Edited"})
	if ops, _ := completion([]core.Record{r, existing}, id, nil, ids); len(ops) != 1 {
		t.Fatalf("existing successor must not be overwritten: %+v", ops)
	}
	done := record(id, "task", map[string]string{"completed": "true"})
	if ops, _ := completion([]core.Record{done}, id, nil, ids); len(ops) != 0 {
		t.Fatal("completed tasks need no operation")
	}
	if _, err := completion(nil, id, nil, ids); err != errTaskNotFound {
		t.Fatal(err)
	}
}

func TestRestorationReopensAndRetractsUntouchedSuccessor(t *testing.T) {
	id := "task-0000000000000001"
	r := record(id, "task", map[string]string{"title": "Water plants", "completed": "false", "dueDate": "2027-01-31",
		"recurrence": `{"frequency":"monthly","interval":1,"anchor":"2027-01-31"}`, "createdAt": "5", "tags": "[]"})
	n := 0
	ids := func() string { n++; return "op-" + strconv.Itoa(n) }
	created, _ := completion([]core.Record{r}, id, nil, ids)
	successor := core.Record{ID: nameUUID(id + ":2027-02-28"), Kind: "task", Fields: map[string][]core.Version{}}
	for _, op := range created[1:] {
		successor.Fields[op.Field] = []core.Version{{ID: op.ID, Value: op.Value}}
	}
	done := record(id, "task", map[string]string{"title": "Water plants", "completed": "true", "dueDate": "2027-01-31",
		"recurrence": `{"frequency":"monthly","interval":1,"anchor":"2027-01-31"}`, "createdAt": "5", "tags": "[]"})
	ops, err := restoration([]core.Record{done, successor}, id, nil, ids)
	if err != nil || len(ops) != 3 || ops[0].Field != "completed" || ops[0].Value != "false" ||
		ops[1].Record != successor.ID || ops[1].Field != "deleted" || ops[2].Field != "retracted" {
		t.Fatalf("%+v %v", ops, err)
	}
	successor.Fields["title"] = []core.Version{{ID: "edit", Value: "Water the fern"}}
	if ops, _ := restoration([]core.Record{done, successor}, id, nil, ids); len(ops) != 1 {
		t.Fatalf("an edited successor must be kept: %+v", ops)
	}
	if ops, _ := restoration([]core.Record{r}, id, nil, ids); len(ops) != 0 {
		t.Fatal("open tasks need no operation")
	}
}

func TestInvalidCommandsAreRejectedAndValidOnesWait(t *testing.T) {
	widgets := t.TempDir()
	h, err := open(t.TempDir(), widgets, "macos")
	if err != nil {
		t.Fatal(err)
	}
	defer h.client.Close()
	inbox := filepath.Join(widgets, "commands")
	_ = os.MkdirAll(inbox, 0o700)
	valid, _ := json.Marshal(Command{Version: 1, ID: "command-0000000000001", Action: "complete", TaskID: "task-0000000000000001", Completed: true})
	_ = os.WriteFile(filepath.Join(inbox, "a.json"), []byte("{"), 0o600)
	_ = os.WriteFile(filepath.Join(inbox, "b.json"), valid, 0o600)
	reopen, _ := json.Marshal(Command{Version: 1, ID: "command-0000000000002", Action: "complete", TaskID: "task-0000000000000001"})
	_ = os.WriteFile(filepath.Join(inbox, "c.json"), reopen, 0o600)
	h.applyCommands()
	if _, err := os.Stat(filepath.Join(inbox, "c.json")); err != nil {
		t.Fatal("reopen commands are valid and wait while disconnected")
	}
	if _, err := os.Stat(filepath.Join(inbox, "a.json.rejected")); err != nil {
		t.Fatal("malformed command must be rejected")
	}
	if _, err := os.Stat(filepath.Join(inbox, "b.json")); err != nil {
		t.Fatal("commands wait while disconnected")
	}
	if r := h.handle(Request{ID: "1", Method: "locale", Locale: "fr"}); r.Error != "" || r.Status.Authenticated {
		t.Fatalf("%+v", r)
	}
	if _, err := os.Stat(filepath.Join(widgets, "snapshot.json")); !os.IsNotExist(err) || h.snapshot.Locale != "fr" {
		t.Fatal("a disconnected helper must keep the locale without publishing widget files")
	}
	if r := h.handle(Request{ID: "2", Method: "connect", Origin: "ftp://example"}); r.Error != "invalid_origin" {
		t.Fatalf("%+v", r)
	}
}

func TestDiscoverResolvesWebAddressToSyncServer(t *testing.T) {
	documents := map[string]string{
		"/web/.well-known/vervetwodo-sync":       `{"syncOrigin":"https://sync.example/"}`,
		"/downgrade/.well-known/vervetwodo-sync": `{"syncOrigin":"http://sync.example"}`,
		"/path/.well-known/vervetwodo-sync":      `{"syncOrigin":"https://sync.example/api"}`,
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if body, ok := documents[r.URL.Path]; ok {
			_, _ = w.Write([]byte(body))
			return
		}
		http.NotFound(w, r)
	}))
	defer server.Close()
	ctx := context.Background()
	if got := discover(ctx, server.URL+"/web"); got != "https://sync.example" {
		t.Fatal(got)
	}
	for _, kept := range []string{server.URL + "/downgrade", server.URL + "/path", server.URL + "/none", "https://127.0.0.1:1"} {
		if got := discover(ctx, kept); got != kept {
			t.Fatalf("%s resolved to %s", kept, got)
		}
	}
}
