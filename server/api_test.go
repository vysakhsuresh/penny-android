package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---- harness ----

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) Now() time.Time          { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *fakeClock) Advance(d time.Duration) { c.mu.Lock(); c.t = c.t.Add(d); c.mu.Unlock() }

type captureMailer struct {
	mu    sync.Mutex
	codes map[string]string
}

func (m *captureMailer) SendCode(_ context.Context, to, code string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.codes[to] = code
	return nil
}

func (m *captureMailer) code(email string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.codes[email]
}

type testEnv struct {
	t     *testing.T
	h     http.Handler
	clock *fakeClock
	mail  *captureMailer
	store *MemoryStore
	ipSeq int
}

func newEnv(t *testing.T) *testEnv {
	t.Helper()
	clock := &fakeClock{t: time.Date(2026, 3, 29, 1, 30, 0, 0, time.UTC)}
	mail := &captureMailer{codes: map[string]string{}}
	st := NewMemoryStore()
	srv := NewServer(Options{Store: st, Mailer: mail, Region: "eu", Pepper: []byte("test-pepper"), Now: clock.Now})
	return &testEnv{t: t, h: srv.Handler(), clock: clock, mail: mail, store: st}
}

type resp struct {
	status int
	body   []byte
	header http.Header
}

func (r resp) decode(t *testing.T, v any) {
	t.Helper()
	if err := json.Unmarshal(r.body, v); err != nil {
		t.Fatalf("decode %q: %v", r.body, err)
	}
}

func (e *testEnv) req(method, path, token string, body any, ip string) resp {
	e.t.Helper()
	var rd *bytes.Reader
	switch b := body.(type) {
	case nil:
		rd = bytes.NewReader(nil)
	case []byte:
		rd = bytes.NewReader(b)
	default:
		raw, err := json.Marshal(b)
		if err != nil {
			e.t.Fatal(err)
		}
		rd = bytes.NewReader(raw)
	}
	r := httptest.NewRequest(method, path, rd)
	if ip != "" {
		r.RemoteAddr = ip + ":5555"
	}
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, r)
	return resp{status: w.Code, body: w.Body.Bytes(), header: w.Header()}
}

func (e *testEnv) do(method, path, token string, body any) resp {
	e.t.Helper()
	return e.req(method, path, token, body, "")
}

func expect(t *testing.T, r resp, status int) {
	t.Helper()
	if r.status != status {
		t.Fatalf("status = %d, want %d; body %s", r.status, status, r.body)
	}
}

// signIn runs the email-code flow from a unique IP so tests don't trip the rate limiter.
func (e *testEnv) signIn(email string) sessionResp {
	e.t.Helper()
	e.ipSeq++
	ip := fmt.Sprintf("10.0.%d.%d", e.ipSeq/250, e.ipSeq%250+1)
	expect(e.t, e.req("POST", "/v1/auth/start", "", startAuthReq{Email: email}, ip), http.StatusNoContent)
	r := e.req("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: e.mail.code(normalizeEmail(email)),
		DeviceName: "Pixel", Platform: "android", PublicKey: "pk-" + email}, ip)
	expect(e.t, r, http.StatusOK)
	var s sessionResp
	r.decode(e.t, &s)
	return s
}

func blob(s string) string { return base64.RawURLEncoding.EncodeToString([]byte(s)) }

func (e *testEnv) push(tok, stream string, blobs ...string) resp {
	e.t.Helper()
	enc := make([]string, len(blobs))
	for i, b := range blobs {
		enc[i] = blob(b)
	}
	return e.do("POST", "/v1/streams/"+stream+"/events", tok, pushReq{Blobs: enc})
}

func (e *testEnv) pull(tok, stream, query string) ([]pulledBlob, resp) {
	e.t.Helper()
	r := e.do("GET", "/v1/streams/"+stream+"/events"+query, tok, nil)
	var out []pulledBlob
	if r.status == http.StatusOK {
		r.decode(e.t, &out)
	}
	return out, r
}

func (e *testEnv) invite(tok, space, role string, days int) inviteInfo {
	e.t.Helper()
	r := e.do("POST", "/v1/spaces/"+space+"/invites", tok, inviteCreateReq{Role: role, ExpiresInDays: days, Summary: "Household budget"})
	expect(e.t, r, http.StatusCreated)
	var inv inviteInfo
	r.decode(e.t, &inv)
	return inv
}

// join creates an invite with role as owner tok and accepts it as s.
func (e *testEnv) join(ownerTok, space, role string, s sessionResp) {
	e.t.Helper()
	inv := e.invite(ownerTok, space, role, 7)
	expect(e.t, e.do("POST", "/v1/invites/"+inv.Token+"/accept", s.Token, inviteAcceptReq{PublicKey: "pk-" + s.AccountID}), http.StatusOK)
}

