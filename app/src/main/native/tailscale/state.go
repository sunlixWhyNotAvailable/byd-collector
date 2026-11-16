package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"tailscale.com/ipn"
)

// The data key is wrapped by the runtime's Android Keystore. No plaintext node
// state is written to disk, including temporary files.
type encryptedStore struct {
	mu   sync.Mutex
	dir  string
	aead cipher.AEAD
}

func newEncryptedStore(dir string, key []byte) (*encryptedStore, error) {
	if len(key) != 32 {
		return nil, errors.New("state key must contain 32 bytes")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, fmt.Errorf("create encrypted state directory: %w", err)
	}
	// Also retries a directory creation whose previous parent sync failed.
	if err := syncStateDirectory(filepath.Dir(dir)); err != nil {
		return nil, fmt.Errorf("persist encrypted state directory: %w", err)
	}
	return &encryptedStore{dir: dir, aead: aead}, nil
}

func syncStateDirectory(dir string) error {
	f, err := os.Open(dir)
	if err != nil {
		return fmt.Errorf("open state directory for sync: %w", err)
	}
	if err = f.Sync(); err != nil {
		err = fmt.Errorf("sync state directory: %w", err)
	}
	if closeErr := f.Close(); closeErr != nil {
		err = errors.Join(err, fmt.Errorf("close state directory: %w", closeErr))
	}
	return err
}

func (s *encryptedStore) path(id ipn.StateKey) string {
	return filepath.Join(s.dir, fmt.Sprintf("%x.state", sha256.Sum256([]byte(id))))
}

func (s *encryptedStore) ReadState(id ipn.StateKey) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.readState(id)
}

func (s *encryptedStore) readState(id ipn.StateKey) ([]byte, error) {
	data, err := os.ReadFile(s.path(id))
	if errors.Is(err, os.ErrNotExist) {
		return nil, ipn.ErrStateNotExist
	}
	if err != nil {
		return nil, fmt.Errorf("read encrypted state: %w", err)
	}
	n := s.aead.NonceSize()
	if len(data) < n+s.aead.Overhead() {
		return nil, errors.New("truncated encrypted state")
	}
	plain, err := s.aead.Open(nil, data[:n], data[n:], []byte(id))
	if err != nil {
		return nil, fmt.Errorf("decrypt state: %w", err)
	}
	return plain, nil
}

func (s *encryptedStore) WriteState(id ipn.StateKey, plain []byte) (err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// Upstream WriteState may continue after a read error. Preserve damaged identity.
	if _, err := s.readState(id); err != nil && !errors.Is(err, ipn.ErrStateNotExist) {
		return fmt.Errorf("refuse replacing unreadable state: %w", err)
	}
	path := s.path(id)
	if plain == nil {
		err := os.Remove(path)
		if err != nil && !errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("remove encrypted state: %w", err)
		}
		if err := syncStateDirectory(s.dir); err != nil {
			return fmt.Errorf("persist encrypted state deletion: %w", err)
		}
		return nil
	}
	nonce := make([]byte, s.aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return err
	}
	sealed := s.aead.Seal(nonce, nonce, plain, []byte(id))
	f, err := os.CreateTemp(s.dir, ".state-*")
	if err != nil {
		return fmt.Errorf("create encrypted temp: %w", err)
	}
	tmp := f.Name()
	defer func() {
		if removeErr := os.Remove(tmp); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
			err = errors.Join(err, fmt.Errorf("remove encrypted temp: %w", removeErr))
		}
	}()
	if _, err = f.Write(sealed); err != nil {
		err = fmt.Errorf("write encrypted temp: %w", err)
	} else if err = f.Sync(); err != nil {
		err = fmt.Errorf("sync encrypted temp: %w", err)
	}
	if closeErr := f.Close(); closeErr != nil {
		err = errors.Join(err, fmt.Errorf("close encrypted temp: %w", closeErr))
	}
	if err != nil {
		return err
	}
	if err = os.Rename(tmp, path); err != nil {
		return fmt.Errorf("rename encrypted state: %w", err)
	}
	if err = syncStateDirectory(s.dir); err != nil {
		return fmt.Errorf("persist encrypted state rename: %w", err)
	}
	return nil
}
