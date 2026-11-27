// The integration preview exercises the packaged desktop process boundary.
// It has a separate data directory and never reads Android or sync replicas.
package main

import (
	"bufio"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const protocolVersion = 1

// Storage evolves independently from the widget/wire contract. Older previews must
// refuse this file rather than silently discard task fields they do not understand.
const storageVersion = 4

type ChecklistItem struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Completed bool   `json:"completed"`
}
type Recurrence struct {
	Frequency string `json:"frequency"`
	Interval  int    `json:"interval"`
	Anchor    string `json:"anchor"`
}
type Task struct {
	ID         string          `json:"id"`
	Title      string          `json:"title"`
	Completed  bool            `json:"completed"`
	Details    string          `json:"details,omitempty"`
	Tags       []string        `json:"tags,omitempty"`
	Priority   int             `json:"priority,omitempty"`
	DueDate    string          `json:"dueDate,omitempty"`
	DueTime    string          `json:"dueTime,omitempty"`
	TimeZone   string          `json:"timeZone,omitempty"`
	Checklist  []ChecklistItem `json:"checklist,omitempty"`
	Recurrence *Recurrence     `json:"recurrence,omitempty"`
	Rollover   *bool           `json:"rollover,omitempty"`
	CreatedAt  int64           `json:"createdAt,omitempty"`
	ModifiedAt int64           `json:"modifiedAt,omitempty"`
	Generation int64           `json:"generation,omitempty"`
	Deleted    bool            `json:"deleted,omitempty"`
	Retracted  bool            `json:"retracted,omitempty"`
	ParentID   string          `json:"parentId,omitempty"`
}
type Tag struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Color string `json:"color"`
	Icon  string `json:"icon,omitempty"`
}
type Snapshot struct {
	Version  int    `json:"version"`
	Revision int64  `json:"revision"`
	Locale   string `json:"locale"`
	Tasks    []Task `json:"tasks"`
	Tags     []Tag  `json:"tags,omitempty"`
}
type Command struct {
	Version          int    `json:"version"`
	ID               string `json:"id"`
	Action           string `json:"action"`
	TaskID           string `json:"taskId"`
	Title            string `json:"title"`
	Completed        bool   `json:"completed"`
	SourceID         string `json:"sourceId,omitempty"`
	ExpectedRevision *int64 `json:"expectedRevision,omitempty"`
	Tasks            []Task `json:"tasks,omitempty"`
	Tags             *[]Tag `json:"tags,omitempty"`
}
type Request struct {
	Version          int             `json:"version"`
	ID               string          `json:"id"`
	Method           string          `json:"method"`
	Command          *Command        `json:"command,omitempty"`
	Locale           *string         `json:"locale,omitempty"`
	Payload          json.RawMessage `json:"payload,omitempty"`
	ExpectedRevision *int64          `json:"expectedRevision,omitempty"`
	Journal          json.RawMessage `json:"journal,omitempty"`
	Snapshot         *Snapshot       `json:"snapshot,omitempty"`
}
type Response struct {
	Version  int             `json:"version"`
	ID       string          `json:"id"`
	Snapshot *Snapshot       `json:"snapshot,omitempty"`
	Error    string          `json:"error,omitempty"`
	Journal  json.RawMessage `json:"journal,omitempty"`
	Sync     json.RawMessage `json:"sync,omitempty"`
}
type State struct {
	Snapshot      Snapshot           `json:"snapshot"`
	Received      map[string]string  `json:"received,omitempty"`
	Applied       map[string]Command `json:"applied"`
	AppliedHashes map[string]string  `json:"appliedHashes,omitempty"`
	Journal       json.RawMessage    `json:"journal,omitempty"`
}
type Store struct {
	directory string
	state     State
	legacy    []byte
	sync      *syncBridge
	notify    func()
}