func (e *testEnv) members(tok, space string) map[string]memberInfo {
	e.t.Helper()
	r := e.do("GET", "/v1/spaces/"+space+"/members", tok, nil)
	expect(e.t, r, http.StatusOK)
	var ms []memberInfo
	r.decode(e.t, &ms)
	out := map[string]memberInfo{}
	for _, m := range ms {
		out[m.AccountID] = m
	}
	return out
}

func (e *testEnv) space(owner sessionResp, id string) {
	e.t.Helper()
	expect(e.t, e.do("POST", "/v1/spaces", owner.Token, spaceCreateReq{SpaceID: id}), http.StatusCreated)
}

// ---- auth ----

func TestAuthFlow(t *testing.T) {
	const email = "Ana@Example.com"
	tests := []struct {
		name   string
		run    func(e *testEnv) resp
		status int
	}{
		{"valid code", func(e *testEnv) resp {
			e.do("POST", "/v1/auth/start", "", startAuthReq{Email: email})
			return e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: e.mail.code("ana@example.com"), DeviceName: "Pixel"})
		}, http.StatusOK},
		{"wrong code", func(e *testEnv) resp {
			e.do("POST", "/v1/auth/start", "", startAuthReq{Email: email})
			return e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: wrong(e.mail.code("ana@example.com"))})
		}, http.StatusUnauthorized},
		{"five wrong attempts burn the code", func(e *testEnv) resp {
			e.do("POST", "/v1/auth/start", "", startAuthReq{Email: email})
			good := e.mail.code("ana@example.com")
			for i := 0; i < maxCodeAttempts; i++ {
				expect(e.t, e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: wrong(good)}), http.StatusUnauthorized)
			}
			return e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: good})
		}, http.StatusUnauthorized},
		{"four wrong then right", func(e *testEnv) resp {
			e.do("POST", "/v1/auth/start", "", startAuthReq{Email: email})
			good := e.mail.code("ana@example.com")
			for i := 0; i < maxCodeAttempts-1; i++ {
				e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: wrong(good)})
			}
			return e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: good})
		}, http.StatusOK},
		{"expired code", func(e *testEnv) resp {
			e.do("POST", "/v1/auth/start", "", startAuthReq{Email: email})
			e.clock.Advance(codeTTL + time.Second)
			return e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: e.mail.code("ana@example.com")})
		}, http.StatusUnauthorized},
		{"code is single use", func(e *testEnv) resp {
			e.do("POST", "/v1/auth/start", "", startAuthReq{Email: email})
			c := e.mail.code("ana@example.com")
			expect(e.t, e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: c}), http.StatusOK)
			return e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: c})
		}, http.StatusUnauthorized},
		{"no code requested", func(e *testEnv) resp {
			return e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: "123456"})
		}, http.StatusUnauthorized},
		{"bad email", func(e *testEnv) resp {
			return e.do("POST", "/v1/auth/start", "", startAuthReq{Email: "nope"})
		}, http.StatusBadRequest},
		{"non-numeric code", func(e *testEnv) resp {
			return e.do("POST", "/v1/auth/verify", "", verifyAuthReq{Email: email, Code: "12ab56"})
		}, http.StatusBadRequest},
		{"malformed json", func(e *testEnv) resp {
			return e.do("POST", "/v1/auth/start", "", []byte("{"))
		}, http.StatusBadRequest},
		{"no bearer", func(e *testEnv) resp { return e.do("GET", "/v1/keys", "", nil) }, http.StatusUnauthorized},
		{"bad bearer", func(e *testEnv) resp { return e.do("GET", "/v1/keys", "garbage", nil) }, http.StatusUnauthorized},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			e := newEnv(t)
			r := tc.run(e)
			expect(t, r, tc.status)
			if r.status >= 400 {
				var eb errorBody
				r.decode(t, &eb)
				if eb.Error == "" {
					t.Fatalf("error body missing message: %s", r.body)
				}
			}
		})
	}
}

func wrong(code string) string {
	if code == "000000" {
		return "000001"
	}
	return "000000"
}

func TestSessionAndAccountReuse(t *testing.T) {
	e := newEnv(t)
	a := e.signIn("bo@example.com")
	b := e.signIn("BO@example.com ")
	if a.AccountID == "" || a.Token == "" || a.Region != "eu" {
		t.Fatalf("bad session %+v", a)
	}
	if a.AccountID != b.AccountID || a.Salt != b.Salt {
		t.Fatalf("second sign-in should reuse the account and salt: %+v vs %+v", a, b)
	}
	if a.DeviceID == b.DeviceID || a.Token == b.Token {
		t.Fatal("each sign-in should create a new device and token")
	}
	if salt, err := decodeB64(a.Salt); err != nil || len(salt) != 16 {
		t.Fatalf("salt should be 16 bytes base64url, got %q", a.Salt)
	}
	for _, acct := range e.store.accounts {
		if strings.Contains(acct.EmailHash, "bo@") {
			t.Fatal("email stored in clear")
		}
	}
}

