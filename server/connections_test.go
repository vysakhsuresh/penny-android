package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"penny/server/connectors"
)

// ---- fake SimpleFIN bridge ----

type fakeTx struct {
	ID      string
	Account string
	Posted  time.Time
	Amount  string
}

type fakeBridge struct {
	t       *testing.T
	srv     *httptest.Server
	mu      sync.Mutex
	claimed map[string]bool
	revoked bool
	txs     []fakeTx
	fetches int
}

const (
	bridgeUser = "sfin-user-7d1c"
	bridgePass = "s3cret-pass-91af"
)

func newFakeBridge(t *testing.T) *fakeBridge {
	b := &fakeBridge{t: t, claimed: map[string]bool{}}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /claim/{token}", func(w http.ResponseWriter, r *http.Request) {
		b.mu.Lock()
		defer b.mu.Unlock()
		tok := r.PathValue("token")
		if b.claimed[tok] {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		b.claimed[tok] = true
		u, _ := url.Parse(b.srv.URL)
		u.User = url.UserPassword(bridgeUser, bridgePass)
		u.Path = "/simplefin"
		fmt.Fprint(w, u.String())
	})
	mux.HandleFunc("GET /simplefin/accounts", func(w http.ResponseWriter, r *http.Request) {
		b.mu.Lock()
		defer b.mu.Unlock()
		b.fetches++
		user, pass, ok := r.BasicAuth()
		if !ok || user != bridgeUser || pass != bridgePass || b.revoked {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		q := r.URL.Query()
		start, _ := strconv.ParseInt(q.Get("start-date"), 10, 64)
		only := map[string]bool{}
		for _, a := range q["account"] {
			only[a] = true
		}
		type tx struct {
			ID          string `json:"id"`
			Posted      int64  `json:"posted"`
			Amount      string `json:"amount"`
			Description string `json:"description"`
		}
		type acct struct {
			Org          map[string]string `json:"org"`
			ID           string            `json:"id"`
			Name         string            `json:"name"`
			Currency     string            `json:"currency"`
			Balance      string            `json:"balance"`
			Transactions []tx              `json:"transactions"`
		}
		accts := []acct{
			{Org: map[string]string{"name": "First Fake Bank", "domain": "ffb.example"}, ID: "ACT-1", Name: "Checking", Currency: "USD", Balance: "1234.56"},
			{Org: map[string]string{"name": "First Fake Bank"}, ID: "ACT-2", Name: "Yen wallet", Currency: "JPY", Balance: "50000"},
		}
		out := []acct{}
		for _, a := range accts {
			if len(only) > 0 && !only[a.ID] {
				continue
			}
			a.Transactions = []tx{}
			if q.Get("balances-only") != "1" {
				for _, t := range b.txs {
					if t.Account == a.ID && t.Posted.Unix() >= start {
						a.Transactions = append(a.Transactions, tx{ID: t.ID, Posted: t.Posted.Unix(), Amount: t.Amount, Description: "Payee " + t.ID})
					}
				}
			}
			out = append(out, a)
		}
		writeJSON(w, http.StatusOK, map[string]any{"errors": []string{}, "accounts": out})
	})
	b.srv = httptest.NewServer(mux)
	t.Cleanup(b.srv.Close)
	return b
}

func (b *fakeBridge) setupToken(name string) string {
	return base64.StdEncoding.EncodeToString([]byte(b.srv.URL + "/claim/" + name))
}

func (b *fakeBridge) accessURL() string {
	u, _ := url.Parse(b.srv.URL)
	u.User = url.UserPassword(bridgeUser, bridgePass)
	u.Path = "/simplefin"
	return u.String()
}

func (b *fakeBridge) add(txs ...fakeTx) {
	b.mu.Lock()
	b.txs = append(b.txs, txs...)
	b.mu.Unlock()
}

// ---- harness ----

type connEnv struct {
	*testEnv
	bridge *fakeBridge
	sealer *Sealer
}

