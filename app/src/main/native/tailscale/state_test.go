package main

import (
	"bytes"
	"crypto/rand"
	"os"
	"testing"

	"tailscale.com/ipn"
)

func TestEncryptedStateReopensAndRejectsTampering(t *testing.T) {
	dir := t.TempDir()
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		t.Fatal(err)
	}
	store, err := newEncryptedStore(dir, key)
	if err != nil {
		t.Fatal(err)
	}
	id := ipn.StateKey("profile-input")
	input := []byte("private node state input")
	if err = store.WriteState(id, input); err != nil {
		t.Fatal(err)
	}
	onDisk, err := os.ReadFile(store.path(id))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(onDisk, input) {
		t.Fatal("plaintext written to state file")
	}
	reopened, err := newEncryptedStore(dir, key)
	if err != nil {
		t.Fatal(err)
	}
	actual, err := reopened.ReadState(id)
	if err != nil || !bytes.Equal(actual, input) {
		t.Fatalf("state not recovered: %v", err)
	}
	onDisk[len(onDisk)-1] ^= 1
	if err = os.WriteFile(store.path(id), onDisk, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = reopened.ReadState(id); err == nil {
		t.Fatal("tampered state accepted")
	}
	if err = ipn.WriteState(reopened, id, input); err == nil {
		t.Fatal("upstream write replaced damaged identity")
	}
	retained, err := os.ReadFile(store.path(id))
	if err != nil || !bytes.Equal(retained, onDisk) {
		t.Fatal("damaged identity was not retained")
	}
}

func TestEncryptedStateRejectsWrongKeyAndDeletes(t *testing.T) {
	dir := t.TempDir()
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		t.Fatal(err)
	}
	store, err := newEncryptedStore(dir, key)
	if err != nil {
		t.Fatal(err)
	}
	id := ipn.StateKey("profile-input")
	if err = store.WriteState(id, []byte("state input")); err != nil {
		t.Fatal(err)
	}
	key[0] ^= 1
	wrong, err := newEncryptedStore(dir, key)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = wrong.ReadState(id); err == nil {
		t.Fatal("wrong key accepted")
	}
	if err = store.WriteState(id, nil); err != nil {
		t.Fatal(err)
	}
	if _, err = store.ReadState(id); err != ipn.ErrStateNotExist {
		t.Fatalf("deletion not persisted: %v", err)
	}
}