func TestRateLimit(t *testing.T) {
	t.Run("per email", func(t *testing.T) {
		e := newEnv(t)
		for i := 0; i < 3; i++ {
			expect(t, e.req("POST", "/v1/auth/start", "", startAuthReq{Email: "c@example.com"}, fmt.Sprintf("10.1.0.%d", i+1)), http.StatusNoContent)
		}
		r := e.req("POST", "/v1/auth/start", "", startAuthReq{Email: "c@example.com"}, "10.1.0.9")
		expect(t, r, http.StatusTooManyRequests)
		if r.header.Get("Retry-After") == "" {
			t.Fatal("missing Retry-After")
		}
		e.clock.Advance(2 * time.Minute)
		expect(t, e.req("POST", "/v1/auth/start", "", startAuthReq{Email: "c@example.com"}, "10.1.0.9"), http.StatusNoContent)
	})
	t.Run("per ip", func(t *testing.T) {
		e := newEnv(t)
		for i := 0; i < 20; i++ {
			expect(t, e.req("POST", "/v1/auth/start", "", startAuthReq{Email: fmt.Sprintf("u%d@example.com", i)}, "10.2.0.1"), http.StatusNoContent)
		}
		expect(t, e.req("POST", "/v1/auth/start", "", startAuthReq{Email: "late@example.com"}, "10.2.0.1"), http.StatusTooManyRequests)
		expect(t, e.req("POST", "/v1/auth/start", "", startAuthReq{Email: "late@example.com"}, "10.2.0.2"), http.StatusNoContent)
	})
}

// ---- streams ----

func TestPushPull(t *testing.T) {
	e := newEnv(t)
	a := e.signIn("a@example.com")
	b := e.signIn("b@example.com")

	var pr pushResp
	r := e.push(a.Token, "profile-a", "e1", "e2", "e3")
	expect(t, r, http.StatusOK)
	r.decode(t, &pr)
	if pr.Cursor != 3 {
		t.Fatalf("cursor = %d, want 3", pr.Cursor)
	}
	e.push(a.Token, "profile-a", "e4", "e5").decode(t, &pr)
	if pr.Cursor != 5 {
		t.Fatalf("cursor = %d, want 5", pr.Cursor)
	}
	// Padded base64url is accepted too.
	padded := base64.URLEncoding.EncodeToString([]byte("e6"))
	expect(t, e.do("POST", "/v1/streams/profile-a/events", a.Token, pushReq{Blobs: []string{padded}}), http.StatusOK)

	var got []string
	after := int64(0)
	for {
		page, r := e.pull(a.Token, "profile-a", fmt.Sprintf("?after=%d&limit=2", after))
		expect(t, r, http.StatusOK)
		if len(page) == 0 {
			break
		}
		if len(page) > 2 {
			t.Fatalf("page size %d > limit", len(page))
		}
		for _, p := range page {
			if p.Cursor != after+1 {
				t.Fatalf("cursor %d after %d: not contiguous", p.Cursor, after)
			}
			if p.Device != a.DeviceID {
				t.Fatalf("device = %q", p.Device)
			}
			raw, _ := decodeB64(p.Blob)
			got = append(got, string(raw))
			after = p.Cursor
		}
	}
	if strings.Join(got, ",") != "e1,e2,e3,e4,e5,e6" {
		t.Fatalf("pulled %v", got)
	}

	tooMany := make([]string, maxPushBlobs+1)
	for i := range tooMany {
		tooMany[i] = blob("x")
	}
	huge := blob(strings.Repeat("x", maxPushBody))

	cases := []struct {
		name   string
		r      resp
		status int
	}{
		{"other account cannot pull", e.do("GET", "/v1/streams/profile-a/events", b.Token, nil), http.StatusForbidden},
		{"other account cannot push", e.push(b.Token, "profile-a", "evil"), http.StatusForbidden},
		{"no token", e.do("GET", "/v1/streams/profile-a/events", "", nil), http.StatusUnauthorized},
		{"empty blobs", e.do("POST", "/v1/streams/p/events", a.Token, pushReq{}), http.StatusBadRequest},
		{"invalid base64", e.do("POST", "/v1/streams/p/events", a.Token, pushReq{Blobs: []string{"*&^"}}), http.StatusBadRequest},
		{"too many blobs", e.do("POST", "/v1/streams/p/events", a.Token, pushReq{Blobs: tooMany}), http.StatusBadRequest},
		{"body over 2 MB", e.do("POST", "/v1/streams/p/events", a.Token, pushReq{Blobs: []string{huge}}), http.StatusRequestEntityTooLarge},
		{"bad stream id", e.do("GET", "/v1/streams/bad%20id/events", a.Token, nil), http.StatusBadRequest},
		{"bad after", e.do("GET", "/v1/streams/profile-a/events?after=-1", a.Token, nil), http.StatusBadRequest},
		{"unknown stream pulls empty", e.do("GET", "/v1/streams/never-pushed/events", a.Token, nil), http.StatusOK},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) { expect(t, c.r, c.status) })
	}

	// Limit is clamped to 500.
	many := make([]string, 600)
	for i := range many {
		many[i] = blob("m")
	}
	expect(t, e.do("POST", "/v1/streams/big/events", a.Token, pushReq{Blobs: many}), http.StatusOK)
	page, _ := e.pull(a.Token, "big", "?limit=9999")
	if len(page) != maxPull {
		t.Fatalf("limit not clamped: %d", len(page))
	}
	page, _ = e.pull(a.Token, "big", "")
	if len(page) != defaultPull {
		t.Fatalf("default limit: %d", len(page))
	}
}