func newConnEnv(t *testing.T, dev bool) *connEnv {
	t.Helper()
	clock := &fakeClock{t: time.Date(2026, 3, 29, 1, 30, 0, 0, time.UTC)}
	mail := &captureMailer{codes: map[string]string{}}
	st := NewMemoryStore()
	bridge := newFakeBridge(t)
	sf := &connectors.SimpleFIN{Client: bridge.srv.Client(), AllowInsecure: true, Now: clock.Now}
	all := append([]connectors.Connector{connectors.NewSandbox(clock.Now), sf}, connectors.Stubs()...)
	reg, _, err := connectors.NewRegistry([]string{"sandbox", "simplefin", "plaid"}, all...)
	if err != nil {
		t.Fatal(err)
	}
	sealer := RandomSealer()
	srv := NewServer(Options{Store: st, Mailer: mail, Region: "eu", Pepper: []byte("test-pepper"), Now: clock.Now,
		Dev: dev, Connectors: reg, Sealer: sealer})
	return &connEnv{testEnv: &testEnv{t: t, h: srv.Handler(), clock: clock, mail: mail, store: st}, bridge: bridge, sealer: sealer}
}

func (e *connEnv) grantPlus(tok, product string) entitlementJSON {
	e.t.Helper()
	r := e.do("PUT", "/v1/entitlements/plus", tok, entitlementReq{Store: "play", PurchaseToken: "gp-token-" + product, ProductID: product})
	expect(e.t, r, http.StatusOK)
	var out entitlementJSON
	r.decode(e.t, &out)
	return out
}

func (e *connEnv) connectSimpleFIN(tok, name string) connectionJSON {
	e.t.Helper()
	r := e.do("POST", "/v1/connections", tok, connectReq{Provider: "simplefin", Token: e.bridge.setupToken(name)})
	expect(e.t, r, http.StatusCreated)
	var c connectionJSON
	r.decode(e.t, &c)
	return c
}

func (e *connEnv) connectSandbox(tok string) connectionJSON {
	e.t.Helper()
	r := e.do("POST", "/v1/connections", tok, connectReq{Provider: "sandbox", RedirectURI: "penny://bank/callback"})
	expect(e.t, r, http.StatusOK)
	var red connectRedirectResp
	r.decode(e.t, &red)
	u, err := url.Parse(red.RedirectURL)
	if err != nil || u.Query().Get("state") != red.State || red.State == "" {
		e.t.Fatalf("redirect %+v", red)
	}
	r = e.do("POST", "/v1/connections/complete", tok, completeReq{State: red.State, Params: map[string]string{"code": u.Query().Get("code")}})
	expect(e.t, r, http.StatusCreated)
	var c connectionJSON
	r.decode(e.t, &c)
	return c
}

func (e *connEnv) sync(tok, id string, body any) syncResp {
	e.t.Helper()
	r := e.do("POST", "/v1/connections/"+id+"/sync", tok, body)
	expect(e.t, r, http.StatusOK)
	var out syncResp
	r.decode(e.t, &out)
	return out
}

func byID(txs []remoteTxJSON) map[string]remoteTxJSON {
	m := map[string]remoteTxJSON{}
	for _, t := range txs {
		m[t.ID] = t
	}
	return m
}

// ---- tests ----

func TestListConnectors(t *testing.T) {
	e := newConnEnv(t, false)
	a := e.signIn("a@example.com")
	expect(t, e.do("GET", "/v1/connectors", "", nil), http.StatusUnauthorized)
	r := e.do("GET", "/v1/connectors", a.Token, nil)
	expect(t, r, http.StatusOK)
	var ls []connectorInfoJSON
	r.decode(t, &ls)
	got := map[string]connectorInfoJSON{}
	for _, l := range ls {
		got[l.ID] = l
	}
	if len(ls) != 19 {
		t.Fatalf("want 19 connectors, got %d", len(ls))
	}
	if c := got["simplefin"]; !c.Enabled || c.Kind != "token" || len(c.Regions) == 0 {
		t.Fatalf("simplefin %+v", c)
	}
	if c := got["sandbox"]; !c.Enabled || c.Kind != "redirect" {
		t.Fatalf("sandbox %+v", c)
	}
	if c := got["plaid"]; c.Enabled || c.Name != "Plaid" {
		t.Fatalf("plaid stub must be listed but disabled: %+v", c)
	}
	if !strings.Contains(string(r.body), `"regions":[`) || !strings.Contains(string(r.body), `"enabled":true`) {
		t.Fatalf("json shape %s", r.body)
	}
	// A listed but disabled provider cannot be connected.
	expect(t, e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "plaid", RedirectURI: "penny://cb"}), http.StatusBadRequest)
	expect(t, e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "nope"}), http.StatusBadRequest)
}

