package main

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
	"time"
)

const testWebhookSecret = "hook-secret-5f2a"

type mailEnv struct {
	*testEnv
	srv *Server
}

func newMailEnv(t *testing.T, secret string, dev bool) *mailEnv {
	t.Helper()
	clock := &fakeClock{t: time.Date(2026, 3, 29, 1, 30, 0, 0, time.UTC)}
	mail := &captureMailer{codes: map[string]string{}}
	st := NewMemoryStore()
	srv := NewServer(Options{Store: st, Mailer: mail, Region: "eu", Pepper: []byte("test-pepper"), Now: clock.Now,
		Dev: dev, MailDomain: "In.Penny.Example", MailWebhookSecret: secret, MerchantSalt: []byte("merchant-salt")})
	return &mailEnv{testEnv: &testEnv{t: t, h: srv.Handler(), clock: clock, mail: mail, store: st}, srv: srv}
}

// inbound posts a webhook body as JSON with the given secret header ("" = none).
func (e *mailEnv) inbound(secret string, body inboundMailReq) resp {
	e.t.Helper()
	raw, _ := json.Marshal(body)
	r := httptest.NewRequest("POST", "/v1/mail/inbound", bytes.NewReader(raw))
	r.Header.Set("Content-Type", "application/json")
	if secret != "" {
		r.Header.Set(mailWebhookHeader, secret)
	}
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	return resp{status: w.Code, body: w.Body.Bytes(), header: w.Header()}
}

func (e *mailEnv) address(tok string, rotate bool) string {
	e.t.Helper()
	r := e.do("POST", "/v1/mail/address", tok, mailAddressReq{Rotate: rotate})
	expect(e.t, r, http.StatusOK)
	var a mailAddressJSON
	r.decode(e.t, &a)
	return a.Address
}

func (e *mailEnv) pending(tok string) []pendingMailJSON {
	e.t.Helper()
	r := e.do("GET", "/v1/mail/pending", tok, nil)
	expect(e.t, r, http.StatusOK)
	var out []pendingMailJSON
	r.decode(e.t, &out)
	return out
}

func newP256(t *testing.T) (*ecdh.PrivateKey, string) {
	t.Helper()
	k, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k, b64.EncodeToString(k.PublicKey().Bytes())
}

// decryptDocumented opens a blob following the README byte layout step by step, independently
// of openMail: ephPub(65 for P-256, 32 for X25519) || nonce(12) || ciphertext+tag(16).
func decryptDocumented(t *testing.T, priv *ecdh.PrivateKey, blobB64 string) mailPlaintext {
	t.Helper()
	blob, err := decodeB64(blobB64)
	if err != nil {
		t.Fatal(err)
	}
	pubLen := 65
	if priv.Curve() == ecdh.X25519() {
		pubLen = 32
	}
	ephRaw, nonce, ct := blob[:pubLen], blob[pubLen:pubLen+12], blob[pubLen+12:]
	if priv.Curve() == ecdh.P256() && ephRaw[0] != 0x04 {
		t.Fatalf("P-256 ephemeral key is not uncompressed: %x", ephRaw[0])
	}
	eph, err := priv.Curve().NewPublicKey(ephRaw)
	if err != nil {
		t.Fatal(err)
	}
	shared, err := priv.ECDH(eph)
	if err != nil {
		t.Fatal(err)
	}
	if len(shared) != 32 {
		t.Fatalf("shared secret is %d bytes", len(shared))
	}
	salt := append(append([]byte{}, ephRaw...), priv.PublicKey().Bytes()...)
	key, err := hkdf.Key(sha256.New, shared, salt, "penny-mail-v1", 32)
	if err != nil {
		t.Fatal(err)
	}
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	plain, err := gcm.Open(nil, nonce, ct, []byte("penny-mail-v1"))
	if err != nil {
		t.Fatalf("decrypt: %v", err)
	}
	var m mailPlaintext
	if err := json.Unmarshal(plain, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

var addrRe = regexp.MustCompile(`^[a-z2-7]{10}@in\.penny\.example$`)

func TestMailAddressLifecycle(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	a := e.signIn("a@example.com")

	expect(t, e.do("GET", "/v1/mail/address", a.Token, nil), http.StatusNotFound)
	addr := e.address(a.Token, false)
	if !addrRe.MatchString(addr) {
		t.Fatalf("address %q has the wrong shape", addr)
	}
	if again := e.address(a.Token, false); again != addr {
		t.Fatalf("POST without rotate changed the address: %q -> %q", addr, again)
	}
	// Empty body is accepted too.
	expect(t, e.do("POST", "/v1/mail/address", a.Token, nil), http.StatusOK)
	r := e.do("GET", "/v1/mail/address", a.Token, nil)
	expect(t, r, http.StatusOK)
	var got mailAddressJSON
	r.decode(t, &got)
	if got.Address != addr || got.KeyAlg != "" {
		t.Fatalf("GET = %+v", got)
	}

	rotated := e.address(a.Token, true)
	if rotated == addr || !addrRe.MatchString(rotated) {
		t.Fatalf("rotate gave %q (old %q)", rotated, addr)
	}
	_, pub := newP256(t)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{P256: pub}), http.StatusNoContent)
	expect(t, e.inbound(testWebhookSecret, inboundMailReq{To: addr, Subject: "x", Text: "y"}), http.StatusNotFound)
	expect(t, e.inbound(testWebhookSecret, inboundMailReq{To: rotated, Subject: "x", Text: "y"}), http.StatusNoContent)

	// Turning forwarding off removes the address, key and pending mail.
	expect(t, e.do("DELETE", "/v1/mail/address", a.Token, nil), http.StatusNoContent)
	expect(t, e.do("GET", "/v1/mail/address", a.Token, nil), http.StatusNotFound)
	if n := len(e.pending(a.Token)); n != 0 {
		t.Fatalf("pending after delete = %d", n)
	}
	expect(t, e.inbound(testWebhookSecret, inboundMailReq{To: rotated, Text: "y"}), http.StatusNotFound)
	expect(t, e.do("GET", "/v1/mail/address", "", nil), http.StatusUnauthorized)
}