func TestSpaceStreamAccess(t *testing.T) {
	e := newEnv(t)
	owner := e.signIn("owner@example.com")
	viewer := e.signIn("viewer@example.com")
	editor := e.signIn("editor@example.com")
	stranger := e.signIn("stranger@example.com")
	e.space(owner, "home")
	e.join(owner.Token, "home", RoleViewer, viewer)
	e.join(owner.Token, "home", RoleEditor, editor)

	expect(t, e.push(owner.Token, "home", "o1"), http.StatusOK)
	expect(t, e.push(editor.Token, "home", "e1"), http.StatusOK)

	tests := []struct {
		name   string
		r      resp
		status int
	}{
		{"viewer pulls", e.do("GET", "/v1/streams/home/events", viewer.Token, nil), http.StatusOK},
		{"viewer cannot push", e.push(viewer.Token, "home", "v1"), http.StatusForbidden},
		{"non-member cannot pull", e.do("GET", "/v1/streams/home/events", stranger.Token, nil), http.StatusForbidden},
		{"non-member cannot push", e.push(stranger.Token, "home", "s1"), http.StatusForbidden},
		{"non-member cannot list members", e.do("GET", "/v1/spaces/home/members", stranger.Token, nil), http.StatusForbidden},
		{"space id already used", e.do("POST", "/v1/spaces", stranger.Token, spaceCreateReq{SpaceID: "home"}), http.StatusConflict},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) { expect(t, tc.r, tc.status) })
	}

	// Role NONE keeps the row but grants no access.
	expect(t, e.do("PUT", "/v1/spaces/home/members/"+editor.AccountID, owner.Token, roleChangeReq{Role: RoleNone}), http.StatusNoContent)
	_, r := e.pull(editor.Token, "home", "")
	expect(t, r, http.StatusForbidden)
}

// ---- keys ----

func TestKeys(t *testing.T) {
	e := newEnv(t)
	owner := e.signIn("owner@example.com")
	admin := e.signIn("admin@example.com")
	editor := e.signIn("editor@example.com")
	stranger := e.signIn("stranger@example.com")
	e.space(owner, "home")
	e.join(owner.Token, "home", RoleAdmin, admin)
	e.join(owner.Token, "home", RoleEditor, editor)

	key := func(stream, acct, tok string) resp {
		return e.do("PUT", "/v1/keys/"+stream+"/"+acct, tok, streamKeyJSON{Stream: stream, AccountID: acct, Wrapped: "wrapped-" + acct, Kind: "x25519"})
	}
	tests := []struct {
		name   string
		r      resp
		status int
	}{
		{"own personal stream (creates it)", key("profile-o", owner.AccountID, owner.Token), http.StatusNoContent},
		{"stranger cannot key someone's personal stream", key("profile-o", stranger.AccountID, stranger.Token), http.StatusForbidden},
		{"owner wraps for editor", key("home", editor.AccountID, owner.Token), http.StatusNoContent},
		{"admin wraps for owner", key("home", owner.AccountID, admin.Token), http.StatusNoContent},
		{"member stores own key", key("home", admin.AccountID, admin.Token), http.StatusNoContent},
		{"editor cannot wrap for others", key("home", admin.AccountID, editor.Token), http.StatusForbidden},
		{"non-member target", key("home", stranger.AccountID, owner.Token), http.StatusNotFound},
		{"body mismatch", e.do("PUT", "/v1/keys/home/"+editor.AccountID, owner.Token, streamKeyJSON{Stream: "other", Wrapped: "w"}), http.StatusBadRequest},
		{"missing wrapped", e.do("PUT", "/v1/keys/home/"+editor.AccountID, owner.Token, streamKeyJSON{}), http.StatusBadRequest},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) { expect(t, tc.r, tc.status) })
	}

	var keys []streamKeyJSON
	e.do("GET", "/v1/keys", owner.Token, nil).decode(t, &keys)
	if len(keys) != 2 || keys[0].Stream != "home" || keys[1].Stream != "profile-o" || keys[0].Wrapped != "wrapped-"+owner.AccountID {
		t.Fatalf("owner keys = %+v", keys)
	}
	// Leaving a space drops that member's wrapped key.
	expect(t, e.do("DELETE", "/v1/spaces/home/members/"+editor.AccountID, editor.Token, nil), http.StatusNoContent)
	e.do("GET", "/v1/keys", editor.Token, nil).decode(t, &keys)
	if len(keys) != 0 {
		t.Fatalf("left member still has keys: %+v", keys)
	}
}

