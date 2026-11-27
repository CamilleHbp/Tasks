package tasks

import (
	"crypto/sha256"
	"fmt"
	core "github.com/CamilleHbp/sync-core"
	"path/filepath"
	"testing"
)

func TestMigrateSavedAddressPreservesWorkspaceAndToken(t *testing.T) {
	for _, old := range []string{primaryAddress, secondaryAddress, "https://selfhost.example"} {
		dir := t.TempDir()
		db, err := core.OpenDatabase(filepath.Join(dir, "sync.sqlite"))
		if err != nil {
			t.Fatal(err)
		}
		if err = db.Put("identity", map[string]string{"origin": old, "token": "sample", "workspace": "keep"}); err != nil {
			t.Fatal(err)
		}
		for i := 0; i < 2; i++ {
			if err = MigrateAddress(dir); err != nil {
				t.Fatal(err)
			}
		}
		var result map[string]string
		if err = db.Get("identity", &result); err != nil {
			t.Fatal(err)
		}
		db.Close()
		token := "sample"
		if old != "https://selfhost.example" {
			token = fmt.Sprintf("%x.sample", sha256.Sum256([]byte(old)))
		}
		if result["workspace"] != "keep" || result["origin"] != DisplayAddress(old) || result["token"] != token {
			t.Fatal("session migration changed identity or credential")
		}
	}
}