func TestSimpleFINConnectAndSync(t *testing.T) {
	e := newConnEnv(t, false)
	a := e.signIn("a@example.com")
	e.grantPlus(a.Token, "penny.plus.monthly")
	now := e.clock.Now()
	e.bridge.add(
		fakeTx{ID: "t1", Account: "ACT-1", Posted: now.Add(-48 * time.Hour), Amount: "-12.34"},
		fakeTx{ID: "t2", Account: "ACT-1", Posted: now.Add(-24 * time.Hour), Amount: "1000"},
		fakeTx{ID: "t3", Account: "ACT-2", Posted: now.Add(-24 * time.Hour), Amount: "-1500"},
		fakeTx{ID: "t-old", Account: "ACT-1", Posted: now.Add(-200 * 24 * time.Hour), Amount: "-1.00"},
	)

	c := e.connectSimpleFIN(a.Token, "setup-1")
	if c.Provider != "simplefin" || c.Status != ConnActive || c.Institution != "First Fake Bank" || c.ConsentExpiry != nil || !strings.HasPrefix(c.ID, "cn-") {
		t.Fatalf("connection %+v", c)
	}
	if len(c.Accounts) != 2 || c.Accounts[0].BalanceMinor != 123456 || c.Accounts[0].Currency != "USD" ||
		c.Accounts[1].BalanceMinor != 50000 || c.Accounts[1].Currency != "JPY" {
		t.Fatalf("accounts %+v", c.Accounts)
	}
	// A setup token can be claimed once.
	r := e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "simplefin", Token: e.bridge.setupToken("setup-1")})
	expect(t, r, http.StatusBadRequest)
	expect(t, e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "simplefin"}), http.StatusBadRequest)
	expect(t, e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "simplefin", Token: "!!not-base64!!"}), http.StatusBadRequest)

	first := e.sync(a.Token, c.ID, syncReq{Accounts: []string{"ACT-1", "ACT-2"}})
	got := byID(first.Transactions)
	if len(first.Transactions) != 3 || first.Status != ConnActive || len(first.Accounts) != 2 {
		t.Fatalf("first sync %+v", first)
	}
	if got["t1"].AmountMinor != -1234 || got["t2"].AmountMinor != 100000 || got["t3"].AmountMinor != -1500 {
		t.Fatalf("amounts %+v", got)
	}
	if got["t3"].Currency != "JPY" || got["t1"].AccountID != "ACT-1" || got["t1"].Date != now.Add(-48*time.Hour).Unix()*1000 || got["t1"].Description != "Payee t1" {
		t.Fatalf("fields %+v", got["t1"])
	}
	if _, old := got["t-old"]; old {
		t.Fatal("default window is 90 days")
	}

	second := e.sync(a.Token, c.ID, syncReq{Accounts: []string{"ACT-1", "ACT-2"}})
	if len(second.Transactions) != 0 {
		t.Fatalf("second sync should return nothing new, got %+v", second.Transactions)
	}

	e.clock.Advance(3 * time.Hour)
	e.bridge.add(fakeTx{ID: "t4", Account: "ACT-1", Posted: e.clock.Now().Add(-time.Hour), Amount: "-0.99"})
	third := e.sync(a.Token, c.ID, nil) // empty body: all accounts
	if len(third.Transactions) != 1 || third.Transactions[0].ID != "t4" || third.Transactions[0].AmountMinor != -99 {
		t.Fatalf("third sync %+v", third.Transactions)
	}

	// Only the requested account.
	e.bridge.add(fakeTx{ID: "t5", Account: "ACT-2", Posted: e.clock.Now(), Amount: "300"})
	only1 := e.sync(a.Token, c.ID, syncReq{Accounts: []string{"ACT-1"}})
	if len(only1.Transactions) != 0 || len(only1.Accounts) != 1 || only1.Accounts[0].ID != "ACT-1" {
		t.Fatalf("account filter %+v", only1)
	}
	// full re-delivers everything in the window (for a reinstall), with an explicit since.
	since := now.Add(-30 * time.Hour).UnixMilli()
	full := e.sync(a.Token, c.ID, syncReq{Since: &since, Full: true})
	if ids := byID(full.Transactions); len(ids) != 4 || ids["t1"].ID != "" {
		t.Fatalf("full sync %+v", full.Transactions)
	}

	r = e.do("GET", "/v1/connections", a.Token, nil)
	expect(t, r, http.StatusOK)
	var list []connectionJSON
	r.decode(t, &list)
	if len(list) != 1 || list[0].ID != c.ID || list[0].LastSyncedAt == nil || list[0].Accounts != nil {
		t.Fatalf("list %+v", list)
	}

	// Another account cannot see or use the connection.
	b := e.signIn("b@example.com")
	e.grantPlus(b.Token, "penny.plus.monthly")
	expect(t, e.do("POST", "/v1/connections/"+c.ID+"/sync", b.Token, nil), http.StatusNotFound)
	expect(t, e.do("DELETE", "/v1/connections/"+c.ID, b.Token, nil), http.StatusNotFound)
}