// ---- invites ----

func TestInvites(t *testing.T) {
	e := newEnv(t)
	owner := e.signIn("owner@example.com")
	editor := e.signIn("editor@example.com")
	guest := e.signIn("guest@example.com")
	e.space(owner, "home")
	e.join(owner.Token, "home", RoleEditor, editor)

	t.Run("create rules", func(t *testing.T) {
		cases := []struct {
			name   string
			tok    string
			body   inviteCreateReq
			status int
		}{
			{"editor cannot invite", editor.Token, inviteCreateReq{Role: RoleViewer}, http.StatusForbidden},
			{"non-member cannot invite", guest.Token, inviteCreateReq{Role: RoleViewer}, http.StatusForbidden},
			{"cannot invite owner", owner.Token, inviteCreateReq{Role: RoleOwner}, http.StatusBadRequest},
			{"too long", owner.Token, inviteCreateReq{Role: RoleViewer, ExpiresInDays: 31}, http.StatusBadRequest},
			{"default expiry", owner.Token, inviteCreateReq{Role: RoleViewer}, http.StatusCreated},
		}
		for _, c := range cases {
			t.Run(c.name, func(t *testing.T) {
				expect(t, e.do("POST", "/v1/spaces/home/invites", c.tok, c.body), c.status)
			})
		}
	})

	t.Run("get, accept, single use", func(t *testing.T) {
		inv := e.invite(owner.Token, "home", RoleContributor, 0)
		if inv.ExpiresAt != e.clock.Now().Add(7*24*time.Hour).UnixMilli() || inv.InviterPublicKey != "pk-owner@example.com" || len(inv.Token) != 32 {
			t.Fatalf("invite = %+v", inv)
		}
		var shown inviteInfo
		r := e.do("GET", "/v1/invites/"+inv.Token, "", nil) // no auth needed
		expect(t, r, http.StatusOK)
		r.decode(t, &shown)
		if shown != inv {
			t.Fatalf("shown %+v != created %+v", shown, inv)
		}
		expect(t, e.do("POST", "/v1/invites/"+inv.Token+"/accept", "", inviteAcceptReq{}), http.StatusUnauthorized)
		expect(t, e.do("POST", "/v1/invites/"+inv.Token+"/accept", guest.Token, inviteAcceptReq{PublicKey: "gk"}), http.StatusOK)
		m := e.members(owner.Token, "home")[guest.AccountID]
		if m.Role != RoleContributor || m.PublicKey != "gk" || m.Left {
			t.Fatalf("member = %+v", m)
		}
		expect(t, e.do("POST", "/v1/invites/"+inv.Token+"/accept", editor.Token, inviteAcceptReq{}), http.StatusGone)
		expect(t, e.do("GET", "/v1/invites/"+inv.Token, "", nil), http.StatusGone)
	})

	t.Run("already a member", func(t *testing.T) {
		inv := e.invite(owner.Token, "home", RoleViewer, 1)
		expect(t, e.do("POST", "/v1/invites/"+inv.Token+"/accept", editor.Token, inviteAcceptReq{}), http.StatusConflict)
	})

	t.Run("expired", func(t *testing.T) {
		inv := e.invite(owner.Token, "home", RoleViewer, 1)
		e.clock.Advance(24*time.Hour + time.Second)
		expect(t, e.do("GET", "/v1/invites/"+inv.Token, "", nil), http.StatusGone)
		x := e.signIn("late@example.com")
		expect(t, e.do("POST", "/v1/invites/"+inv.Token+"/accept", x.Token, inviteAcceptReq{}), http.StatusGone)
	})

	t.Run("revoked", func(t *testing.T) {
		inv := e.invite(owner.Token, "home", RoleViewer, 3)
		expect(t, e.do("DELETE", "/v1/invites/"+inv.Token, editor.Token, nil), http.StatusForbidden)
		expect(t, e.do("DELETE", "/v1/invites/"+inv.Token, owner.Token, nil), http.StatusNoContent)
		expect(t, e.do("GET", "/v1/invites/"+inv.Token, "", nil), http.StatusGone)
		x := e.signIn("revoked@example.com")
		expect(t, e.do("POST", "/v1/invites/"+inv.Token+"/accept", x.Token, inviteAcceptReq{}), http.StatusGone)
	})

	t.Run("unknown token", func(t *testing.T) {
		expect(t, e.do("GET", "/v1/invites/nope", "", nil), http.StatusNotFound)
	})

	t.Run("member limit", func(t *testing.T) {
		e.space(owner, "big")
		for i := 1; i < maxSpaceMembers; i++ {
			e.join(owner.Token, "big", RoleViewer, e.signIn(fmt.Sprintf("m%d@example.com", i)))
		}
		inv := e.invite(owner.Token, "big", RoleViewer, 7)
		extra := e.signIn("eleventh@example.com")
		r := e.do("POST", "/v1/invites/"+inv.Token+"/accept", extra.Token, inviteAcceptReq{})
		expect(t, r, http.StatusConflict)
		// The invite was not consumed and works once a seat frees up.
		m1 := e.signIn("m1@example.com")
		expect(t, e.do("DELETE", "/v1/spaces/big/members/"+m1.AccountID, m1.Token, nil), http.StatusNoContent)
		expect(t, e.do("POST", "/v1/invites/"+inv.Token+"/accept", extra.Token, inviteAcceptReq{}), http.StatusOK)
	})
}

