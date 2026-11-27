// Command vervetwodo-widget-helper keeps the desktop widget files current from the shared
// workspace without the desktop app. Hosts speak line-delimited JSON over stdin/stdout;
// access codes travel only through that pipe, never through arguments or files.
package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/CamilleHbp/Tasks/sync-engine/tasks"
	core "github.com/CamilleHbp/sync-core"
	"github.com/CamilleHbp/sync-core/appclient"
	_ "time/tzdata"
)

type Request struct {
	ID     string `json:"id"`
	Method string `json:"method"`
	Origin string `json:"origin,omitempty"`
	Code   string `json:"code,omitempty"`
	Locale string `json:"locale,omitempty"`
}

type Status struct {
	Authenticated bool   `json:"authenticated"`
	Workspace     string `json:"workspace"`
	Origin        string `json:"origin"`
	Confirmed     bool   `json:"confirmed"`
	LastSuccessAt int64  `json:"lastSuccessAt"`
	Busy          bool   `json:"busy"`
	Error         string `json:"error,omitempty"`
	Tasks         int    `json:"tasks"`
}

type Response struct {
	ID     string  `json:"id,omitempty"`
	Event  string  `json:"event,omitempty"`
	Status *Status `json:"status,omitempty"`
	Error  string  `json:"error,omitempty"`
}

type Command struct {
	Version   int    `json:"version"`
	ID        string `json:"id"`
	Action    string `json:"action"`
	TaskID    string `json:"taskId"`
	Completed bool   `json:"completed"`
}

var commandID = regexp.MustCompile(`^[a-zA-Z0-9-]{16,128}$`)

type Helper struct {
	data, widgets string
	source        core.Source
	mu            sync.Mutex
	client        *appclient.Client
	snapshot      Snapshot
	applied       []string
	busy          bool
	failure       string
	send          func(Response)
	stop          context.CancelFunc
}

func open(data, widgets, platform string) (*Helper, error) {
	if data == "" || widgets == "" {
		return nil, errors.New("data and widget directories are required")
	}
	if err := os.MkdirAll(data, 0o700); err != nil {
		return nil, err
	}
	h := &Helper{data: data, widgets: widgets, send: func(Response) {}}
	h.source = core.Source{ID: h.sourceID(), Name: "Widgets", Platform: platform}
	_ = readJSON(filepath.Join(data, "applied.json"), &h.applied)
	if readJSON(filepath.Join(widgets, "snapshot.json"), &h.snapshot) != nil || h.snapshot.Version != 1 {
		h.snapshot = Snapshot{Version: 1, Locale: "en"}
	}
	return h, h.openClient()
}

// sourceID is stable per installation so the workspace attributes widget edits consistently.
func (h *Helper) sourceID() string {
	path := filepath.Join(h.data, "source-id")
	if b, err := os.ReadFile(path); err == nil && commandID.Match(b) {
		return string(b)
	}
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	id := hex.EncodeToString(b)
	_ = writeAtomic(path, []byte(id))
	return id
}

func (h *Helper) openClient() error {
	directory := filepath.Join(h.data, "sync-v1")
	if err := tasks.MigrateAddress(directory); err != nil {
		return err
	}
	client, err := appclient.New(directory, tasks.Config())
	if err != nil {
		return err
	}
	h.client = client
	ctx, cancel := context.WithCancel(context.Background())
	h.stop = cancel
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-client.Done():
				return
			case <-client.Changes():
				h.refresh()
			}
		}
	}()
	return nil
}

func (h *Helper) status() *Status {
	view, err := h.client.View()
	s := &Status{Authenticated: view.Authenticated, Workspace: view.Workspace, Origin: tasks.DisplayAddress(view.Origin),
		Confirmed: view.SyncStatus.Confirmed, LastSuccessAt: view.SyncStatus.LastSuccessAt, Busy: h.busy, Error: h.failure, Tasks: len(h.snapshot.Tasks)}
	if err != nil && s.Error == "" {
		s.Error = core.ErrorCode(err)
	}
	return s
}

// refresh projects the current view and rewrites snapshot.json only when content changed.
func (h *Helper) refresh() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.refreshLocked(false)
}