func TestSandboxSyncReturnsOnlyNew(t *testing.T) {
	e := newConnEnv(t, true)
	a := e.signIn("a@example.com")
	c := e.connectSandbox(a.Token)
	first := e.sync(a.Token, c.ID, syncReq{Accounts: []string{"sbx-checking"}})
	if len(first.Transactions) < 150 {
		t.Fatalf("sandbox should return ~90 days, got %d", len(first.Transactions))
	}
	for _, tx := range first.Transactions {
		if tx.AmountMinor == 0 || tx.Currency != "USD" || tx.AccountID != "sbx-checking" {
			t.Fatalf("bad tx %+v", tx)
		}
	}
	if again := e.sync(a.Token, c.ID, syncReq{Accounts: []string{"sbx-checking"}}); len(again.Transactions) != 0 {
		t.Fatalf("repeat sync returned %d", len(again.Transactions))
	}
	e.clock.Advance(24 * time.Hour)
	next := e.sync(a.Token, c.ID, syncReq{Accounts: []string{"sbx-checking"}})
	if len(next.Transactions) < 2 || len(next.Transactions) > 3 {
		t.Fatalf("next day should bring one day's transactions, got %+v", next.Transactions)
	}
	seen := byID(first.Transactions)
	for _, tx := range next.Transactions {
		if _, dup := seen[tx.ID]; dup {
			t.Fatalf("re-delivered %s", tx.ID)
		}
	}
	// Savings is JPY with 0 decimals.
	sav := e.sync(a.Token, c.ID, syncReq{Accounts: []string{"sbx-savings"}})
	if len(sav.Transactions) == 0 || sav.Transactions[0].Currency != "JPY" || sav.Transactions[0].AmountMinor != 5000 {
		t.Fatalf("savings %+v", sav.Transactions)
	}
}