// ---- members ----

func TestRoleChanges(t *testing.T) {
	e := newEnv(t)
	owner := e.signIn("owner@example.com")
	admin := e.signIn("admin@example.com")
	editor := e.signIn("editor@example.com")
	e.space(owner, "home")
	e.join(owner.Token, "home", RoleAdmin, admin)
	e.join(owner.Token, "home", RoleEditor, editor)

	set := func(tok, acct, role string) resp {
		return e.do("PUT", "/v1/spaces/home/members/"+acct, tok, roleChangeReq{Role: role})
	}
	steps := []struct {
		name   string
		r      func() resp
		status int
	}{
		{"editor cannot change roles", func() resp { return set(editor.Token, admin.AccountID, RoleViewer) }, http.StatusForbidden},
		{"admin promotes editor to admin", func() resp { return set(admin.Token, editor.AccountID, RoleAdmin) }, http.StatusNoContent},
		{"admin demotes back", func() resp { return set(admin.Token, editor.AccountID, RoleEditor) }, http.StatusNoContent},
		{"admin cannot grant owner", func() resp { return set(admin.Token, editor.AccountID, RoleOwner) }, http.StatusForbidden},
		{"admin cannot demote owner", func() resp { return set(admin.Token, owner.AccountID, RoleAdmin) }, http.StatusForbidden},
		{"cannot demote the last owner", func() resp { return set(owner.Token, owner.AccountID, RoleAdmin) }, http.StatusConflict},
		{"unknown role", func() resp { return set(owner.Token, editor.AccountID, "GOD") }, http.StatusBadRequest},
		{"unknown member", func() resp { return set(owner.Token, "nobody", RoleViewer) }, http.StatusNotFound},
		{"owner transfers ownership", func() resp { return set(owner.Token, admin.AccountID, RoleOwner) }, http.StatusNoContent},
		{"former sole owner can step down", func() resp { return set(owner.Token, owner.AccountID, RoleAdmin) }, http.StatusNoContent},
		{"new sole owner cannot step down", func() resp { return set(admin.Token, admin.AccountID, RoleEditor) }, http.StatusConflict},
	}
	for _, s := range steps {
		t.Run(s.name, func(t *testing.T) { expect(t, s.r(), s.status) })
	}
	ms := e.members(editor.Token, "home")
	if ms[admin.AccountID].Role != RoleOwner || ms[owner.AccountID].Role != RoleAdmin || ms[editor.AccountID].Role != RoleEditor {
		t.Fatalf("final roles %+v", ms)
	}
}

func TestLeaveAndRemove(t *testing.T) {
	e := newEnv(t)
	owner := e.signIn("owner@example.com")
	admin := e.signIn("admin@example.com")
	editor := e.signIn("editor@example.com")
	viewer := e.signIn("viewer@example.com")
	e.space(owner, "home")
	e.join(owner.Token, "home", RoleAdmin, admin)
	e.join(owner.Token, "home", RoleEditor, editor)
	e.join(owner.Token, "home", RoleViewer, viewer)
	expect(t, e.push(editor.Token, "home", "kept"), http.StatusOK)

	del := func(tok, acct string) resp { return e.do("DELETE", "/v1/spaces/home/members/"+acct, tok, nil) }
	steps := []struct {
		name   string
		r      func() resp
		status int
	}{
		{"viewer cannot remove others", func() resp { return del(viewer.Token, editor.AccountID) }, http.StatusForbidden},
		{"admin cannot remove owner", func() resp { return del(admin.Token, owner.AccountID) }, http.StatusForbidden},
		{"last owner cannot leave while others remain", func() resp { return del(owner.Token, owner.AccountID) }, http.StatusConflict},
		{"admin removes editor", func() resp { return del(admin.Token, editor.AccountID) }, http.StatusNoContent},
		{"removed twice", func() resp { return del(admin.Token, editor.AccountID) }, http.StatusNotFound},
		{"viewer leaves", func() resp { return del(viewer.Token, viewer.AccountID) }, http.StatusNoContent},
	}
	for _, s := range steps {
		t.Run(s.name, func(t *testing.T) { expect(t, s.r(), s.status) })
	}

	ms := e.members(owner.Token, "home")
	if !ms[editor.AccountID].Left || !ms[viewer.AccountID].Left || ms[admin.AccountID].Left {
		t.Fatalf("left flags wrong: %+v", ms)
	}
	_, r := e.pull(editor.Token, "home", "")
	expect(t, r, http.StatusForbidden)
	// History pushed by a removed member stays.
	evs, _ := e.pull(owner.Token, "home", "")
	if len(evs) != 1 || evs[0].Device != editor.DeviceID {
		t.Fatalf("history lost: %+v", evs)
	}
	// A removed member can be invited back.
	e.join(owner.Token, "home", RoleViewer, editor)
	if m := e.members(owner.Token, "home")[editor.AccountID]; m.Left || m.Role != RoleViewer {
		t.Fatalf("rejoined member %+v", m)
	}
}

