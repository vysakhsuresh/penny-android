package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
)

const sealVersion byte = 1

// Sealer encrypts connector credentials at rest with AES-256-GCM. Output is
// version(1) || nonce(12) || ciphertext+tag. The additional data binds a ciphertext to its
// connection row, so a sealed secret copied to another row does not open.
type Sealer struct {
	aead cipher.AEAD
}

func NewSealer(key []byte) (*Sealer, error) {
	if len(key) != 32 {
		return nil, fmt.Errorf("connector key must be 32 bytes, got %d", len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Sealer{aead: aead}, nil
}

// ParseConnectorKey decodes a base64 (standard or URL alphabet, padded or not) 32-byte key.
func ParseConnectorKey(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		if k, err := enc.DecodeString(s); err == nil {
			if len(k) != 32 {
				return nil, fmt.Errorf("CONNECTOR_KEY must decode to 32 bytes, got %d", len(k))
			}
			return k, nil
		}
	}
	return nil, errors.New("CONNECTOR_KEY is not base64")
}

// RandomSealer uses a fresh key for this process only (dev mode and tests).
func RandomSealer() *Sealer {
	k := make([]byte, 32)
	if _, err := rand.Read(k); err != nil {
		panic(err)
	}
	s, _ := NewSealer(k)
	return s
}

func (s *Sealer) Seal(plaintext []byte, aad string) []byte {
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		panic(err)
	}
	out := append([]byte{sealVersion}, nonce...)
	return s.aead.Seal(out, nonce, plaintext, []byte(aad))
}

func (s *Sealer) Open(sealed []byte, aad string) ([]byte, error) {
	n := s.aead.NonceSize()
	if len(sealed) < 1+n+s.aead.Overhead() || sealed[0] != sealVersion {
		return nil, errors.New("sealed secret is malformed")
	}
	return s.aead.Open(nil, sealed[1:1+n], sealed[1+n:], []byte(aad))
}