func TestPlusRequired(t *testing.T) {
	e := newConnEnv(t, false)
	a := e.signIn("a@example.com")
	r := e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "simplefin", Token: e.bridge.setupToken("p1")})
	expect(t, r, http.StatusPaymentRequired)
	var eb errorBody
	r.decode(t, &eb)
	if eb.Error != "plus_required" {
		t.Fatalf("error %q", eb.Error)
	}
	if e.bridge.claimed["p1"] {
		t.Fatal("token must not be claimed without Plus")
	}
	// The sandbox needs no entitlement.
	sbx := e.connectSandbox(a.Token)

	var ent entitlementJSON
	e.do("GET", "/v1/entitlements/plus", a.Token, nil).decode(t, &ent)
	if ent.Plus || ent.PlusUntil != nil {
		t.Fatalf("no entitlement yet: %+v", ent)
	}
	ent = e.grantPlus(a.Token, "penny.plus.monthly")
	if !ent.Plus || ent.PlusUntil == nil || *ent.PlusUntil != e.clock.Now().Add(32*24*time.Hour).UnixMilli() {
		t.Fatalf("monthly %+v", ent)
	}
	c := e.connectSimpleFIN(a.Token, "p2")
	expect(t, e.do("PUT", "/v1/entitlements/plus", a.Token, entitlementReq{Store: "appstore", PurchaseToken: "x", ProductID: "p"}), http.StatusBadRequest)
	expect(t, e.do("PUT", "/v1/entitlements/plus", a.Token, entitlementReq{Store: "play", ProductID: "p"}), http.StatusBadRequest)

	// Lapsed: sync and reconsent need Plus; list and delete stay available.
	e.clock.Advance(33 * 24 * time.Hour)
	expect(t, e.do("POST", "/v1/connections/"+c.ID+"/sync", a.Token, nil), http.StatusPaymentRequired)
	expect(t, e.do("POST", "/v1/connections/"+c.ID+"/reconsent", a.Token, nil), http.StatusPaymentRequired)
	expect(t, e.do("GET", "/v1/connections", a.Token, nil), http.StatusOK)
	e.sync(a.Token, sbx.ID, nil)

	ent = e.grantPlus(a.Token, "penny.plus.lifetime")
	if *ent.PlusUntil != lifetimeUntil.UnixMilli() {
		t.Fatalf("lifetime %+v", ent)
	}
	// A later monthly purchase never shortens a lifetime grant.
	if ent = e.grantPlus(a.Token, "penny.plus.monthly"); *ent.PlusUntil != lifetimeUntil.UnixMilli() || ent.ProductID != "penny.plus.lifetime" {
		t.Fatalf("shortened %+v", ent)
	}
	e.sync(a.Token, c.ID, nil)
	expect(t, e.do("DELETE", "/v1/connections/"+c.ID, a.Token, nil), http.StatusNoContent)

	// Dev mode waives the entitlement.
	d := newConnEnv(t, true)
	da := d.signIn("a@example.com")
	d.connectSimpleFIN(da.Token, "dev")
	var dent entitlementJSON
	d.do("GET", "/v1/entitlements/plus", da.Token, nil).decode(t, &dent)
	if !dent.Plus {
		t.Fatal("dev mode reports plus")
	}
}

func TestSandboxConsentLifecycle(t *testing.T) {
	e := newConnEnv(t, false)
	a := e.signIn("a@example.com")
	expect(t, e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "sandbox"}), http.StatusBadRequest) // needs redirectUri

	r := e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "sandbox", RedirectURI: "penny://bank/callback"})
	expect(t, r, http.StatusOK)
	var red connectRedirectResp
	r.decode(t, &red)
	if !strings.HasPrefix(red.RedirectURL, "penny://bank/callback?") {
		t.Fatalf("redirect %+v", red)
	}
	b := e.signIn("b@example.com")
	expect(t, e.do("POST", "/v1/connections/complete", b.Token, completeReq{State: red.State, Params: map[string]string{"code": connectors.SandboxCode}}), http.StatusNotFound)
	expect(t, e.do("POST", "/v1/connections/complete", a.Token, completeReq{State: red.State, Params: map[string]string{"code": "denied"}}), http.StatusBadRequest)
	// The state is single use, even after a failed completion.
	expect(t, e.do("POST", "/v1/connections/complete", a.Token, completeReq{State: red.State, Params: map[string]string{"code": connectors.SandboxCode}}), http.StatusNotFound)

	// Expired consent session.
	r = e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "sandbox", RedirectURI: "penny://cb"})
	r.decode(t, &red)
	e.clock.Advance(31 * time.Minute)
	expect(t, e.do("POST", "/v1/connections/complete", a.Token, completeReq{State: red.State, Params: map[string]string{"code": connectors.SandboxCode}}), http.StatusGone)

	c := e.connectSandbox(a.Token)
	if c.Status != ConnActive || c.ConsentExpiry == nil || c.Institution != "Sandbox Bank" || len(c.Accounts) != 2 {
		t.Fatalf("connection %+v", c)
	}
	e.sync(a.Token, c.ID, nil)

	// Consent lapses: the list shows needs_consent and sync reports it.
	e.clock.Advance(91 * 24 * time.Hour)
	var list []connectionJSON
	e.do("GET", "/v1/connections", a.Token, nil).decode(t, &list)
	if list[0].Status != ConnNeedsConsent {
		t.Fatalf("list %+v", list)
	}
	if s := e.sync(a.Token, c.ID, nil); s.Status != ConnNeedsConsent || len(s.Transactions) != 0 {
		t.Fatalf("sync %+v", s)
	}

	// Re-consent renews the same connection.
	r = e.do("POST", "/v1/connections/"+c.ID+"/reconsent", a.Token, connectReq{RedirectURI: "penny://bank/callback"})
	expect(t, r, http.StatusOK)
	r.decode(t, &red)
	r = e.do("POST", "/v1/connections/complete", a.Token, completeReq{State: red.State, Params: map[string]string{"code": connectors.SandboxCode}})
	expect(t, r, http.StatusOK)
	var renewed connectionJSON
	r.decode(t, &renewed)
	if renewed.ID != c.ID || renewed.Status != ConnActive || *renewed.ConsentExpiry <= *c.ConsentExpiry {
		t.Fatalf("renewed %+v", renewed)
	}
	if s := e.sync(a.Token, c.ID, nil); s.Status != ConnActive || len(s.Transactions) == 0 {
		t.Fatalf("after reconsent %+v", s.Status)
	}
	expect(t, e.do("POST", "/v1/connections/"+c.ID+"/reconsent", a.Token, connectReq{Provider: "simplefin"}), http.StatusBadRequest)
}