// ---- devices ----

func TestDeviceSignOut(t *testing.T) {
	e := newEnv(t)
	phone := e.signIn("d@example.com")
	tablet := e.signIn("d@example.com")
	other := e.signIn("other@example.com")

	e.clock.Advance(5 * time.Minute)
	expect(t, e.do("GET", "/v1/keys", tablet.Token, nil), http.StatusOK) // bumps lastSeen

	var ds []deviceInfo
	e.do("GET", "/v1/devices", phone.Token, nil).decode(t, &ds)
	if len(ds) != 2 {
		t.Fatalf("devices = %+v", ds)
	}
	for _, d := range ds {
		if d.Current != (d.DeviceID == phone.DeviceID) {
			t.Fatalf("current flag wrong: %+v", d)
		}
		if d.DeviceID == tablet.DeviceID && d.LastSeen != e.clock.Now().UnixMilli() {
			t.Fatalf("lastSeen not updated: %+v", d)
		}
	}

	expect(t, e.do("DELETE", "/v1/devices/"+tablet.DeviceID, other.Token, nil), http.StatusNotFound)
	expect(t, e.do("DELETE", "/v1/devices/"+tablet.DeviceID, phone.Token, nil), http.StatusNoContent)
	expect(t, e.do("GET", "/v1/keys", tablet.Token, nil), http.StatusUnauthorized)
	expect(t, e.do("GET", "/v1/keys", phone.Token, nil), http.StatusOK)
	e.do("GET", "/v1/devices", phone.Token, nil).decode(t, &ds)
	if len(ds) != 1 || ds[0].DeviceID != phone.DeviceID {
		t.Fatalf("devices after revoke = %+v", ds)
	}
}

// ---- account deletion ----

func TestAccountDeletion(t *testing.T) {
	e := newEnv(t)
	a := e.signIn("a@example.com")
	editor := e.signIn("editor@example.com")
	admin := e.signIn("admin@example.com")
	viewer := e.signIn("viewer@example.com")

	expect(t, e.push(a.Token, "profile-a", "secret"), http.StatusOK)
	e.space(a, "shared")
	e.join(a.Token, "shared", RoleEditor, editor) // joined first, but an admin is preferred
	e.clock.Advance(time.Minute)
	e.join(a.Token, "shared", RoleAdmin, admin)
	e.space(a, "solo")
	e.space(viewer, "theirs")
	e.join(viewer.Token, "theirs", RoleEditor, a)
	expect(t, e.do("PUT", "/v1/advisor/adv-a", a.Token, advisorPutReq{Blob: blob(strings.Repeat("z", 40)), ExpiresAt: e.clock.Now().Add(time.Hour).UnixMilli()}), http.StatusNoContent)

	expect(t, e.do("DELETE", "/v1/account", a.Token, nil), http.StatusNoContent)
	expect(t, e.do("GET", "/v1/keys", a.Token, nil), http.StatusUnauthorized)

	ms := e.members(editor.Token, "shared")
	if ms[admin.AccountID].Role != RoleOwner || ms[editor.AccountID].Role != RoleEditor {
		t.Fatalf("ownership not transferred to earliest admin: %+v", ms)
	}
	if _, ok := ms[a.AccountID]; ok {
		t.Fatal("deleted account still listed")
	}
	if _, ok := e.members(viewer.Token, "theirs")[a.AccountID]; ok {
		t.Fatal("membership in other space not removed")
	}
	if _, err := e.store.GetStream(context.Background(), "solo"); err != ErrNotFound {
		t.Fatalf("solo space should be deleted, got %v", err)
	}
	if _, err := e.store.GetStream(context.Background(), "profile-a"); err != ErrNotFound {
		t.Fatalf("personal stream should be deleted, got %v", err)
	}
	expect(t, e.do("GET", "/v1/advisor/adv-a/blob", "", nil), http.StatusNotFound)

	// Signing in again starts a fresh account.
	again := e.signIn("a@example.com")
	if again.AccountID == a.AccountID {
		t.Fatal("account id reused after deletion")
	}
	expect(t, e.do("DELETE", "/v1/account", again.Token, nil), http.StatusNoContent)
}

