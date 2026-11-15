package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"mime"
	"net/http"
	"net/mail"
	"regexp"
	"strings"
	"sync/atomic"
	"unicode/utf8"
)

// Email forwarding (CP-06). Each account gets an address <10 base32 chars>@MAIL_DOMAIN. An
// email provider posts inbound mail to /v1/mail/inbound; the server seals {from, subject, text,
// receivedAt} to the account's mail public key within that request and stores only the
// ciphertext until a device fetches and deletes it (14 days at most). Plaintext is never
// written to the database or logs.
//
// Sealing scheme "penny-mail-v1" (ECIES), with curve P-256 (preferred) or X25519:
//
//	eph        = fresh key pair on the recipient's curve
//	shared     = ECDH(eph.private, recipient)        // 32 bytes (P-256: the X coordinate)
//	salt       = ephPub || recipientPub              // P-256: 65+65 uncompressed, X25519: 32+32
//	key        = HKDF-SHA256(ikm=shared, salt, info="penny-mail-v1", length=32)
//	nonce      = 12 random bytes
//	ct         = AES-256-GCM(key, nonce, plaintext, aad="penny-mail-v1")   // ciphertext || 16-byte tag
//	blob       = ephPub || nonce || ct
const (
	defaultMailDomain = "in.penny.example"
	mailScheme        = "penny-mail-v1"
	maxPendingMail    = 50
	maxInboundBody    = 2 << 20
	maxMailText       = 64 << 10
	maxMailSubject    = 1 << 10
	maxMailFrom       = 512
	mailLocalLen      = 10
	mailWebhookHeader = "X-Penny-Webhook"
)

const base32Lower = "abcdefghijklmnopqrstuvwxyz234567"

var mailLocalRe = regexp.MustCompile(`^[a-z2-7]{10}$`)

// mailStats counts inbound outcomes for operators. Counts only; nothing about the mail.
type mailStats struct {
	stored, discardedNoKey, unknownAddress, droppedOverCap atomic.Int64
}

// ---- JSON shapes ----

type mailAddressReq struct {
	Rotate bool `json:"rotate"`
}

type mailAddressJSON struct {
	Address string `json:"address"`
	KeyAlg  string `json:"keyAlg"`
}

type mailKeyReq struct {
	P256   string `json:"p256,omitempty"`
	X25519 string `json:"x25519,omitempty"`
}

type pendingMailJSON struct {
	ID         string `json:"id"`
	Alg        string `json:"alg"`
	Blob       string `json:"blob"`
	ReceivedAt int64  `json:"receivedAt"`
	ExpiresAt  int64  `json:"expiresAt"`
}

// inboundMailReq is what an email provider posts (JSON or form fields with the same names).
type inboundMailReq struct {
	To      string `json:"to"`
	From    string `json:"from"`
	Subject string `json:"subject"`
	Text    string `json:"text"`
	HTML    string `json:"html"`
}

// mailPlaintext is the sealed payload the device decrypts.
type mailPlaintext struct {
	From       string `json:"from"`
	Subject    string `json:"subject"`
	Text       string `json:"text"`
	ReceivedAt int64  `json:"receivedAt"`
}

// ---- crypto ----

func mailCurve(alg string) (ecdh.Curve, error) {
	switch alg {
	case MailKeyP256:
		return ecdh.P256(), nil
	case MailKeyX25519:
		return ecdh.X25519(), nil
	}
	return nil, fmt.Errorf("unknown mail key algorithm %q", alg)
}

// sealMail encrypts plaintext to the recipient public key with the penny-mail-v1 scheme.
func sealMail(alg string, recipientPub, plaintext []byte) ([]byte, error) {
	curve, err := mailCurve(alg)
	if err != nil {
		return nil, err
	}
	recipient, err := curve.NewPublicKey(recipientPub)
	if err != nil {
		return nil, err
	}
	eph, err := curve.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	shared, err := eph.ECDH(recipient)
	if err != nil {
		return nil, err
	}
	defer clear(shared)
	ephPub := eph.PublicKey().Bytes()
	salt := make([]byte, 0, len(ephPub)+len(recipientPub))
	salt = append(append(salt, ephPub...), recipientPub...)
	key, err := hkdf.Key(sha256.New, shared, salt, mailScheme, 32)
	if err != nil {
		return nil, err
	}
	defer clear(key)
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, len(ephPub)+aead.NonceSize()+len(plaintext)+aead.Overhead())
	out = append(out, ephPub...)
	nonce := make([]byte, aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	out = append(out, nonce...)
	return aead.Seal(out, nonce, plaintext, []byte(mailScheme)), nil
}