func TestSimpleFINRevokedAtBridge(t *testing.T) {
	e := newConnEnv(t, true)
	a := e.signIn("a@example.com")
	c := e.connectSimpleFIN(a.Token, "r1")
	e.bridge.mu.Lock()
	e.bridge.revoked = true
	e.bridge.mu.Unlock()
	s := e.sync(a.Token, c.ID, nil)
	if s.Status != ConnDisconnected {
		t.Fatalf("status %+v", s)
	}
	bc, err := e.store.GetConnection(context.Background(), c.ID)
	if err != nil || bc.Secret != nil || bc.Status != ConnDisconnected {
		t.Fatalf("credentials should be dropped: %+v %v", bc, err)
	}
	// Reconnecting with a new setup token restores it.
	e.bridge.mu.Lock()
	e.bridge.revoked = false
	e.bridge.mu.Unlock()
	r := e.do("POST", "/v1/connections/"+c.ID+"/reconsent", a.Token, connectReq{Token: e.bridge.setupToken("r2")})
	expect(t, r, http.StatusOK)
	if s := e.sync(a.Token, c.ID, nil); s.Status != ConnActive {
		t.Fatalf("after reconnect %+v", s)
	}
}

func TestDisconnectDeletesCredentials(t *testing.T) {
	e := newConnEnv(t, true)
	a := e.signIn("a@example.com")
	c := e.connectSimpleFIN(a.Token, "d1")
	e.sync(a.Token, c.ID, nil)
	// A pending re-consent for this connection goes with it.
	sbx := e.connectSandbox(a.Token)
	expect(t, e.do("POST", "/v1/connections/"+sbx.ID+"/reconsent", a.Token, connectReq{RedirectURI: "penny://cb"}), http.StatusOK)

	expect(t, e.do("DELETE", "/v1/connections/"+c.ID, a.Token, nil), http.StatusNoContent)
	if _, err := e.store.GetConnection(context.Background(), c.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("connection row should be gone, got %v", err)
	}
	expect(t, e.do("DELETE", "/v1/connections/"+c.ID, a.Token, nil), http.StatusNotFound)
	expect(t, e.do("POST", "/v1/connections/"+c.ID+"/sync", a.Token, nil), http.StatusNotFound)

	expect(t, e.do("DELETE", "/v1/connections/"+sbx.ID, a.Token, nil), http.StatusNoContent)
	e.store.mu.Lock()
	nConns, nStates := len(e.store.conns), len(e.store.states)
	e.store.mu.Unlock()
	if nConns != 0 || nStates != 0 {
		t.Fatalf("left behind: %d connections, %d states", nConns, nStates)
	}
	var list []connectionJSON
	e.do("GET", "/v1/connections", a.Token, nil).decode(t, &list)
	if len(list) != 0 {
		t.Fatalf("list %+v", list)
	}
}