func (h *Helper) refreshLocked(verifyFile bool) {
	view, err := h.client.View()
	if err != nil || !view.Authenticated {
		return
	}
	next := project(view.Records, h.snapshot)
	if !verifyFile && next.Revision == h.snapshot.Revision {
		return
	}
	data, _ := json.Marshal(next)
	path := filepath.Join(h.widgets, "snapshot.json")
	if next.Revision == h.snapshot.Revision {
		if info, err := os.Stat(path); err == nil && info.Size() == int64(len(data)) {
			if existing, err := os.ReadFile(path); err == nil && bytes.Equal(existing, data) {
				return
			}
		}
	}
	if err := writeAtomic(path, data); err != nil {
		h.failure = "widget_write_failed"
		return
	}
	h.snapshot = next
	h.send(Response{Event: "changed", Status: h.status()})
}

// sync runs in the background; completion is reported as a status event.
func (h *Helper) sync(reconnect bool) {
	h.mu.Lock()
	if h.busy {
		h.mu.Unlock()
		return
	}
	h.busy = true
	client := h.client
	h.mu.Unlock()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		var err error
		if reconnect {
			err = client.Sync(ctx)
		} else {
			err = client.Check(ctx)
			// A checkpoint mismatch on the native transport can persist after sleep.
			// Check alone verifies the replica but does not reconnect the peer.
			if err == nil {
				view, viewErr := client.View()
				if viewErr == nil && view.Authenticated && view.Pending < 0 && view.SyncStatus.Supported && !view.SyncStatus.Confirmed {
					err = client.Sync(ctx)
				}
			}
		}
		h.mu.Lock()
		h.busy = false
		h.failure = core.ErrorCode(err)
		h.refreshLocked(true)
		h.send(Response{Event: "status", Status: h.status()})
		h.mu.Unlock()
	}()
}

