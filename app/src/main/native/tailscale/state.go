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
		return nil, err
	}
	return &encryptedStore{dir: dir, aead: aead}, nil
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
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	nonce := make([]byte, s.aead.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return err
	}
	sealed := s.aead.Seal(nonce, nonce, plain, []byte(id))
	f, err := os.CreateTemp(s.dir, ".state-*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	defer func() {
		if removeErr := os.Remove(tmp); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
			err = errors.Join(err, fmt.Errorf("remove encrypted temp: %w", removeErr))
		}
	}()
	if _, err = f.Write(sealed); err == nil {
		err = f.Sync()
	}
	err = errors.Join(err, f.Close())
	if err != nil {
		return err
	}
	if err = os.Rename(tmp, path); err != nil {
		return err
	}
	return nil
}