func TestAccountDeletionRemovesConnections(t *testing.T) {
	e := newConnEnv(t, false)
	a := e.signIn("a@example.com")
	other := e.signIn("other@example.com")
	e.grantPlus(a.Token, "penny.plus.monthly")
	e.connectSimpleFIN(a.Token, "x1")
	e.connectSandbox(a.Token)
	expect(t, e.do("POST", "/v1/connections", a.Token, connectReq{Provider: "sandbox", RedirectURI: "penny://cb"}), http.StatusOK) // pending
	keep := e.connectSandbox(other.Token)

	expect(t, e.do("DELETE", "/v1/account", a.Token, nil), http.StatusNoContent)
	ctx := context.Background()
	if cs, _ := e.store.ListConnections(ctx, a.AccountID); len(cs) != 0 {
		t.Fatalf("connections left: %d", len(cs))
	}
	if _, err := e.store.GetEntitlement(ctx, a.AccountID); !errors.Is(err, ErrNotFound) {
		t.Fatal("entitlement left")
	}
	e.store.mu.Lock()
	for _, st := range e.store.states {
		if st.AccountID == a.AccountID {
			t.Error("connect state left")
		}
	}
	e.store.mu.Unlock()
	if _, err := e.store.GetConnection(ctx, keep.ID); err != nil {
		t.Fatal("another account's connection was deleted")
	}
}

func TestCredentialsEncryptedAtRest(t *testing.T) {
	e := newConnEnv(t, true)
	a := e.signIn("a@example.com")
	now := e.clock.Now()
	e.bridge.add(fakeTx{ID: "enc-1", Account: "ACT-1", Posted: now.Add(-time.Hour), Amount: "-77.01"})
	c := e.connectSimpleFIN(a.Token, "enc")
	e.sync(a.Token, c.ID, nil)

	e.store.mu.Lock()
	raw, _ := json.Marshal(e.store.conns)
	stored := e.store.conns[c.ID]
	e.store.mu.Unlock()
	access := e.bridge.accessURL()
	for _, needle := range []string{access, bridgePass, bridgeUser, "/simplefin", base64.StdEncoding.EncodeToString([]byte(bridgePass)), "Payee enc-1", "-77.01"} {
		if bytes.Contains(stored.Secret, []byte(needle)) || bytes.Contains(stored.Cursor, []byte(needle)) || bytes.Contains(raw, []byte(needle)) {
			t.Fatalf("stored connection contains %q", needle)
		}
	}
	if len(stored.Secret) < 1+12+16 || stored.Secret[0] != sealVersion {
		t.Fatalf("secret is not sealed: %x", stored.Secret)
	}
	// It opens with the server key and binds to its row.
	pt, err := e.sealer.Open(stored.Secret, secretAAD(stored))
	if err != nil || !bytes.Contains(pt, []byte(access)) {
		t.Fatalf("open: %v", err)
	}
	moved := stored
	moved.ID = "cn-other"
	if _, err := e.sealer.Open(stored.Secret, secretAAD(moved)); err == nil {
		t.Fatal("sealed secret must not open under another connection id")
	}
	if _, err := RandomSealer().Open(stored.Secret, secretAAD(stored)); err == nil {
		t.Fatal("sealed secret must not open with another key")
	}
	// The client never receives credentials.
	for _, path := range []string{"/v1/connections"} {
		body := e.do("GET", path, a.Token, nil).body
		if bytes.Contains(body, []byte(bridgePass)) || bytes.Contains(body, []byte("secret")) {
			t.Fatalf("%s leaks credentials: %s", path, body)
		}
	}
}

func TestConnectorKeyParsing(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawURLEncoding} {
		if k, err := ParseConnectorKey(enc.EncodeToString(key)); err != nil || !bytes.Equal(k, key) {
			t.Fatalf("parse: %v", err)
		}
	}
	if _, err := ParseConnectorKey(base64.StdEncoding.EncodeToString(key[:16])); err == nil {
		t.Fatal("16-byte key must be rejected")
	}
	s, _ := NewSealer(key)
	ct := s.Seal([]byte("hello"), "aad")
	if pt, err := s.Open(ct, "aad"); err != nil || string(pt) != "hello" {
		t.Fatal("round trip")
	}
	ct[len(ct)-1] ^= 1
	if _, err := s.Open(ct, "aad"); err == nil {
		t.Fatal("tampered ciphertext must fail")
	}
}
