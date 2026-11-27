package main

import (
	"bytes"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sort"
	"strconv"
	"time"

	core "github.com/CamilleHbp/sync-core"
)

// Snapshot mirrors the shared WidgetSnapshot contract read by both platform widgets.
// No credentials or synchronization keys may be written here.
type Snapshot struct {
	Version  int    `json:"version"`
	Revision int64  `json:"revision"`
	Locale   string `json:"locale"`
	Tasks    []Task `json:"tasks"`
	Tags     []Tag  `json:"tags"`
}

type Tag struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Color string `json:"color"`
	Icon  string `json:"icon"`
}

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
	Details    string          `json:"details"`
	Tags       []string        `json:"tags"`
	Priority   int             `json:"priority"`
	DueDate    string          `json:"dueDate"`
	DueTime    string          `json:"dueTime"`
	TimeZone   string          `json:"timeZone"`
	Checklist  []ChecklistItem `json:"checklist"`
	Recurrence *Recurrence     `json:"recurrence"`
	CreatedAt  int64           `json:"createdAt"`
	ParentID   string          `json:"parentId"`
	Rollover   *bool           `json:"rollover"`
}

// value matches SyncRecord.value: the lowest version ID wins among concurrent values.
func value(r core.Record, field string) string {
	versions := r.Fields[field]
	if len(versions) == 0 {
		return ""
	}
	best := versions[0]
	for _, v := range versions[1:] {
		if v.ID < best.ID {
			best = v
		}
	}
	return best.Value
}

func decode[T any](text, empty string, out *T) {
	if text == "" {
		text = empty
	}
	_ = json.Unmarshal([]byte(text), out)
}

// project ports the records→snapshot part of TaskSync.project; deleted tasks are omitted
// as WidgetFiles.publish does.
func project(records []core.Record, previous Snapshot) Snapshot {
	next := Snapshot{Version: 1, Revision: previous.Revision, Locale: previous.Locale, Tasks: []Task{}, Tags: []Tag{}}
	if next.Locale == "" {
		next.Locale = "en"
	}
	old := map[string]Task{}
	for _, t := range previous.Tasks {
		old[t.ID] = t
	}
	for _, r := range records {
		switch r.Kind {
		case "tag":
			if value(r, "deleted") == "true" {
				continue
			}
			tag := Tag{ID: r.ID, Name: value(r, "name"), Color: value(r, "color"), Icon: value(r, "icon")}
			if tag.Color == "" {
				tag.Color = "#3877B8"
			}
			if tag.Icon == "" {
				tag.Icon = "tag"
			}
			next.Tags = append(next.Tags, tag)
		case "task":
			if value(r, "deleted") == "true" {
				continue
			}
			next.Tasks = append(next.Tasks, task(r, old[r.ID]))
		}
	}
	sort.Slice(next.Tasks, func(i, j int) bool { return next.Tasks[i].ID < next.Tasks[j].ID })
	sort.Slice(next.Tags, func(i, j int) bool { return next.Tags[i].ID < next.Tags[j].ID })
	if !sameContent(previous, next) {
		next.Revision++
	}
	return next
}

func task(r core.Record, prior Task) Task {
	date := value(r, "dueDate")
	t := Task{ID: r.ID, Title: value(r, "title"), Completed: value(r, "completed") == "true", Details: value(r, "details"),
		Tags: []string{}, DueDate: date, TimeZone: value(r, "timeZone"), Checklist: []ChecklistItem{}, ParentID: value(r, "parentId")}
	if t.Title == "" {
		t.Title = prior.Title
	}
	t.Priority, _ = strconv.Atoi(value(r, "priority"))
	t.CreatedAt, _ = strconv.ParseInt(value(r, "createdAt"), 10, 64)
	decode(value(r, "tags"), "[]", &t.Tags)
	decode(value(r, "checklist"), "[]", &t.Checklist)
	if date != "" {
		t.DueTime = value(r, "dueTime")
		decode(value(r, "recurrence"), "null", &t.Recurrence)
	}
	if _, ok := r.Fields["rollover"]; ok {
		v := value(r, "rollover") == "true"
		t.Rollover = &v
	} else {
		t.Rollover = prior.Rollover
	}
	return t
}

func sameContent(a, b Snapshot) bool {
	a.Revision, b.Revision = 0, 0
	x, _ := json.Marshal(a)
	y, _ := json.Marshal(b)
	return bytes.Equal(x, y)
}

// encode matches kotlinx.serialization output for the nested JSON field values.
func encode(v any) string {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(v)
	return string(bytes.TrimRight(buffer.Bytes(), "\n"))
}