func openStore(directory string) (*Store, error) {
	if directory == "" {
		return nil, errors.New("missing data directory")
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return nil, err
	}
	s := &Store{directory: directory, state: State{Snapshot: Snapshot{Version: 1, Locale: "en", Tasks: []Task{}}, Applied: map[string]Command{}}}
	data, err := os.ReadFile(filepath.Join(directory, "preview-state.json"))
	if err == nil {
		if err = json.Unmarshal(data, &s.state); err != nil {
			return nil, fmt.Errorf("invalid saved state: %w", err)
		}
		if (s.state.Snapshot.Version < 1 || s.state.Snapshot.Version > storageVersion) || s.state.Applied == nil || s.state.Snapshot.Tasks == nil {
			return nil, errors.New("unsupported saved state")
		}
		if s.state.Snapshot.Version == protocolVersion {
			s.legacy = data
		}
		s.state.Snapshot.Version = protocolVersion
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if s.state.AppliedHashes == nil {
		s.state.AppliedHashes = map[string]string{}
	}
	for id, command := range s.state.Applied {
		s.state.AppliedHashes[id] = commandHash(command)
	}
	s.state.Applied = map[string]Command{}
	return s, nil
}

func commandHash(command Command) string {
	data, _ := json.Marshal(command)
	return fmt.Sprintf("%x", sha256.Sum256(data))
}

func atomicJSON(path string, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return atomicData(path, data)
}

func atomicData(path string, data []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".write-*")
	if err != nil {
		return err
	}
	name := f.Name()
	defer os.Remove(name)
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(name, path)
}