// openMail is the reference decryption (used by tests; devices implement the same steps).
func openMail(priv *ecdh.PrivateKey, blob []byte) ([]byte, error) {
	recipientPub := priv.PublicKey().Bytes()
	n := len(recipientPub) // ephemeral key has the same encoding length
	if len(blob) < n+12+16 {
		return nil, errors.New("mail blob too short")
	}
	eph, err := priv.Curve().NewPublicKey(blob[:n])
	if err != nil {
		return nil, err
	}
	shared, err := priv.ECDH(eph)
	if err != nil {
		return nil, err
	}
	salt := append(append([]byte{}, blob[:n]...), recipientPub...)
	key, err := hkdf.Key(sha256.New, shared, salt, mailScheme, 32)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return aead.Open(nil, blob[n:n+12], blob[n+12:], []byte(mailScheme))
}

// ---- helpers ----

func newMailLocal() string {
	b := make([]byte, mailLocalLen)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	for i := range b {
		b[i] = base32Lower[b[i]&31] // 256 is a multiple of 32, so this is uniform
	}
	return string(b)
}

func newMailID() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return "ml-" + hex.EncodeToString(b)
}

func (s *Server) mailAddress(local string) string { return local + "@" + s.mailDomain }

// mailLocal finds the first recipient on the forwarding domain and returns its local part
// (lowercased, "+tag" removed), or "" if none is a well-formed forwarding address.
func (s *Server) mailLocal(to string) string {
	var addrs []string
	if list, err := mail.ParseAddressList(to); err == nil {
		for _, a := range list {
			addrs = append(addrs, a.Address)
		}
	} else {
		for _, p := range strings.Split(to, ",") {
			addrs = append(addrs, strings.Trim(strings.TrimSpace(p), "<>"))
		}
	}
	for _, a := range addrs {
		at := strings.LastIndexByte(a, '@')
		if at <= 0 || !strings.EqualFold(a[at+1:], s.mailDomain) {
			continue
		}
		local := strings.ToLower(a[:at])
		if i := strings.IndexByte(local, '+'); i >= 0 {
			local = local[:i]
		}
		if mailLocalRe.MatchString(local) {
			return local
		}
	}
	return ""
}

// truncateUTF8 cuts s to at most n bytes without splitting a character.
func truncateUTF8(s string, n int) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}

var (
	htmlCommentRe = regexp.MustCompile(`(?s)<!--.*?-->`)
	htmlDropRe    = regexp.MustCompile(`(?is)<(script|style|head|title)\b[^>]*>.*?</(script|style|head|title)\s*>`)
	htmlBreakRe   = regexp.MustCompile(`(?i)<(br|hr|p|div|tr|li|h[1-6]|table|/p|/div|/tr|/li|/h[1-6]|/table)\b[^>]*>`)
	htmlCellRe    = regexp.MustCompile(`(?i)</t[dh]\s*>`)
	htmlTagRe     = regexp.MustCompile(`(?s)<[^>]*>`)
)

// htmlToText is a deliberately simple tag stripper: drop comments, scripts and styles, turn
// block tags into line breaks and table cells into spaces, remove remaining tags, decode
// entities, then collapse whitespace.
func htmlToText(h string) string {
	h = htmlCommentRe.ReplaceAllString(h, "")
	h = htmlDropRe.ReplaceAllString(h, "")
	h = htmlBreakRe.ReplaceAllString(h, "\n")
	h = htmlCellRe.ReplaceAllString(h, " ")
	h = htmlTagRe.ReplaceAllString(h, "")
	h = html.UnescapeString(h)
	var b strings.Builder
	blank := true // suppresses leading and repeated blank lines
	for _, line := range strings.Split(h, "\n") {
		line = strings.Join(strings.Fields(line), " ")
		if line == "" {
			if !blank {
				b.WriteByte('\n')
				blank = true
			}
			continue
		}
		b.WriteString(line)
		b.WriteByte('\n')
		blank = false
	}
	return strings.TrimSpace(b.String())
}

