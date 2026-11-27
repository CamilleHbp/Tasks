package main

import (
	"context"
	"fmt"
	"github.com/CamilleHbp/Tasks/sync-engine/tasks"
	shared "github.com/CamilleHbp/sync-core"
	"github.com/CamilleHbp/sync-core/appclient"
	"log"
	"os"
	"path/filepath"
	"time"
)

func provisionPath(directory string) string {
	if path := os.Getenv("SYNC_NATIVE_PROVISION"); path != "" {
		return path
	}
	path := filepath.Join(directory, "native-provision.json")
	if _, err := os.Stat(path); err == nil {
		return path
	}
	return ""
}
func (s *server) prepareNative(directory string) error {
	configPath, id, destination := os.Getenv("SYNC_NETWORK_CONFIG"), os.Getenv("SYNC_NETWORK_ID"), os.Getenv("SYNC_NATIVE_DIRECTORY")
	if configPath == "" || id == "" || destination == "" {
		return fmt.Errorf("SYNC_NETWORK_CONFIG, SYNC_NETWORK_ID and SYNC_NATIVE_DIRECTORY are required")
	}
	network, err := os.ReadFile(configPath)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	_, err = appclient.Prepare(ctx, tasks.Config(), s.e, []shared.Bundle{s.account.Bundle}, network, id, destination)
	return err
}
func (s *server) openNative(directory string) error {
	s.provisionFile = provisionPath(directory)
	if len(os.Args) > 1 && os.Args[1] == "prepare-native" {
		return nil
	}
	path := s.provisionFile
	if path == "" {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	var err error
	s.native, err = appclient.OpenGateway(ctx, directory, path, tasks.Config(), s.account.Bundle)
	if err != nil {
		return err
	}
	if err = s.flushNative(); err != nil {
		_ = s.native.Close(ctx)
		return err
	}
	go func() {
		for {
			select {
			case <-s.native.Done():
				return
			case <-s.native.Changes():
				s.mu.Lock()
				err := s.flushNative()
				s.mu.Unlock()
				if err != nil {
					log.Printf("native projection failed: %s", shared.ErrorCode(err))
				}
			}
		}
	}()
	return nil
}
func (s *server) flushNative() error {
	if s.native == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	bundles := []shared.Bundle{}
	bundles = append(bundles, s.account.Bundle)
	next, err := s.native.ReconcileLegacy(ctx, s.e, bundles)
	if err != nil {
		return err
	}
	if len(next.Store.Changes) == len(s.e.Store.Changes) {
		return nil
	}
	if err = s.db.Commit(next, nil, false, nil); err != nil {
		return err
	}
	s.e = next
	s.publishChanges()
	return nil
}