func TestMailKeyValidation(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	a := e.signIn("a@example.com")
	_, p256 := newP256(t)
	x, _ := ecdh.X25519().GenerateKey(rand.Reader)
	x25519 := b64.EncodeToString(x.PublicKey().Bytes())

	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{}), http.StatusBadRequest)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{P256: p256, X25519: x25519}), http.StatusBadRequest)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{P256: x25519}), http.StatusBadRequest)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{X25519: p256}), http.StatusBadRequest)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{P256: "!!"}), http.StatusBadRequest)
	// 65 bytes but not on the curve.
	bad := make([]byte, 65)
	bad[0] = 4
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{P256: b64.EncodeToString(bad)}), http.StatusBadRequest)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{X25519: x25519}), http.StatusNoContent)
	box, err := e.store.GetMailBox(context.Background(), a.AccountID)
	if err != nil || box.KeyAlg != MailKeyX25519 || box.KeyDevice != a.DeviceID {
		t.Fatalf("box = %+v, %v", box, err)
	}
}

func TestInboundWebhookAuth(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	a := e.signIn("a@example.com")
	addr := e.address(a.Token, false)
	msg := inboundMailReq{To: addr, Subject: "s", Text: "t"}
	expect(t, e.inbound("", msg), http.StatusUnauthorized)
	expect(t, e.inbound("wrong", msg), http.StatusForbidden)
	expect(t, e.inbound(testWebhookSecret+"x", msg), http.StatusForbidden)
	expect(t, e.inbound(testWebhookSecret, msg), http.StatusNoContent)

	off := newMailEnv(t, "", false)
	expect(t, off.inbound("", msg), http.StatusServiceUnavailable)
	dev := newMailEnv(t, "", true)
	expect(t, dev.inbound("", msg), http.StatusNotFound) // open in dev; address unknown there
	devSecret := newMailEnv(t, testWebhookSecret, true)
	expect(t, devSecret.inbound("", msg), http.StatusUnauthorized) // a configured secret is enforced in dev too
}

func TestInboundUnknownAddress(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	for _, to := range []string{"", "abcdefghij@in.penny.example", "nobody@elsewhere.example", "garbage", "ABC@in.penny.example"} {
		r := e.inbound(testWebhookSecret, inboundMailReq{To: to, Subject: "secret subject", Text: "body"})
		expect(t, r, http.StatusNotFound)
		if string(bytes.TrimSpace(r.body)) != `{"error":"not found"}` {
			t.Fatalf("404 body leaks detail: %s", r.body)
		}
	}
	if n := e.srv.mailStats.unknownAddress.Load(); n != 5 {
		t.Fatalf("unknownAddress = %d", n)
	}
}

