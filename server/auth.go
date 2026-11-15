package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"math/big"
	"net"
	"net/http"
	"net/smtp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	codeTTL         = 10 * time.Minute
	maxCodeAttempts = 5
	touchInterval   = time.Minute
)

// ---- encoding and hashing helpers ----

var b64 = base64.RawURLEncoding

// decodeB64 accepts base64url with or without padding.
func decodeB64(s string) ([]byte, error) {
	return b64.DecodeString(strings.TrimRight(s, "="))
}

func randomB64(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand never fails on supported platforms
	}
	return b64.EncodeToString(b)
}

func randomCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		panic(err)
	}
	return fmt.Sprintf("%06d", n.Int64())
}

func sha256Hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func normalizeEmail(e string) string { return strings.ToLower(strings.TrimSpace(e)) }

func validEmail(e string) bool {
	at := strings.LastIndexByte(e, '@')
	return len(e) <= 254 && at > 0 && at < len(e)-1 && !strings.ContainsAny(e, " \t\r\n<>,;")
}

// emailHash is a keyed hash so a database dump cannot be reversed with a list of addresses.
func (s *Server) emailHash(email string) string {
	m := hmac.New(sha256.New, s.pepper)
	m.Write([]byte(email))
	return hex.EncodeToString(m.Sum(nil))
}

func codeHash(emailHash, code string) string { return sha256Hex(emailHash + ":" + code) }

// ---- mailers ----

// Mailer delivers sign-in codes.
type Mailer interface {
	SendCode(ctx context.Context, to, code string) error
}

// LogMailer is the development mailer. It prints codes to stdout only in dev mode and never logs them.
type LogMailer struct {
	Dev bool
	Out io.Writer
	Log *slog.Logger
}

func (m LogMailer) SendCode(_ context.Context, to, code string) error {
	if m.Dev {
		_, err := fmt.Fprintf(m.Out, "[dev] sign-in code for %s: %s\n", to, code)
		return err
	}
	m.Log.Warn("no mailer configured; sign-in code not delivered")
	return nil
}

// SMTPMailer sends codes with net/smtp (STARTTLS when the server offers it).
type SMTPMailer struct {
	Host, Port, User, Pass, From string
}

func (m SMTPMailer) SendCode(_ context.Context, to, code string) error {
	var auth smtp.Auth
	if m.User != "" {
		auth = smtp.PlainAuth("", m.User, m.Pass, m.Host)
	}
	msg := "From: " + m.From + "\r\nTo: " + to + "\r\nSubject: Your " + brandName + " sign-in code\r\n" +
		"Content-Type: text/plain; charset=utf-8\r\n\r\n" +
		"Your " + brandName + " sign-in code is " + code + ". It expires in 10 minutes.\r\n" +
		"If you did not ask for it, ignore this email.\r\n"
	return smtp.SendMail(net.JoinHostPort(m.Host, m.Port), auth, m.From, []string{to}, []byte(msg))
}

// ---- rate limiting ----

// rateLimiter is an in-memory token bucket per key.
type rateLimiter struct {
	mu      sync.Mutex
	perSec  float64
	burst   float64
	buckets map[string]*bucket
	now     func() time.Time
}

type bucket struct {
	tokens float64
	last   time.Time
}

func newRateLimiter(every time.Duration, burst int, now func() time.Time) *rateLimiter {
	return &rateLimiter{perSec: 1 / every.Seconds(), burst: float64(burst), buckets: map[string]*bucket{}, now: now}
}

// allow takes a token for key, or reports how long until one is available.
func (l *rateLimiter) allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b, ok := l.buckets[key]
	if !ok {
		if len(l.buckets) > 50_000 {
			l.pruneLocked(now)
		}
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[key] = b
	}
	b.tokens = math.Min(l.burst, b.tokens+now.Sub(b.last).Seconds()*l.perSec)
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	return false, time.Duration((1 - b.tokens) / l.perSec * float64(time.Second))
}

func (l *rateLimiter) pruneLocked(now time.Time) {
	for k, b := range l.buckets {
		if b.tokens+now.Sub(b.last).Seconds()*l.perSec >= l.burst {
			delete(l.buckets, k)
		}
	}
}

