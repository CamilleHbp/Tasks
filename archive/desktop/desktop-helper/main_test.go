package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCommandsSurviveRestartAndRetry(t *testing.T) {
	directory := t.TempDir()
	s, err := openStore(directory)
	if err != nil {
		t.Fatal(err)
	}
	command := &Command{Version: 1, ID: "creation-command-001", Action: "add", Title: "Préparer demain"}
	req := Request{Version: 1, ID: "request", Method: "command", Command: command}
	first := s.handle(req)
	if first.Error != "" || len(first.Snapshot.Tasks) != 1 {
		t.Fatalf("%+v", first)
	}
	s, err = openStore(directory)
	if err != nil {
		t.Fatal(err)
	}
	again := s.handle(req)
	if again.Error != "" || len(again.Snapshot.Tasks) != 1 || again.Snapshot.Revision != 1 {
		t.Fatalf("%+v", again)
	}
	complete := &Command{Version: 1, ID: "completion-command-001", Action: "complete", TaskID: command.ID, Completed: true}
	result := s.handle(Request{Version: 1, ID: "complete", Method: "command", Command: complete})
	if result.Error != "" || !result.Snapshot.Tasks[0].Completed {
		t.Fatalf("%+v", result)
	}
}

func TestRejectedWriteDoesNotAlterMemoryOrDisk(t *testing.T) {
	s, _ := openStore(t.TempDir())
	c := &Command{Version: 1, ID: "creation-command-001", Action: "add", Title: "Task"}
	r := Request{Version: 1, ID: "request", Method: "command", Command: c}
	s.handle(r)
	c.Title = "Changed payload"
	if s.handle(r).Error != "command_id_reused" {
		t.Fatal("reused identity accepted")
	}
	if s.state.Snapshot.Tasks[0].Title != "Task" {
		t.Fatal("failed operation changed state")
	}
	// A file in place of the state path forces the next durable write to fail.
	path := filepath.Join(s.directory, "preview-state.json")
	os.Remove(path)
	os.Mkdir(path, 0700)
	c.ID = "creation-command-002"
	if s.handle(r).Error != "storage_failed" || len(s.state.Snapshot.Tasks) != 1 {
		t.Fatal("failed commit published state")
	}
}

func TestProtocolAndFraming(t *testing.T) {
	s, _ := openStore(t.TempDir())
	var output bytes.Buffer
	input := "invalid\n{\"version\":9,\"id\":\"wrong\",\"method\":\"hello\"}\n{\"version\":1,\"id\":\"ok\",\"method\":\"hello\"}\n"
	if err := serve(s, strings.NewReader(input), &output); err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 3 {
		t.Fatal(output.String())
	}
	var last Response
	json.Unmarshal([]byte(lines[2]), &last)
	if last.ID != "ok" || last.Snapshot == nil || last.Snapshot.Tasks == nil {
		t.Fatal(output.String())
	}
}

func TestCorruptStateIsNeverReplaced(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "preview-state.json")
	os.WriteFile(path, []byte("corrupt"), 0600)
	if _, err := openStore(dir); err == nil {
		t.Fatal("corrupt state accepted")
	}
	data, _ := os.ReadFile(path)
	if string(data) != "corrupt" {
		t.Fatal("corrupt data overwritten")
	}
}

func TestTransactionIsAtomicAndRejectsStaleEditors(t *testing.T) {
	s, _ := openStore(t.TempDir())
	revision := int64(0)
	tags := []Tag{{ID: "tag-identity-0001", Name: "Maison", Color: "#3877B8"}}
	command := &Command{Version: 1, ID: "transaction-command-001", Action: "transaction", ExpectedRevision: &revision,
		Tasks: []Task{{ID: "task-identity-001", Title: "Planifier", Generation: 1, Tags: []string{tags[0].ID}, DueDate: "2026-10-10", Details: "Notes", Checklist: []ChecklistItem{{ID: "step-1", Title: "Étape"}}}}, Tags: &tags}
	result := s.handle(Request{Version: 1, ID: "save", Method: "command", Command: command})
	if result.Error != "" || result.Snapshot.Tasks[0].Details != "Notes" || len(result.Snapshot.Tags) != 1 {
		t.Fatalf("%+v", result)
	}
	command.ID = "transaction-command-002"
	command.Tasks[0].Generation = 2
	command.Tasks[0].Title = "Stale edit"
	if got := s.handle(Request{Version: 1, ID: "stale", Method: "command", Command: command}); got.Error != "stale_revision" {
		t.Fatalf("%+v", got)
	}
	if s.state.Snapshot.Tasks[0].Title != "Planifier" {
		t.Fatal("stale transaction mutated committed task")
	}
	reopened, err := openStore(s.directory)
	if err != nil || reopened.state.Snapshot.Tags[0].Name != "Maison" {
		t.Fatal("transaction not durable", err)
	}
}