func TestInboundSealsToP256(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	a := e.signIn("a@example.com")
	addr := e.address(a.Token, false)
	priv, pub := newP256(t)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{P256: pub}), http.StatusNoContent)

	const subject = "Your Uber receipt 4471"
	const body = "Total paid INR 312.50 to Uber India"
	// Display-name form, mixed case, +tag, several recipients.
	to := `"Someone" <other@example.com>, "Penny" <` + strings.ToUpper(strings.Replace(addr, "@", "+receipts@", 1)) + `>`
	expect(t, e.inbound(testWebhookSecret, inboundMailReq{To: to, From: "Uber <noreply@uber.com>", Subject: subject, Text: body}), http.StatusNoContent)

	ps := e.pending(a.Token)
	if len(ps) != 1 {
		t.Fatalf("pending = %d", len(ps))
	}
	p := ps[0]
	if p.Alg != MailKeyP256 || p.ReceivedAt != e.clock.Now().UnixMilli() || p.ExpiresAt != e.clock.Now().Add(14*24*time.Hour).UnixMilli() {
		t.Fatalf("pending = %+v", p)
	}
	stored, _ := e.store.ListMail(context.Background(), a.AccountID)
	raw := stored[0].Blob
	for _, secret := range []string{subject, body, "uber", "Uber", "noreply"} {
		if bytes.Contains(raw, []byte(secret)) || strings.Contains(p.Blob, secret) {
			t.Fatalf("stored blob contains plaintext %q", secret)
		}
	}
	if len(raw) < 65+12+16 {
		t.Fatalf("blob too short: %d", len(raw))
	}
	m := decryptDocumented(t, priv, p.Blob)
	if m.Subject != subject || m.Text != body || m.From != "Uber <noreply@uber.com>" || m.ReceivedAt != p.ReceivedAt {
		t.Fatalf("decrypted = %+v", m)
	}
	// The reference implementation agrees.
	if _, err := openMail(priv, raw); err != nil {
		t.Fatal(err)
	}
	// Another key cannot open it.
	other, _ := newP256(t)
	if _, err := openMail(other, raw); err == nil {
		t.Fatal("opened with the wrong key")
	}
}

func TestInboundSealsToX25519AndForms(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	a := e.signIn("a@example.com")
	addr := e.address(a.Token, false)
	priv, _ := ecdh.X25519().GenerateKey(rand.Reader)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{X25519: b64.EncodeToString(priv.PublicKey().Bytes())}), http.StatusNoContent)

	// Multipart form with HTML only.
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	_ = mw.WriteField("to", addr)
	_ = mw.WriteField("from", "shop@example.com")
	_ = mw.WriteField("subject", "Order")
	_ = mw.WriteField("html", `<html><head><style>p{color:red}</style></head><body><!-- hi --><p>Total:&nbsp;<b>&euro;12.00</b></p>`+
		`<table><tr><td>Item</td><td>Tea</td></tr></table><script>alert(1)</script><br>Thanks &amp; bye</body></html>`)
	_ = mw.Close()
	r := httptest.NewRequest("POST", "/v1/mail/inbound", &buf)
	r.Header.Set("Content-Type", mw.FormDataContentType())
	r.Header.Set(mailWebhookHeader, testWebhookSecret)
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("multipart status %d: %s", w.Code, w.Body)
	}
	// URL-encoded form with text, a second later.
	e.clock.Advance(time.Second)
	form := "to=" + addr + "&subject=Hello&text=plain+body"
	r = httptest.NewRequest("POST", "/v1/mail/inbound", strings.NewReader(form))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	r.Header.Set(mailWebhookHeader, testWebhookSecret)
	w = httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("form status %d: %s", w.Code, w.Body)
	}

	ps := e.pending(a.Token)
	if len(ps) != 2 || ps[0].Alg != MailKeyX25519 {
		t.Fatalf("pending = %+v", ps)
	}
	m := decryptDocumented(t, priv, ps[0].Blob)
	if m.Text != "Total: €12.00\n\nItem Tea\n\nThanks & bye" || m.Subject != "Order" {
		t.Fatalf("html text = %q", m.Text)
	}
	if m2 := decryptDocumented(t, priv, ps[1].Blob); m2.Text != "plain body" || m2.Subject != "Hello" {
		t.Fatalf("form mail = %+v", m2)
	}
}

func TestInboundTruncatesText(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	a := e.signIn("a@example.com")
	addr := e.address(a.Token, false)
	priv, pub := newP256(t)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{P256: pub}), http.StatusNoContent)
	long := strings.Repeat("é", 40_000) // 80,000 bytes
	expect(t, e.inbound(testWebhookSecret, inboundMailReq{To: addr, Text: long}), http.StatusNoContent)
	m := decryptDocumented(t, priv, e.pending(a.Token)[0].Blob)
	if len(m.Text) != 64<<10 || !strings.HasPrefix(long, m.Text) {
		t.Fatalf("text is %d bytes", len(m.Text))
	}
}