func (s *Server) clientIP(r *http.Request) string {
	if s.trustProxy {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			return strings.TrimSpace(strings.Split(xff, ",")[0])
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func tooMany(w http.ResponseWriter, wait time.Duration) {
	w.Header().Set("Retry-After", strconv.Itoa(int(math.Ceil(wait.Seconds()))))
	writeErr(w, http.StatusTooManyRequests, "too many requests, try again later")
}

// ---- handlers ----

type startAuthReq struct {
	Email string `json:"email"`
}

type verifyAuthReq struct {
	Email      string `json:"email"`
	Code       string `json:"code"`
	DeviceName string `json:"deviceName"`
	Platform   string `json:"platform"`
	PublicKey  string `json:"publicKey"`
}

type sessionResp struct {
	AccountID string `json:"accountId"`
	DeviceID  string `json:"deviceId"`
	Token     string `json:"token"`
	Salt      string `json:"salt"`
	Region    string `json:"region"`
}

func (s *Server) authStart(w http.ResponseWriter, r *http.Request) {
	var req startAuthReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	email := normalizeEmail(req.Email)
	if !validEmail(email) {
		writeErr(w, http.StatusBadRequest, "invalid email")
		return
	}
	if ok, wait := s.startByIP.allow(s.clientIP(r)); !ok {
		tooMany(w, wait)
		return
	}
	eh := s.emailHash(email)
	if ok, wait := s.startByEmail.allow(eh); !ok {
		tooMany(w, wait)
		return
	}
	code := randomCode()
	if err := s.store.PutAuthCode(r.Context(), AuthCode{EmailHash: eh, CodeHash: codeHash(eh, code), ExpiresAt: s.now().Add(codeTTL)}); err != nil {
		s.fail(w, r, err)
		return
	}
	if err := s.mailer.SendCode(r.Context(), email, code); err != nil {
		s.log.Error("send code failed", "email", eh[:12], "err", err)
		writeErr(w, http.StatusBadGateway, "could not send the code")
		return
	}
	s.log.Info("auth code issued", "email", eh[:12])
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) authVerify(w http.ResponseWriter, r *http.Request) {
	var req verifyAuthReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	email := normalizeEmail(req.Email)
	code := strings.TrimSpace(req.Code)
	switch {
	case !validEmail(email):
		writeErr(w, http.StatusBadRequest, "invalid email")
		return
	case len(code) != 6 || strings.Trim(code, "0123456789") != "":
		writeErr(w, http.StatusBadRequest, "code must be 6 digits")
		return
	case len(req.DeviceName) > 100 || len(req.Platform) > 32 || len(req.PublicKey) > 2048:
		writeErr(w, http.StatusBadRequest, "device fields too long")
		return
	}
	if ok, wait := s.verifyByIP.allow(s.clientIP(r)); !ok {
		tooMany(w, wait)
		return
	}
	ctx := r.Context()
	eh := s.emailHash(email)
	ac, err := s.store.UseAuthAttempt(ctx, eh)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusUnauthorized, "invalid or expired code")
		return
	} else if err != nil {
		s.fail(w, r, err)
		return
	}
	now := s.now()
	switch {
	case !now.Before(ac.ExpiresAt):
		_ = s.store.DeleteAuthCode(ctx, eh)
		writeErr(w, http.StatusUnauthorized, "code expired, request a new one")
		return
	case ac.Attempts > maxCodeAttempts:
		_ = s.store.DeleteAuthCode(ctx, eh)
		writeErr(w, http.StatusTooManyRequests, "too many attempts, request a new code")
		return
	case subtle.ConstantTimeCompare([]byte(ac.CodeHash), []byte(codeHash(eh, code))) != 1:
		if ac.Attempts >= maxCodeAttempts {
			_ = s.store.DeleteAuthCode(ctx, eh)
		}
		s.log.Info("auth code mismatch", "email", eh[:12], "attempt", ac.Attempts)
		writeErr(w, http.StatusUnauthorized, "invalid code")
		return
	}
	if err := s.store.DeleteAuthCode(ctx, eh); err != nil {
		s.fail(w, r, err)
		return
	}
	acct, err := s.store.UpsertAccount(ctx, Account{ID: randomB64(16), EmailHash: eh, Salt: randomB64(16), CreatedAt: now})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	name := strings.TrimSpace(req.DeviceName)
	if name == "" {
		name = "Device"
	}
	dev := Device{ID: randomB64(16), AccountID: acct.ID, Name: name, Platform: req.Platform, PublicKey: req.PublicKey, CreatedAt: now, LastSeen: now}
	token := randomB64(32)
	if err := s.store.CreateDevice(ctx, dev, sha256Hex(token)); err != nil {
		s.fail(w, r, err)
		return
	}
	s.log.Info("device signed in", "email", eh[:12])
	writeJSON(w, http.StatusOK, sessionResp{AccountID: acct.ID, DeviceID: dev.ID, Token: token, Salt: acct.Salt, Region: s.region})
}

// caller is the authenticated account and device of a request.
type caller struct {
	AccountID string
	DeviceID  string
	PublicKey string
}

type authedHandler func(w http.ResponseWriter, r *http.Request, c caller)

func (s *Server) authed(h authedHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		tok = strings.TrimSpace(tok)
		if !ok || tok == "" {
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeErr(w, http.StatusUnauthorized, "missing bearer token")
			return
		}
		d, err := s.store.DeviceByToken(r.Context(), sha256Hex(tok))
		if errors.Is(err, ErrNotFound) {
			w.Header().Set("WWW-Authenticate", `Bearer error="invalid_token"`)
			writeErr(w, http.StatusUnauthorized, "invalid or revoked token")
			return
		} else if err != nil {
			s.fail(w, r, err)
			return
		}
		if now := s.now(); now.Sub(d.LastSeen) >= touchInterval {
			if err := s.store.TouchDevice(r.Context(), d.ID, now); err != nil {
				s.log.Warn("touch device failed", "err", err)
			}
		}
		h(w, r, caller{AccountID: d.AccountID, DeviceID: d.ID, PublicKey: d.PublicKey})
	}
}