func (s *Store) handle(r Request) Response {
	out := Response{Version: protocolVersion, ID: r.ID}
	if r.Version != protocolVersion {
		out.Error = "unsupported_protocol"
		return out
	}
	if r.ID == "" {
		out.Error = "invalid_request"
		return out
	}
	if strings.HasPrefix(r.Method, "engine.") {
		return s.engineRequest(r)
	}
	next := s.state
	next.Snapshot.Tasks = append([]Task{}, s.state.Snapshot.Tasks...)
	next.Received = make(map[string]string, len(s.state.Received))
	for id, signature := range s.state.Received {
		next.Received[id] = signature
	}
	next.Applied = make(map[string]Command, len(s.state.Applied))
	for id, command := range s.state.Applied {
		next.Applied[id] = command
	}
	next.AppliedHashes = make(map[string]string, len(s.state.AppliedHashes))
	for id, hash := range s.state.AppliedHashes {
		next.AppliedHashes[id] = hash
	}
	changed := false
	switch r.Method {
	case "syncRead":
	case "syncJournal", "syncProject":
		if r.ExpectedRevision == nil || *r.ExpectedRevision != next.Snapshot.Revision {
			out.Error = "stale_revision"
			return out
		}
		if len(r.Journal) == 0 || !json.Valid(r.Journal) {
			out.Error = "invalid_request"
			return out
		}
		next.Journal = r.Journal
		if r.Method == "syncProject" {
			if r.Snapshot == nil || r.Snapshot.Version != protocolVersion || r.Snapshot.Tasks == nil {
				out.Error = "invalid_request"
				return out
			}
			next.Snapshot.Tasks = r.Snapshot.Tasks
			next.Snapshot.Tags = r.Snapshot.Tags
		}
		changed = true
	case "hello", "snapshot":
	case "locale":
		if r.Locale == nil || (*r.Locale != "en" && *r.Locale != "fr") {
			out.Error = "invalid_locale"
			return out
		}
		changed = next.Snapshot.Locale != *r.Locale
		next.Snapshot.Locale = *r.Locale
	case "command":
		c := r.Command
		if c == nil || c.Version != protocolVersion || len(c.ID) < 16 || len(c.ID) > 128 {
			out.Error = "invalid_command"
			return out
		}
		if previous, exists := next.AppliedHashes[c.ID]; exists {
			if previous != commandHash(*c) {
				out.Error = "command_id_reused"
				return out
			}
			break
		}
		if c.SourceID != "" {
			signature := fmt.Sprintf("%s:%t", c.TaskID, c.Completed)
			if previous, exists := next.Received[c.SourceID]; exists {
				if previous != signature {
					out.Error = "command_id_reused"
					return out
				}
				break
			}
			next.Received[c.SourceID] = signature
		}
		index := -1
		for i, task := range next.Snapshot.Tasks {
			if task.ID == c.TaskID {
				index = i
				break
			}
		}
		switch c.Action {
		case "transaction":
			if c.ExpectedRevision == nil || *c.ExpectedRevision != s.state.Snapshot.Revision {
				out.Error = "stale_revision"
				return out
			}
			seen := map[string]bool{}
			for _, task := range c.Tasks {
				if len(task.ID) < 16 || len(task.ID) > 128 || seen[task.ID] || strings.TrimSpace(task.Title) == "" || len(task.Title) > 64000 || task.Generation < 1 {
					out.Error = "invalid_task"
					return out
				}
				seen[task.ID] = true
				found := false
				for i, old := range next.Snapshot.Tasks {
					if old.ID == task.ID {
						if task.Generation != old.Generation+1 {
							out.Error = "stale_revision"
							return out
						}
						next.Snapshot.Tasks[i] = task
						found = true
						break
					}
				}
				if !found {
					if task.Generation != 1 {
						out.Error = "invalid_task"
						return out
					}
					next.Snapshot.Tasks = append(next.Snapshot.Tasks, task)
				}
			}
			if c.Tags != nil {
				next.Snapshot.Tags = *c.Tags
			}
		case "add":
			title := strings.TrimSpace(c.Title)
			if title == "" || len(title) > 16000 {
				out.Error = "invalid_title"
				return out
			}
			// The command ID is also the creation ID; retries cannot create duplicates.
			next.Snapshot.Tasks = append(next.Snapshot.Tasks, Task{ID: c.ID, Title: title, CreatedAt: time.Now().UnixMilli()})
		case "complete":
			if index < 0 {
				out.Error = "task_not_found"
				return out
			}
			next.Snapshot.Tasks[index].Completed = c.Completed
		case "delete":
			if index >= 0 {
				next.Snapshot.Tasks = append(next.Snapshot.Tasks[:index], next.Snapshot.Tasks[index+1:]...)
			}
		default:
			out.Error = "unknown_action"
			return out
		}
		next.AppliedHashes[c.ID] = commandHash(*c)
		changed = true
	default:
		out.Error = "unknown_method"
		return out
	}
	if changed {
		next.Snapshot.Revision++
		encoded, err := json.Marshal(next.Snapshot)
		if err != nil || len(encoded) > 3*1024*1024 {
			out.Error = "storage_limit"
			return out
		}
		if s.legacy != nil {
			backupPath := filepath.Join(s.directory, "preview-state-v1.backup.json")
			existing, err := os.ReadFile(backupPath)
			if errors.Is(err, os.ErrNotExist) {
				err = atomicData(backupPath, s.legacy)
			} else if err == nil && !json.Valid(existing) {
				err = errors.New("invalid migration backup")
			}
			if err != nil {
				out.Error = "storage_failed"
				return out
			}
		}
		disk := next
		disk.Snapshot.Version = storageVersion
		if err := atomicJSON(filepath.Join(s.directory, "preview-state.json"), disk); err != nil {
			out.Error = "storage_failed"
			return out
		}
		s.state = next
		s.legacy = nil
	}
	out.Snapshot = &s.state.Snapshot
	out.Journal = s.state.Journal
	return out
}

func serve(s *Store, input io.Reader, output io.Writer) error {
	scanner := bufio.NewScanner(input)
	scanner.Buffer(make([]byte, 4096), 4<<20)
	encoder := json.NewEncoder(output)
	var outputMu sync.Mutex
	send := func(value any) error { outputMu.Lock(); defer outputMu.Unlock(); return encoder.Encode(value) }
	s.notify = func() { _ = send(map[string]any{"version": protocolVersion, "id": "", "event": "syncChanged"}) }
	defer func() {
		if s.sync != nil {
			_ = s.sync.client.Close()
		}
	}()
	for scanner.Scan() {
		var request Request
		var response Response
		if json.Unmarshal(scanner.Bytes(), &request) != nil {
			response = Response{Version: protocolVersion, Error: "invalid_json"}
		} else {
			response = s.handle(request)
		}
		if err := send(response); err != nil {
			return err
		}
	}
	return scanner.Err()
}

func main() {
	directory := flag.String("data-dir", "", "private integration-preview data directory")
	flag.Parse()
	store, err := openStore(*directory)
	if err == nil {
		err = serve(store, os.Stdin, os.Stdout)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