// readInbound parses a JSON, multipart or urlencoded webhook body.
func readInbound(w http.ResponseWriter, r *http.Request) (inboundMailReq, bool) {
	var req inboundMailReq
	mt, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	switch mt {
	case "multipart/form-data", "application/x-www-form-urlencoded":
		r.Body = http.MaxBytesReader(w, r.Body, maxInboundBody)
		var err error
		if mt == "multipart/form-data" {
			// The whole body fits in memory (maxMemory = body limit), so nothing goes to disk.
			err = r.ParseMultipartForm(maxInboundBody)
			if r.MultipartForm != nil {
				defer func() { _ = r.MultipartForm.RemoveAll() }()
			}
		} else {
			err = r.ParseForm()
		}
		if err != nil {
			var mbe *http.MaxBytesError
			if errors.As(err, &mbe) {
				writeErr(w, http.StatusRequestEntityTooLarge, "request body too large")
			} else {
				writeErr(w, http.StatusBadRequest, "invalid form body")
			}
			return req, false
		}
		req = inboundMailReq{To: r.PostFormValue("to"), From: r.PostFormValue("from"), Subject: r.PostFormValue("subject"),
			Text: r.PostFormValue("text"), HTML: r.PostFormValue("html")}
		return req, true
	default:
		return req, decodeJSON(w, r, &req, maxInboundBody)
	}
}

// webhookOK checks the shared secret in constant time (hashing both sides first so the
// comparison does not leak the secret's length).
func (s *Server) webhookOK(w http.ResponseWriter, r *http.Request) bool {
	if s.mailSecret == "" {
		if s.dev {
			return true
		}
		writeErr(w, http.StatusServiceUnavailable, "mail forwarding is not configured")
		return false
	}
	got := r.Header.Get(mailWebhookHeader)
	if got == "" {
		writeErr(w, http.StatusUnauthorized, "missing webhook secret")
		return false
	}
	a, b := sha256.Sum256([]byte(got)), sha256.Sum256([]byte(s.mailSecret))
	if subtle.ConstantTimeCompare(a[:], b[:]) != 1 {
		writeErr(w, http.StatusForbidden, "invalid webhook secret")
		return false
	}
	return true
}

// ---- handlers ----

func (s *Server) getMailAddress(w http.ResponseWriter, r *http.Request, c caller) {
	box, err := s.store.GetMailBox(r.Context(), c.AccountID)
	if errors.Is(err, ErrNotFound) || (err == nil && box.Local == "") {
		writeErr(w, http.StatusNotFound, "no forwarding address")
		return
	} else if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, mailAddressJSON{Address: s.mailAddress(box.Local), KeyAlg: box.KeyAlg})
}

// postMailAddress returns the account's forwarding address, creating it on first use.
// {rotate:true} replaces it; the old address stops working at once. Pending mail stays.
func (s *Server) postMailAddress(w http.ResponseWriter, r *http.Request, c caller) {
	var req mailAddressReq
	if !decodeOptionalJSON(w, r, &req, smallBody) {
		return
	}
	ctx := r.Context()
	box, err := s.store.GetMailBox(ctx, c.AccountID)
	if err != nil && !errors.Is(err, ErrNotFound) {
		s.fail(w, r, err)
		return
	}
	if box.Local != "" && !req.Rotate {
		writeJSON(w, http.StatusOK, mailAddressJSON{Address: s.mailAddress(box.Local), KeyAlg: box.KeyAlg})
		return
	}
	for range 5 {
		local := newMailLocal()
		err = s.store.SetMailAddress(ctx, c.AccountID, local, s.now())
		if errors.Is(err, ErrConflict) {
			continue // 50 random bits: a collision is rare; draw again
		}
		if err != nil {
			s.fail(w, r, err)
			return
		}
		s.log.Info("mail address issued", "rotate", req.Rotate)
		writeJSON(w, http.StatusOK, mailAddressJSON{Address: s.mailAddress(local), KeyAlg: box.KeyAlg})
		return
	}
	s.fail(w, r, errors.New("could not allocate a unique mail address"))
}