// applyCommands drains widget actions in name order. A command completes or, with
// "completed": false, reopens a task. Invalid or obsolete commands are
// renamed *.rejected; transient failures leave the file for the next pass.
func (h *Helper) applyCommands() {
	inbox := filepath.Join(h.widgets, "commands")
	entries, err := os.ReadDir(inbox)
	if err != nil {
		return
	}
	names := []string{}
	for _, e := range entries {
		if e.Type().IsRegular() && strings.HasSuffix(e.Name(), ".json") {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)
	if len(names) > 100 {
		names = names[:100]
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	changed := false
	for _, name := range names {
		path := filepath.Join(inbox, name)
		var c Command
		if info, err := os.Lstat(path); err != nil || info.Size() > 64*1024 || readJSON(path, &c) != nil ||
			c.Version != 1 || !commandID.MatchString(c.ID) || c.Action != "complete" || len(c.TaskID) < 16 || len(c.TaskID) > 128 {
			_ = os.Rename(path, path+".rejected")
			continue
		}
		if h.seen(c.ID) {
			_ = os.Remove(path)
			continue
		}
		view, err := h.client.View()
		if err != nil || !view.Authenticated {
			return
		}
		// "completed": false reopens the task, so a widget checkbox can be unticked.
		apply := completion
		if !c.Completed {
			apply = restoration
		}
		ops, err := apply(view.Records, c.TaskID, &h.source, newID)
		if err != nil {
			_ = os.Rename(path, path+".rejected")
			continue
		}
		if len(ops) > 0 {
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			err = h.client.Apply(ctx, ops)
			cancel()
			if err != nil {
				h.failure = core.ErrorCode(err)
				return
			}
			changed = true
		}
		h.remember(c.ID)
		_ = os.Remove(path)
	}
	if changed {
		h.refreshLocked(false)
		go h.sync(false)
	}
}

func (h *Helper) seen(id string) bool {
	for _, applied := range h.applied {
		if applied == id {
			return true
		}
	}
	return false
}

func (h *Helper) remember(id string) {
	h.applied = append(h.applied, id)
	if len(h.applied) > 1000 {
		h.applied = h.applied[len(h.applied)-1000:]
	}
	data, _ := json.Marshal(h.applied)
	_ = writeAtomic(filepath.Join(h.data, "applied.json"), data)
}

func (h *Helper) handle(r Request) Response {
	out := Response{ID: r.ID}
	switch r.Method {
	case "status":
	case "connect":
		origin := strings.TrimSpace(r.Origin)
		if !strings.HasPrefix(origin, "https://") && !strings.HasPrefix(origin, "http://127.0.0.1") && !strings.HasPrefix(origin, "http://localhost") {
			out.Error = "invalid_origin"
			break
		}
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		if tasks.DisplayAddress(origin) == tasks.PublicAddress {
			origin = tasks.PublicAddress
		} else {
			origin = discover(ctx, origin)
		}
		err := h.client.Connect(ctx, origin, strings.TrimSpace(r.Code))
		cancel()
		if err != nil {
			out.Error = core.ErrorCode(err)
			break
		}
		h.refresh()
		h.sync(true)
	case "sync":
		h.sync(true)
	case "check":
		h.sync(false)
	case "locale":
		if r.Locale != "en" && r.Locale != "fr" {
			out.Error = "invalid_locale"
			break
		}
		h.mu.Lock()
		if h.snapshot.Locale != r.Locale {
			h.snapshot.Locale = r.Locale
			h.snapshot.Revision++
			// Only a connected helper owns the widget files; never publish an empty list.
			if view, err := h.client.View(); err == nil && view.Authenticated {
				data, _ := json.Marshal(h.snapshot)
				_ = writeAtomic(filepath.Join(h.widgets, "snapshot.json"), data)
			}
		}
		h.mu.Unlock()
	case "disconnect":
		h.mu.Lock()
		h.stop()
		err := h.client.Close()
		if err == nil {
			err = os.RemoveAll(filepath.Join(h.data, "sync-v1"))
		}
		if err == nil {
			_ = os.Remove(filepath.Join(h.widgets, "snapshot.json"))
			h.snapshot = Snapshot{Version: 1, Revision: h.snapshot.Revision + 1, Locale: h.snapshot.Locale}
			err = h.openClient()
		}
		h.mu.Unlock()
		if err != nil {
			out.Error = "disconnect_failed"
		}
	default:
		out.Error = "invalid_request"
	}
	h.mu.Lock()
	out.Status = h.status()
	h.mu.Unlock()
	return out
}

func (h *Helper) serve(in io.Reader, out io.Writer) error {
	var writeMu sync.Mutex
	encoder := json.NewEncoder(out)
	h.send = func(r Response) {
		writeMu.Lock()
		defer writeMu.Unlock()
		_ = encoder.Encode(r)
	}
	h.refresh()
	h.sync(true)
	go func() {
		// The widgets show the change immediately; a short poll keeps the workspace close behind.
		commands := time.NewTicker(500 * time.Millisecond)
		check := time.NewTicker(5 * time.Minute)
		for {
			select {
			case <-commands.C:
				h.applyCommands()
			case <-check.C:
				h.sync(false)
			}
		}
	}()
	scanner := bufio.NewScanner(in)
	scanner.Buffer(make([]byte, 64*1024), 64*1024)
	for scanner.Scan() {
		var r Request
		if err := json.Unmarshal(scanner.Bytes(), &r); err != nil {
			h.send(Response{Error: "invalid_request"})
			continue
		}
		h.send(h.handle(r))
	}
	// The host closing stdin ends the helper; the sync client flushes on close.
	h.stop()
	return errors.Join(scanner.Err(), h.client.Close())
}

// discover resolves the address people know, the web app, to its synchronization server
// through the app's /.well-known/vervetwodo-sync document. A server without the document
// is used as entered, so a synchronization address keeps working.
func discover(ctx context.Context, origin string) string {
	base := strings.TrimSuffix(origin, "/")
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, "GET", base+"/.well-known/vervetwodo-sync", nil)
	if err != nil {
		return origin
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return origin
	}
	defer response.Body.Close()
	var document struct {
		SyncOrigin string `json:"syncOrigin"`
	}
	if response.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(response.Body, 4096)).Decode(&document) != nil {
		return origin
	}
	// The document may only point at another https origin, never downgrade or add a path.
	target, err := url.Parse(document.SyncOrigin)
	if err != nil || target.Scheme != "https" || target.Host == "" || target.User != nil ||
		(target.Path != "" && target.Path != "/") || target.RawQuery != "" || target.Fragment != "" {
		return origin
	}
	return strings.TrimSuffix(target.String(), "/")
}

func newID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

func readJSON(path string, out any) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, out)
}

func writeAtomic(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	file, err := os.CreateTemp(filepath.Dir(path), ".tmp-")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	if _, err = file.Write(data); err == nil {
		err = file.Sync()
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}

func main() {
	data := flag.String("data-dir", "", "private synchronization directory")
	widgets := flag.String("widget-dir", "", "shared widget snapshot directory")
	platform := flag.String("platform", "macos", "source platform: macos or windows")
	flag.Parse()
	h, err := open(*data, *widgets, *platform)
	if err == nil {
		err = h.serve(os.Stdin, os.Stdout)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
