package tasks

import (
	"crypto/sha256"
	"fmt"
	core "github.com/CamilleHbp/sync-core"
	"os"
	"path/filepath"
	"strings"
)

const PublicAddress = "https://tasks.camille.studio"
const primaryAddress = "https://vervetwodo.195-15-214-30.sslip.io"
const secondaryAddress = "https://vervetwodo-emy.195-15-214-30.sslip.io"

func DisplayAddress(origin string) string {
	switch strings.TrimRight(origin, "/") {
	case "", PublicAddress, primaryAddress, secondaryAddress:
		return PublicAddress
	default:
		return origin
	}
}

// MigrateAddress updates the transport envelope before opening a device replica.
// The proxy unwraps this prefix before passing the original session to its owner.
func MigrateAddress(directory string) error {
	path := filepath.Join(directory, "sync.sqlite")
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return nil
	} else if err != nil {
		return err
	}
	db, err := core.OpenDatabase(path)
	if err != nil {
		return err
	}
	defer db.Close()
	for _, previous := range []string{primaryAddress, secondaryAddress} {
		prefix := fmt.Sprintf("%x.", sha256.Sum256([]byte(previous)))
		_, err = db.SQL.Exec(`UPDATE meta SET value=json_set(value,'$.origin',?,'$.token',? || json_extract(value,'$.token'))
    WHERE key='identity' AND json_extract(value,'$.origin')=? AND length(json_extract(value,'$.token'))>0`, PublicAddress, prefix, previous)
		if err != nil {
			return err
		}
	}
	return nil
}