func TestNativeReceiptSurvivesRestartAndInterveningEdit(t *testing.T) {
	s, _ := openStore(t.TempDir())
	revision := int64(0)
	command := &Command{Version: 1, ID: "transaction-command-001", Action: "transaction", SourceID: "widget-command-001", TaskID: "task-identity-001", Completed: true, ExpectedRevision: &revision,
		Tasks: []Task{{ID: "task-identity-001", Title: "Task", Completed: true, Generation: 1}}}
	result := s.handle(Request{Version: 1, ID: "complete", Method: "command", Command: command})
	if result.Error != "" {
		t.Fatal(result.Error)
	}
	revision = 1
	edit := &Command{Version: 1, ID: "transaction-command-002", Action: "transaction", ExpectedRevision: &revision,
		Tasks: []Task{{ID: "task-identity-001", Title: "Edited", Completed: false, Generation: 2}}}
	s.handle(Request{Version: 1, ID: "edit", Method: "command", Command: edit})
	s, _ = openStore(s.directory)
	command.ID = "transaction-command-003"
	result = s.handle(Request{Version: 1, ID: "replay", Method: "command", Command: command})
	if result.Error != "" || result.Snapshot.Revision != 2 || result.Snapshot.Tasks[0].Completed {
		t.Fatalf("native command repeated: %+v", result)
	}
}

func TestEmptyTransactionCollectionsReplayAfterRestart(t *testing.T) {
	s, _ := openStore(t.TempDir())
	revision := int64(0)
	tags := []Tag{}
	command := &Command{Version: 1, ID: "transaction-command-001", Action: "transaction", ExpectedRevision: &revision, Tasks: []Task{}, Tags: &tags}
	request := Request{Version: 1, ID: "save", Method: "command", Command: command}
	if result := s.handle(request); result.Error != "" {
		t.Fatal(result.Error)
	}
	s, _ = openStore(s.directory)
	if result := s.handle(request); result.Error != "" || result.Snapshot.Revision != 1 {
		t.Fatalf("%+v", result)
	}
}

func TestLegacyStorageUpgradePreservesOriginalAndBlocksOldReaders(t *testing.T) {
	dir := t.TempDir()
	original := []byte(`{"snapshot":{"version":1,"revision":4,"locale":"fr","tasks":[{"id":"legacy-task-0001","title":"Ancienne tâche","completed":false}]},"applied":{}}`)
	os.WriteFile(filepath.Join(dir, "preview-state.json"), original, 0600)
	s, err := openStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	locale := "en"
	if result := s.handle(Request{Version: 1, ID: "locale", Method: "locale", Locale: &locale}); result.Error != "" || result.Snapshot.Version != 1 {
		t.Fatalf("%+v", result)
	}
	backup, err := os.ReadFile(filepath.Join(dir, "preview-state-v1.backup.json"))
	if err != nil || string(backup) != string(original) {
		t.Fatal("original backup not retained", err)
	}
	var disk State
	saved, _ := os.ReadFile(filepath.Join(dir, "preview-state.json"))
	json.Unmarshal(saved, &disk)
	if disk.Snapshot.Version != storageVersion {
		t.Fatal("old readers would accept the upgraded file")
	}
	s, err = openStore(dir)
	if err != nil || s.state.Snapshot.Tasks[0].Title != "Ancienne tâche" || s.state.Snapshot.Version != 1 {
		t.Fatal("upgrade did not preserve data", err)
	}
}