// nameUUID matches java.util.UUID.nameUUIDFromBytes (MD5, version 3).
func nameUUID(name string) string {
	sum := md5.Sum([]byte(name))
	sum[6] = sum[6]&0x0f | 0x30
	sum[8] = sum[8]&0x3f | 0x80
	h := hex.EncodeToString(sum[:])
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

var errInvalidRecurrence = errors.New("invalidRecurrence")

// nextDate ports TaskRules.nextDate.
func nextDate(date time.Time, rule Recurrence) (time.Time, error) {
	anchor, err := time.Parse(time.DateOnly, rule.Anchor)
	if err != nil {
		return time.Time{}, errInvalidRecurrence
	}
	clamp := func(year int, month time.Month) time.Time {
		last := time.Date(year, month+1, 0, 0, 0, 0, 0, time.UTC).Day()
		return time.Date(year, month, min(anchor.Day(), last), 0, 0, 0, 0, time.UTC)
	}
	var next time.Time
	switch rule.Frequency {
	case "daily":
		next = date.AddDate(0, 0, rule.Interval)
	case "weekly":
		next = date.AddDate(0, 0, 7*rule.Interval)
	case "monthly":
		first := time.Date(date.Year(), date.Month(), 1, 0, 0, 0, 0, time.UTC).AddDate(0, rule.Interval, 0)
		next = clamp(first.Year(), first.Month())
	case "yearly":
		next = clamp(date.Year()+rule.Interval, anchor.Month())
	default:
		return time.Time{}, errInvalidRecurrence
	}
	if next.Year() < 1 || next.Year() > 9999 {
		return time.Time{}, errInvalidRecurrence
	}
	return next, nil
}

func versionIDs(r core.Record, field string) []string {
	ids := []string{}
	for _, v := range r.Fields[field] {
		ids = append(ids, v.ID)
	}
	return ids
}

// completion ports TaskRules.completion for the widget's single-task complete action,
// including the recurring successor. Existing successors are never overwritten.
func completion(records []core.Record, id string, source *core.Source, newID func() string) ([]core.Operation, error) {
	byID := map[string]core.Record{}
	for _, r := range records {
		byID[r.ID] = r
	}
	r, ok := byID[id]
	if !ok || r.Kind != "task" || value(r, "deleted") == "true" {
		return nil, errTaskNotFound
	}
	if value(r, "completed") == "true" {
		return nil, nil
	}
	op := func(record core.Record, field, v string) core.Operation {
		return core.Operation{ID: newID(), Record: record.ID, Kind: "task", Field: field, Value: v, Replaces: versionIDs(record, field), Source: source}
	}
	ops := []core.Operation{op(r, "completed", "true")}
	successor, _, fields, err := successorFields(r)
	if err != nil || successor == "" {
		return ops, err
	}
	existing, exists := byID[successor]
	if exists && value(existing, "retracted") != "true" {
		return ops, nil
	}
	if !exists {
		existing = core.Record{ID: successor, Kind: "task"}
	}
	for _, f := range fields {
		ops = append(ops, op(existing, f[0], f[1]))
	}
	return ops, nil
}

// restoration ports TaskRules.completion(completed = false): the task reopens and an
// untouched generated successor is retracted. A successor whose content no longer matches
// what completion generates has been edited and is kept.
func restoration(records []core.Record, id string, source *core.Source, newID func() string) ([]core.Operation, error) {
	byID := map[string]core.Record{}
	for _, r := range records {
		byID[r.ID] = r
	}
	r, ok := byID[id]
	if !ok || r.Kind != "task" || value(r, "deleted") == "true" {
		return nil, errTaskNotFound
	}
	if value(r, "completed") != "true" {
		return nil, nil
	}
	op := func(record core.Record, field, v string) core.Operation {
		return core.Operation{ID: newID(), Record: record.ID, Kind: "task", Field: field, Value: v, Replaces: versionIDs(record, field), Source: source}
	}
	ops := []core.Operation{op(r, "completed", "false")}
	successor, _, fields, err := successorFields(r)
	if err != nil || successor == "" {
		return ops, nil
	}
	existing, exists := byID[successor]
	if !exists || value(existing, "deleted") == "true" {
		return ops, nil
	}
	for _, f := range fields {
		if f[0] != "createdAt" && value(existing, f[0]) != f[1] {
			return ops, nil
		}
	}
	return append(ops, op(existing, "deleted", "true"), op(existing, "retracted", "true")), nil
}

// successorFields returns the generated successor of a recurring task, or "" when none applies.
func successorFields(r core.Record) (string, string, [][2]string, error) {
	t := task(r, Task{})
	if t.Recurrence == nil || t.DueDate == "" {
		return "", "", nil, nil
	}
	due, err := time.Parse(time.DateOnly, t.DueDate)
	if err != nil {
		return "", "", nil, nil
	}
	date, err := nextDate(due, *t.Recurrence)
	if err != nil {
		return "", "", nil, err
	}
	next := date.Format(time.DateOnly)
	checklist := make([]ChecklistItem, len(t.Checklist))
	for i, item := range t.Checklist {
		checklist[i] = ChecklistItem{ID: item.ID, Title: item.Title}
	}
	fields := [][2]string{
		{"title", value(r, "title")}, {"details", t.Details}, {"completed", "false"}, {"priority", strconv.Itoa(t.Priority)},
		{"dueDate", next}, {"dueTime", t.DueTime}, {"timeZone", t.TimeZone},
		{"checklist", encode(checklist)}, {"recurrence", encode(t.Recurrence)}, {"tags", encode(t.Tags)},
		{"parentId", r.ID}, {"deleted", "false"}, {"retracted", "false"}, {"createdAt", strconv.FormatInt(t.CreatedAt, 10)},
	}
	if t.Rollover != nil {
		fields = append(fields, [2]string{"rollover", strconv.FormatBool(*t.Rollover)})
	}
	return nameUUID(r.ID + ":" + next), next, fields, nil
}

var errTaskNotFound = errors.New("task_not_found")