func TestInboundWithoutKeyIsDiscarded(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	a := e.signIn("a@example.com")
	addr := e.address(a.Token, false)
	expect(t, e.inbound(testWebhookSecret, inboundMailReq{To: addr, Subject: "s", Text: "t"}), http.StatusNoContent)
	if n := len(e.pending(a.Token)); n != 0 {
		t.Fatalf("pending = %d, want 0 (no key, nothing stored)", n)
	}
	if n := e.srv.mailStats.discardedNoKey.Load(); n != 1 {
		t.Fatalf("discardedNoKey = %d", n)
	}
}

func TestPendingMailCapDeleteAndExpiry(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	a := e.signIn("a@example.com")
	b := e.signIn("b@example.com")
	addr := e.address(a.Token, false)
	priv, pub := newP256(t)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{P256: pub}), http.StatusNoContent)
	for i := 1; i <= 52; i++ {
		e.clock.Advance(time.Second)
		expect(t, e.inbound(testWebhookSecret, inboundMailReq{To: addr, Subject: fmt.Sprintf("#%d", i)}), http.StatusNoContent)
	}
	ps := e.pending(a.Token)
	if len(ps) != 50 {
		t.Fatalf("pending = %d, want 50", len(ps))
	}
	if s := decryptDocumented(t, priv, ps[0].Blob).Subject; s != "#3" {
		t.Fatalf("oldest kept = %s, want #3", s)
	}
	if s := decryptDocumented(t, priv, ps[49].Blob).Subject; s != "#52" {
		t.Fatalf("newest = %s", s)
	}
	if n := e.srv.mailStats.droppedOverCap.Load(); n != 2 {
		t.Fatalf("droppedOverCap = %d", n)
	}

	// Delete after parsing; only the owner can.
	expect(t, e.do("DELETE", "/v1/mail/"+ps[0].ID, b.Token, nil), http.StatusNotFound)
	expect(t, e.do("DELETE", "/v1/mail/"+ps[0].ID, a.Token, nil), http.StatusNoContent)
	expect(t, e.do("DELETE", "/v1/mail/"+ps[0].ID, a.Token, nil), http.StatusNotFound)
	if n := len(e.pending(a.Token)); n != 49 {
		t.Fatalf("pending after delete = %d", n)
	}
	if n := len(e.pending(b.Token)); n != 0 {
		t.Fatalf("b sees %d", n)
	}

	// Expiry: 14 days after receipt the janitor drops it.
	ctx := context.Background()
	e.clock.Advance(14*24*time.Hour - 30*time.Second)
	_ = e.store.PurgeExpired(ctx, e.clock.Now())
	left := e.pending(a.Token)
	if len(left) == 0 || len(left) == 49 {
		t.Fatalf("partial expiry expected, got %d", len(left))
	}
	e.clock.Advance(time.Minute)
	_ = e.store.PurgeExpired(ctx, e.clock.Now())
	if n := len(e.pending(a.Token)); n != 0 {
		t.Fatalf("pending after expiry = %d", n)
	}
}

func TestAccountDeletionRemovesMail(t *testing.T) {
	e := newMailEnv(t, testWebhookSecret, false)
	a := e.signIn("a@example.com")
	addr := e.address(a.Token, false)
	_, pub := newP256(t)
	expect(t, e.do("PUT", "/v1/mail/key", a.Token, mailKeyReq{P256: pub}), http.StatusNoContent)
	expect(t, e.inbound(testWebhookSecret, inboundMailReq{To: addr, Text: "t"}), http.StatusNoContent)
	expect(t, e.do("DELETE", "/v1/account", a.Token, nil), http.StatusNoContent)

	ctx := context.Background()
	if _, err := e.store.GetMailBox(ctx, a.AccountID); err != ErrNotFound {
		t.Fatalf("mail box survived: %v", err)
	}
	if ms, _ := e.store.ListMail(ctx, a.AccountID); len(ms) != 0 {
		t.Fatalf("pending mail survived: %d", len(ms))
	}
	expect(t, e.inbound(testWebhookSecret, inboundMailReq{To: addr, Text: "t"}), http.StatusNotFound)
}

func TestHTMLToText(t *testing.T) {
	cases := map[string]string{
		"<p>a</p><p>b</p>":                         "a\n\nb",
		"x<br/>y":                                  "x\ny",
		"<div>  lots   of\tspace </div>":           "lots of space",
		"&lt;tag&gt; &quot;q&quot; &#8377;5":       `<tag> "q" ₹5`,
		"<STYLE>.a{}</STYLE><SCRIPT>x()</SCRIPT>k": "k",
	}
	for in, want := range cases {
		if got := htmlToText(in); got != want {
			t.Errorf("htmlToText(%q) = %q, want %q", in, got, want)
		}
	}
}