func TestAccountDeletionFallsBackToEarliestMember(t *testing.T) {
	e := newEnv(t)
	a := e.signIn("a@example.com")
	first := e.signIn("first@example.com")
	second := e.signIn("second@example.com")
	e.space(a, "s")
	e.join(a.Token, "s", RoleViewer, first)
	e.clock.Advance(time.Minute)
	e.join(a.Token, "s", RoleEditor, second)
	expect(t, e.do("DELETE", "/v1/account", a.Token, nil), http.StatusNoContent)
	if m := e.members(first.Token, "s"); m[first.AccountID].Role != RoleOwner || m[second.AccountID].Role != RoleEditor {
		t.Fatalf("roles %+v", m)
	}
}

// ---- advisor links ----

func TestAdvisorLinks(t *testing.T) {
	e := newEnv(t)
	a := e.signIn("a@example.com")
	b := e.signIn("b@example.com")
	ct := blob(strings.Repeat("c", 64))
	exp := e.clock.Now().Add(48 * time.Hour).UnixMilli()

	cases := []struct {
		name   string
		r      resp
		status int
	}{
		{"put", e.do("PUT", "/v1/advisor/link1", a.Token, advisorPutReq{Blob: ct, ExpiresAt: exp}), http.StatusNoContent},
		{"update by owner", e.do("PUT", "/v1/advisor/link1", a.Token, advisorPutReq{Blob: ct, ExpiresAt: exp}), http.StatusNoContent},
		{"other account cannot overwrite", e.do("PUT", "/v1/advisor/link1", b.Token, advisorPutReq{Blob: ct, ExpiresAt: exp}), http.StatusForbidden},
		{"past expiry rejected", e.do("PUT", "/v1/advisor/link2", a.Token, advisorPutReq{Blob: ct, ExpiresAt: e.clock.Now().Add(-time.Second).UnixMilli()}), http.StatusBadRequest},
		{"too short blob", e.do("PUT", "/v1/advisor/link2", a.Token, advisorPutReq{Blob: blob("tiny"), ExpiresAt: exp}), http.StatusBadRequest},
		{"put needs auth", e.do("PUT", "/v1/advisor/link2", "", advisorPutReq{Blob: ct, ExpiresAt: exp}), http.StatusUnauthorized},
		{"other account cannot delete", e.do("DELETE", "/v1/advisor/link1", b.Token, nil), http.StatusNotFound},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) { expect(t, c.r, c.status) })
	}

	r := e.do("GET", "/v1/advisor/link1/blob", "", nil)
	expect(t, r, http.StatusOK)
	if r.header.Get("Access-Control-Allow-Origin") != "*" {
		t.Fatal("blob route needs CORS")
	}
	var ab advisorBlobResp
	r.decode(t, &ab)
	if ab.Blob != ct || ab.ExpiresAt != exp {
		t.Fatalf("blob = %+v", ab)
	}
	if e.do("GET", "/v1/keys", a.Token, nil).header.Get("Access-Control-Allow-Origin") != "" {
		t.Fatal("CORS must be limited to advisor routes")
	}

	page := e.do("GET", "/a/link1", "", nil)
	expect(t, page, http.StatusOK)
	csp := page.header.Get("Content-Security-Policy")
	if !strings.Contains(csp, "script-src 'sha256-") || !strings.Contains(string(page.body), "Read-only · shared from Penny") ||
		strings.Contains(string(page.body), "<script src") || !strings.Contains(string(page.body), "AES-GCM") {
		t.Fatalf("advisor page wrong; csp %q", csp)
	}
	body := string(page.body)
	script := body[strings.Index(body, "<script>")+len("<script>") : strings.Index(body, "</script>")]
	if !strings.Contains(csp, cspHash(script)) {
		t.Fatal("CSP hash does not match the inline script")
	}

	e.clock.Advance(48*time.Hour + time.Millisecond)
	expect(t, e.do("GET", "/v1/advisor/link1/blob", "", nil), http.StatusNotFound)

	expect(t, e.do("PUT", "/v1/advisor/link3", a.Token, advisorPutReq{Blob: ct, ExpiresAt: e.clock.Now().Add(time.Hour).UnixMilli()}), http.StatusNoContent)
	expect(t, e.do("DELETE", "/v1/advisor/link3", a.Token, nil), http.StatusNoContent)
	expect(t, e.do("GET", "/v1/advisor/link3/blob", "", nil), http.StatusNotFound)

	if err := e.store.PurgeExpired(context.Background(), e.clock.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := e.store.GetAdvisor(context.Background(), "link1"); err != ErrNotFound {
		t.Fatal("expired link not purged")
	}
}

func TestHealthz(t *testing.T) {
	e := newEnv(t)
	r := e.do("GET", "/healthz", "", nil)
	expect(t, r, http.StatusOK)
	if !strings.Contains(string(r.body), `"region":"eu"`) {
		t.Fatalf("healthz %s", r.body)
	}
}