// deleteMailAddress turns forwarding off: removes the address, the key and pending mail.
func (s *Server) deleteMailAddress(w http.ResponseWriter, r *http.Request, c caller) {
	if err := s.store.DeleteMailBox(r.Context(), c.AccountID); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) putMailKey(w http.ResponseWriter, r *http.Request, c caller) {
	var req mailKeyReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	alg, enc := MailKeyP256, req.P256
	switch {
	case req.P256 != "" && req.X25519 != "":
		writeErr(w, http.StatusBadRequest, "send exactly one of p256 or x25519")
		return
	case req.X25519 != "":
		alg, enc = MailKeyX25519, req.X25519
	case req.P256 == "":
		writeErr(w, http.StatusBadRequest, "p256 or x25519 is required")
		return
	}
	raw, err := decodeB64(enc)
	if err != nil {
		writeErr(w, http.StatusBadRequest, alg+" is not base64url")
		return
	}
	curve, _ := mailCurve(alg)
	if _, err := curve.NewPublicKey(raw); err != nil {
		if alg == MailKeyP256 {
			writeErr(w, http.StatusBadRequest, "p256 must be a 65-byte uncompressed P-256 point")
		} else {
			writeErr(w, http.StatusBadRequest, "x25519 must be a 32-byte X25519 public key")
		}
		return
	}
	if err := s.store.SetMailKey(r.Context(), c.AccountID, alg, raw, c.DeviceID, s.now()); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) listPendingMail(w http.ResponseWriter, r *http.Request, c caller) {
	ms, err := s.store.ListMail(r.Context(), c.AccountID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]pendingMailJSON, len(ms))
	for i, m := range ms {
		out[i] = pendingMailJSON{ID: m.ID, Alg: m.Alg, Blob: b64.EncodeToString(m.Blob), ReceivedAt: m.ReceivedAt.UnixMilli(),
			ExpiresAt: m.ReceivedAt.Add(mailRetention).UnixMilli()}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) deletePendingMail(w http.ResponseWriter, r *http.Request, c caller) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if err := s.store.DeleteMail(r.Context(), c.AccountID, id); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// inboundMail is the provider webhook. It answers 204 both when the mail was stored and when
// it was discarded because the account has no key yet (so the provider does not retry), and
// 404 with a generic body for addresses that do not exist.
func (s *Server) inboundMail(w http.ResponseWriter, r *http.Request) {
	if !s.webhookOK(w, r) {
		return
	}
	req, ok := readInbound(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	local := s.mailLocal(req.To)
	if local == "" {
		s.mailStats.unknownAddress.Add(1)
		writeErr(w, http.StatusNotFound, "not found")
		return
	}
	box, err := s.store.MailBoxByLocal(ctx, local)
	if errors.Is(err, ErrNotFound) {
		s.mailStats.unknownAddress.Add(1)
		writeErr(w, http.StatusNotFound, "not found")
		return
	} else if err != nil {
		s.fail(w, r, err)
		return
	}
	if box.KeyAlg == "" || len(box.PublicKey) == 0 {
		n := s.mailStats.discardedNoKey.Add(1)
		s.log.Info("mail discarded", "reason", "no_key", "total", n)
		w.WriteHeader(http.StatusNoContent)
		return
	}
	now := s.now()
	text := req.Text
	if strings.TrimSpace(text) == "" && req.HTML != "" {
		text = htmlToText(req.HTML)
	}
	plain, err := json.Marshal(mailPlaintext{
		From:       truncateUTF8(strings.TrimSpace(req.From), maxMailFrom),
		Subject:    truncateUTF8(strings.TrimSpace(req.Subject), maxMailSubject),
		Text:       truncateUTF8(text, maxMailText),
		ReceivedAt: now.UnixMilli(),
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	blob, err := sealMail(box.KeyAlg, box.PublicKey, plain)
	clear(plain) // best-effort: the request strings are garbage once this handler returns
	if err != nil {
		s.fail(w, r, err)
		return
	}
	dropped, err := s.store.AddMail(ctx, PendingMail{ID: newMailID(), AccountID: box.AccountID, Alg: box.KeyAlg,
		Blob: blob, ReceivedAt: now}, maxPendingMail)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.mailStats.stored.Add(1)
	if dropped > 0 {
		s.mailStats.droppedOverCap.Add(int64(dropped))
	}
	s.log.Info("mail sealed", "bytes", len(blob), "dropped_old", dropped)
	w.WriteHeader(http.StatusNoContent)
}
