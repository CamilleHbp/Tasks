package main

import (
	"context"
	"encoding/json"
	"github.com/CamilleHbp/Tasks/sync-engine/tasks"
	core "github.com/CamilleHbp/sync-core"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestDevicesOfflineConflictAndRestart(t *testing.T) {
	directory := t.TempDir()
	server, err := newServer(directory)
	if err != nil {
		t.Fatal(err)
	}
	defer server.db.Close()
	endpoint := httptest.NewServer(server)
	defer endpoint.Close()
	var code string
	raw, _ := os.ReadFile(filepath.Join(directory, "access-code.json"))
	if err := json.Unmarshal(raw, &code); err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	devices := make([]*core.Client, 2)
	for i := range devices {
		devices[i], err = core.NewClient(t.TempDir(), tasks.Config())
		if err != nil {
			t.Fatal(err)
		}
		defer devices[i].Close()
		if err = devices[i].Connect(ctx, endpoint.URL, code); err != nil {
			t.Fatal(err)
		}
	}
	title := core.Operation{ID: core.RandomID(), Record: core.RandomID(), Kind: "task", Field: "title", Value: "A personal task"}
	if err = devices[0].Apply([]core.Operation{title}); err != nil {
		t.Fatal(err)
	}
	for _, c := range devices {
		if err = c.Sync(ctx); err != nil {
			t.Fatal(err)
		}
	}
	checkpoint, supported, err := devices[0].Checkpoint(ctx)
	if err != nil || !supported {
		t.Fatal("checkpoint unavailable", err)
	}
	current, err := devices[0].View()
	if err != nil || checkpoint.Digest != core.RecordsDigest(current.Records) {
		t.Fatal("durable checkpoint differs", err)
	}
	editA := core.Operation{ID: core.RandomID(), Record: title.Record, Kind: "task", Field: "title", Value: "Android edit", Replaces: []string{title.ID}}
	editB := editA
	editB.ID = core.RandomID()
	editB.Value = "Desktop edit"
	for i, edit := range []core.Operation{editA, editB} {
		if err = devices[i].Apply([]core.Operation{edit}); err != nil {
			t.Fatal(err)
		}
	}
	for _, i := range []int{0, 1, 0} {
		if err = devices[i].Sync(ctx); err != nil {
			t.Fatal(err)
		}
	}
	for _, c := range devices {
		view, err := c.View()
		if err != nil {
			t.Fatal(err)
		}
		if view.Pending != 0 || len(view.Records) != 1 || len(view.Records[0].Fields["title"]) != 2 {
			t.Fatalf("conflicting offline edits were lost: %+v", view)
		}
	}
	server.db.Close()
	reopened, err := newServer(directory)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.db.Close()
	if reopened.account.CodeHash != digest(code) {
		t.Fatal("access code changed on restart")
	}
}

func TestWrongCodeCannotResetClient(t *testing.T) {
	server, err := newServer(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer server.db.Close()
	endpoint := httptest.NewServer(server)
	defer endpoint.Close()
	client, err := core.NewClient(t.TempDir(), tasks.Config())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if err = client.Connect(context.Background(), endpoint.URL, "incorrect"); err == nil {
		t.Fatal("accepted wrong code")
	}
}
