package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"

	"penny/server/connectors"
)

// Bank connectors (CP-10, AC-09, SE-08). Provider credentials live only here, sealed with
// AES-256-GCM; the client gets connection ids, statuses and normalised data. Transactions pass
// through in the sync response and are never stored: the only sync state is a cursor of last
// fetch times and truncated hashes of seen transaction ids, kept for 60 days.

const (
	connStateTTL       = 30 * time.Minute
	connectorTimeout   = 90 * time.Second
	disconnectTimeout  = 20 * time.Second
	seenRetention      = 60 * 24 * time.Hour
	syncOverlap        = 7 * 24 * time.Hour
	defaultSyncBack    = 90 * 24 * time.Hour
	maxSyncBack        = 2 * 365 * 24 * time.Hour
	maxSeen            = 100_000
	maxSyncAccounts    = 100
	maxCompleteParams  = 32
	maxRedirectURI     = 2048
	maxConnectorToken  = 4096
	plusSubscription   = 32 * 24 * time.Hour
	sandboxProvider    = "sandbox"
	entitlementStoreGP = "play"
)

// lifetimeUntil is the plusUntil recorded for lifetime purchases.
var lifetimeUntil = time.Date(9999, 12, 31, 0, 0, 0, 0, time.UTC)

var errPlusRequired = apiError{http.StatusPaymentRequired, "plus_required"}

// ---- JSON shapes ----

type connectorInfoJSON struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Kind    string   `json:"kind"`
	Regions []string `json:"regions"`
	Enabled bool     `json:"enabled"`
}

type connectReq struct {
	Provider    string `json:"provider"`
	RedirectURI string `json:"redirectUri"`
	Token       string `json:"token,omitempty"`
}

type connectRedirectResp struct {
	RedirectURL string `json:"redirectUrl"`
	State       string `json:"state"`
}

type completeReq struct {
	State  string            `json:"state"`
	Params map[string]string `json:"params"`
}

type remoteAccountJSON struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Currency     string `json:"currency"`
	Type         string `json:"type"`
	BalanceMinor int64  `json:"balanceMinor"`
}

type remoteTxJSON struct {
	ID          string `json:"id"`
	AccountID   string `json:"accountId"`
	Date        int64  `json:"date"`
	AmountMinor int64  `json:"amountMinor"`
	Currency    string `json:"currency"`
	Description string `json:"description"`
	Pending     bool   `json:"pending"`
}

type connectionJSON struct {
	ID            string              `json:"id"`
	Provider      string              `json:"provider"`
	Institution   string              `json:"institution"`
	Status        string              `json:"status"`
	ConsentExpiry *int64              `json:"consentExpiry"`
	LastSyncedAt  *int64              `json:"lastSyncedAt"`
	CreatedAt     int64               `json:"createdAt"`
	Accounts      []remoteAccountJSON `json:"accounts,omitempty"`
}

type syncReq struct {
	Accounts []string `json:"accounts"`
	Since    *int64   `json:"since,omitempty"`
	Full     bool     `json:"full,omitempty"`
}

type syncResp struct {
	Transactions []remoteTxJSON      `json:"transactions"`
	Accounts     []remoteAccountJSON `json:"accounts"`
	Status       string              `json:"status"`
	Message      string              `json:"message,omitempty"`
}

type entitlementReq struct {
	Store         string `json:"store"`
	PurchaseToken string `json:"purchaseToken"`
	ProductID     string `json:"productId"`
}

type entitlementJSON struct {
	Plus      bool   `json:"plus"`
	PlusUntil *int64 `json:"plusUntil"`
	ProductID string `json:"productId,omitempty"`
	Store     string `json:"store,omitempty"`
}

func msPtr(t *time.Time) *int64 {
	if t == nil {
		return nil
	}
	v := t.UnixMilli()
	return &v
}

func toAccountsJSON(as []connectors.RemoteAccount) []remoteAccountJSON {
	out := make([]remoteAccountJSON, len(as))
	for i, a := range as {
		out[i] = remoteAccountJSON{ID: a.ID, Name: a.Name, Currency: a.Currency, Type: a.Type, BalanceMinor: a.BalanceMinor}
	}
	return out
}

// effectiveStatus reports needs_consent once an active connection's consent has lapsed.
func (s *Server) effectiveStatus(bc BankConnection) string {
	if bc.Status == ConnActive && bc.ConsentExpiry != nil && !s.now().Before(*bc.ConsentExpiry) {
		return ConnNeedsConsent
	}
	return bc.Status
}

func (s *Server) toConnectionJSON(bc BankConnection, accts []connectors.RemoteAccount) connectionJSON {
	out := connectionJSON{ID: bc.ID, Provider: bc.Provider, Institution: bc.Institution, Status: s.effectiveStatus(bc),
		ConsentExpiry: msPtr(bc.ConsentExpiry), LastSyncedAt: msPtr(bc.LastSyncedAt), CreatedAt: bc.CreatedAt.UnixMilli()}
	if accts != nil {
		out.Accounts = toAccountsJSON(accts)
	}
	return out
}

// ---- helpers ----

func newConnID() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return "cn-" + hex.EncodeToString(b)
}

func decodeOptionalJSON(w http.ResponseWriter, r *http.Request, v any, limit int64) bool {
	err := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit)).Decode(v)
	if err == nil || errors.Is(err, io.EOF) {
		return true
	}
	var mbe *http.MaxBytesError
	if errors.As(err, &mbe) {
		writeErr(w, http.StatusRequestEntityTooLarge, "request body too large")
	} else {
		writeErr(w, http.StatusBadRequest, "invalid JSON body")
	}
	return false
}

// connector returns an enabled adapter or a 400.
func (s *Server) connector(id string) (connectors.Connector, error) {
	if !idRe.MatchString(id) {
		return nil, apiError{http.StatusBadRequest, "invalid provider"}
	}
	c, ok := s.connectors.Get(id)
	if !ok {
		return nil, apiError{http.StatusBadRequest, "provider is not available"}
	}
	return c, nil
}

// requirePlus gates bank sync behind Plus, except in dev mode and for the sandbox.
func (s *Server) requirePlus(ctx context.Context, accountID, provider string) error {
	if s.dev || provider == sandboxProvider {
		return nil
	}
	e, err := s.store.GetEntitlement(ctx, accountID)
	if errors.Is(err, ErrNotFound) {
		return errPlusRequired
	} else if err != nil {
		return err
	}
	if !s.now().Before(e.PlusUntil) {
		return errPlusRequired
	}
	return nil
}

// safeErr renders a provider error without request URLs, which can carry one-time claim tokens.
func safeErr(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) {
		return ue.Op + ": " + ue.Err.Error()
	}
	return err.Error()
}

// connectorErr maps an adapter error during connect to an HTTP error.
func (s *Server) connectorErr(r *http.Request, provider string, err error) error {
	switch {
	case errors.Is(err, connectors.ErrNotConfigured):
		return apiError{http.StatusNotImplemented, "provider not configured"}
	case errors.Is(err, connectors.ErrInvalidInput):
		msg := strings.TrimPrefix(err.Error(), connectors.ErrInvalidInput.Error()+": ")
		return apiError{http.StatusBadRequest, msg}
	case errors.Is(err, connectors.ErrConsentRequired):
		return apiError{http.StatusConflict, "consent required"}
	case errors.Is(err, connectors.ErrRevoked):
		return apiError{http.StatusConflict, "access revoked"}
	case errors.Is(err, context.DeadlineExceeded):
		return apiError{http.StatusGatewayTimeout, "provider timed out"}
	}
	s.log.Warn("connector error", "route", r.Pattern, "provider", provider, "err", safeErr(err))
	return apiError{http.StatusBadGateway, "provider error"}
}

type sealedSecret struct {
	Ref    string `json:"ref"`
	Secret string `json:"secret"`
}

func secretAAD(bc BankConnection) string { return "penny-connection:" + bc.ID + ":" + bc.Provider }

func (s *Server) sealSecret(bc BankConnection, pc connectors.Connection) ([]byte, error) {
	raw, err := json.Marshal(sealedSecret{Ref: pc.ID, Secret: pc.Secret})
	if err != nil {
		return nil, err
	}
	return s.sealer.Seal(raw, secretAAD(bc)), nil
}

func (s *Server) openSecret(bc BankConnection) (connectors.Connection, error) {
	if bc.Secret == nil {
		return connectors.Connection{}, errors.New("no credentials")
	}
	raw, err := s.sealer.Open(bc.Secret, secretAAD(bc))
	if err != nil {
		return connectors.Connection{}, err
	}
	var ss sealedSecret
	if err := json.Unmarshal(raw, &ss); err != nil {
		return connectors.Connection{}, err
	}
	return connectors.Connection{ID: ss.Ref, Institution: bc.Institution, ConsentExpiry: bc.ConsentExpiry, Secret: ss.Secret}, nil
}

func (s *Server) ownConnection(ctx context.Context, accountID, id string) (BankConnection, error) {
	bc, err := s.store.GetConnection(ctx, id)
	if err == nil && bc.AccountID != accountID {
		err = ErrNotFound
	}
	return bc, err
}

// disconnectAtProvider revokes access at the provider, best-effort.
func (s *Server) disconnectAtProvider(bc BankConnection) {
	if bc.Secret == nil {
		return
	}
	conn, ok := s.connectors.Get(bc.Provider)
	if !ok {
		s.log.Warn("disconnect skipped: provider not enabled", "provider", bc.Provider)
		return
	}
	pc, err := s.openSecret(bc)
	if err != nil {
		s.log.Warn("disconnect skipped: cannot open credentials", "provider", bc.Provider)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), disconnectTimeout)
	defer cancel()
	if err := conn.Disconnect(ctx, pc); err != nil {
		s.log.Warn("provider disconnect failed", "provider", bc.Provider, "err", safeErr(err))
	}
}

// ---- handlers ----

func (s *Server) listConnectors(w http.ResponseWriter, r *http.Request, _ caller) {
	ls := s.connectors.List()
	out := make([]connectorInfoJSON, len(ls))
	for i, l := range ls {
		out[i] = connectorInfoJSON{ID: l.ID, Name: l.Name, Kind: l.Kind, Regions: l.Regions, Enabled: l.Enabled}
	}
	writeJSON(w, http.StatusOK, out)
}

func validConnectReq(req connectReq) error {
	switch {
	case len(req.RedirectURI) > maxRedirectURI:
		return apiError{http.StatusBadRequest, "redirectUri too long"}
	case len(req.Token) > maxConnectorToken:
		return apiError{http.StatusBadRequest, "token too long"}
	}
	return nil
}

func (s *Server) createConnection(w http.ResponseWriter, r *http.Request, c caller) {
	var req connectReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	if err := validConnectReq(req); err != nil {
		s.fail(w, r, err)
		return
	}
	conn, err := s.connector(req.Provider)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if err := s.requirePlus(r.Context(), c.AccountID, conn.ID()); err != nil {
		s.fail(w, r, err)
		return
	}
	s.beginConnect(w, r, c, conn, req, nil)
}

func (s *Server) reconsentConnection(w http.ResponseWriter, r *http.Request, c caller) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req connectReq
	if !decodeOptionalJSON(w, r, &req, smallBody) {
		return
	}
	if err := validConnectReq(req); err != nil {
		s.fail(w, r, err)
		return
	}
	bc, err := s.ownConnection(r.Context(), c.AccountID, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if req.Provider != "" && req.Provider != bc.Provider {
		writeErr(w, http.StatusBadRequest, "provider does not match the connection")
		return
	}
	conn, err := s.connector(bc.Provider)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if err := s.requirePlus(r.Context(), c.AccountID, conn.ID()); err != nil {
		s.fail(w, r, err)
		return
	}
	s.beginConnect(w, r, c, conn, req, &bc)
}

// beginConnect starts a new connection (existing == nil) or renews consent for existing.
func (s *Server) beginConnect(w http.ResponseWriter, r *http.Request, c caller, conn connectors.Connector, req connectReq, existing *BankConnection) {
	ctx, cancel := context.WithTimeout(r.Context(), connectorTimeout)
	defer cancel()
	st, err := conn.Start(ctx, c.AccountID, req.RedirectURI)
	if err != nil {
		s.fail(w, r, s.connectorErr(r, conn.ID(), err))
		return
	}
	switch st.Kind {
	case connectors.KindToken:
		if strings.TrimSpace(req.Token) == "" {
			writeErr(w, http.StatusBadRequest, "token is required for this provider")
			return
		}
		pc, err := conn.Complete(ctx, c.AccountID, map[string]string{"token": req.Token})
		if err != nil {
			s.fail(w, r, s.connectorErr(r, conn.ID(), err))
			return
		}
		s.finishConnect(ctx, w, r, c, conn, pc, existing)
	case connectors.KindRedirect:
		if st.State == "" || st.URL == "" {
			s.fail(w, r, errors.New("connector returned no consent URL or state"))
			return
		}
		cs := ConnectState{StateHash: sha256Hex(st.State), AccountID: c.AccountID, Provider: conn.ID(),
			RedirectURI: req.RedirectURI, ExpiresAt: s.now().Add(connStateTTL)}
		if existing != nil {
			cs.ConnectionID = existing.ID
		}
		if err := s.store.PutConnectState(r.Context(), cs); err != nil {
			s.fail(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, connectRedirectResp{RedirectURL: st.URL, State: st.State})
	default:
		s.fail(w, r, errors.New("connector returned an unknown kind"))
	}
}

func (s *Server) completeConnection(w http.ResponseWriter, r *http.Request, c caller) {
	var req completeReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	if req.State == "" || len(req.State) > 512 {
		writeErr(w, http.StatusBadRequest, "state is required")
		return
	}
	if len(req.Params) > maxCompleteParams {
		writeErr(w, http.StatusBadRequest, "too many params")
		return
	}
	ctx := r.Context()
	st, err := s.store.TakeConnectState(ctx, sha256Hex(req.State), c.AccountID)
	if errors.Is(err, ErrNotFound) {
		writeErr(w, http.StatusNotFound, "unknown or already used state")
		return
	} else if err != nil {
		s.fail(w, r, err)
		return
	}
	if !s.now().Before(st.ExpiresAt) {
		writeErr(w, http.StatusGone, "consent session expired, start again")
		return
	}
	conn, err := s.connector(st.Provider)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if err := s.requirePlus(ctx, c.AccountID, conn.ID()); err != nil {
		s.fail(w, r, err)
		return
	}
	var existing *BankConnection
	if st.ConnectionID != "" {
		bc, err := s.ownConnection(ctx, c.AccountID, st.ConnectionID)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		existing = &bc
	}
	params := make(map[string]string, len(req.Params)+2)
	for k, v := range req.Params {
		params[k] = v
	}
	params["state"] = req.State
	params["redirect_uri"] = st.RedirectURI
	cctx, cancel := context.WithTimeout(ctx, connectorTimeout)
	defer cancel()
	pc, err := conn.Complete(cctx, c.AccountID, params)
	if err != nil {
		s.fail(w, r, s.connectorErr(r, conn.ID(), err))
		return
	}
	s.finishConnect(cctx, w, r, c, conn, pc, existing)
}

// finishConnect seals the new credentials, stores the connection and returns it with its
// accounts. If anything fails after the provider granted access, access is revoked again.
func (s *Server) finishConnect(ctx context.Context, w http.ResponseWriter, r *http.Request, c caller,
	conn connectors.Connector, pc connectors.Connection, existing *BankConnection) {
	undo := func() {
		dctx, cancel := context.WithTimeout(context.Background(), disconnectTimeout)
		defer cancel()
		_ = conn.Disconnect(dctx, pc)
	}
	accts, err := conn.Accounts(ctx, pc)
	if err != nil {
		undo()
		s.fail(w, r, s.connectorErr(r, conn.ID(), err))
		return
	}
	now := s.now()
	var bc BankConnection
	var old *connectors.Connection
	if existing != nil {
		bc = *existing
		if prev, err := s.openSecret(bc); err == nil && prev.ID != pc.ID {
			old = &prev
		}
	} else {
		bc = BankConnection{ID: newConnID(), AccountID: c.AccountID, Provider: conn.ID(), CreatedAt: now}
	}
	sealed, err := s.sealSecret(bc, pc)
	if err != nil {
		undo()
		s.fail(w, r, err)
		return
	}
	bc.Institution = pc.Institution
	if bc.Institution == "" {
		bc.Institution = conn.Info().Name
	}
	bc.Status, bc.ConsentExpiry, bc.Secret, bc.UpdatedAt = ConnActive, pc.ConsentExpiry, sealed, now
	status := http.StatusCreated
	if existing != nil {
		status = http.StatusOK
		err = s.store.UpdateConnection(r.Context(), bc)
	} else {
		err = s.store.CreateConnection(r.Context(), bc)
	}
	if err != nil {
		undo()
		s.fail(w, r, err)
		return
	}
	if old != nil { // renewed consent replaced the provider-side grant; revoke the old one
		dctx, cancel := context.WithTimeout(context.Background(), disconnectTimeout)
		defer cancel()
		if err := conn.Disconnect(dctx, *old); err != nil {
			s.log.Warn("revoking replaced grant failed", "provider", conn.ID(), "err", safeErr(err))
		}
	}
	s.log.Info("bank connection stored", "provider", conn.ID(), "accounts", len(accts), "renewed", existing != nil)
	writeJSON(w, status, s.toConnectionJSON(bc, accts))
}

func (s *Server) listConnections(w http.ResponseWriter, r *http.Request, c caller) {
	bcs, err := s.store.ListConnections(r.Context(), c.AccountID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]connectionJSON, len(bcs))
	for i, bc := range bcs {
		out[i] = s.toConnectionJSON(bc, nil)
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) deleteConnection(w http.ResponseWriter, r *http.Request, c caller) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	bc, err := s.ownConnection(r.Context(), c.AccountID, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.disconnectAtProvider(bc)
	if err := s.store.DeleteConnection(r.Context(), c.AccountID, id); err != nil {
		s.fail(w, r, err)
		return
	}
	s.log.Info("bank connection deleted", "provider", bc.Provider)
	w.WriteHeader(http.StatusNoContent)
}

// disconnectAll revokes every connection of an account at its provider (best-effort). The
// rows themselves go with the account.
func (s *Server) disconnectAll(ctx context.Context, accountID string) error {
	bcs, err := s.store.ListConnections(ctx, accountID)
	if err != nil {
		return err
	}
	for _, bc := range bcs {
		s.disconnectAtProvider(bc)
	}
	return nil
}

// ---- sync ----

// syncCursor is the only per-connection sync state: when each remote account was last fetched
// and truncated hashes of the transaction ids already delivered (with the transaction day, so
// entries can expire after seenRetention). It holds no amounts, payees or descriptions.
type syncCursor struct {
	Fetched map[string]int64 `json:"f,omitempty"` // remote account id -> last fetch, epoch ms
	Seen    map[string]int64 `json:"s,omitempty"` // seenKey -> transaction day, epoch days
}

func decodeCursor(b []byte) syncCursor {
	var c syncCursor
	if len(b) > 0 {
		_ = json.Unmarshal(b, &c) // a corrupt cursor only costs re-delivery; clients upsert by id
	}
	if c.Fetched == nil {
		c.Fetched = map[string]int64{}
	}
	if c.Seen == nil {
		c.Seen = map[string]int64{}
	}
	return c
}

// seenKey identifies a delivered transaction. Pending is part of the key so a pending
// transaction that later posts under the same id is delivered again as posted.
func seenKey(connID string, t connectors.RemoteTx) string {
	p := "0"
	if t.Pending {
		p = "1"
	}
	h := sha256.Sum256([]byte(connID + "\x00" + t.AccountID + "\x00" + t.ID + "\x00" + p))
	return hex.EncodeToString(h[:16])
}

func (c *syncCursor) prune(now time.Time) {
	floor := now.Add(-seenRetention).Unix() / 86400
	for k, d := range c.Seen {
		if d < floor {
			delete(c.Seen, k)
		}
	}
	if len(c.Seen) <= maxSeen {
		return
	}
	type kv struct {
		k string
		d int64
	}
	all := make([]kv, 0, len(c.Seen))
	for k, d := range c.Seen {
		all = append(all, kv{k, d})
	}
	sort.Slice(all, func(i, j int) bool { return all[i].d < all[j].d })
	for _, e := range all[:len(all)-maxSeen] {
		delete(c.Seen, e.k)
	}
}

// syncProblem records a provider failure on the connection and answers 200 with the status,
// so the client can show "needs consent" or "broken" without treating it as a transport error.
func (s *Server) syncProblem(w http.ResponseWriter, r *http.Request, bc BankConnection, err error) {
	status, msg := ConnBroken, "the provider could not be reached; try again later"
	switch {
	case errors.Is(err, connectors.ErrNotConfigured):
		s.fail(w, r, apiError{http.StatusNotImplemented, "provider not configured"})
		return
	case errors.Is(err, connectors.ErrConsentRequired):
		status, msg = ConnNeedsConsent, "consent required"
	case errors.Is(err, connectors.ErrRevoked):
		// The credentials are useless now: drop them and the cursor.
		status, msg = ConnDisconnected, "access was revoked at the provider"
		bc.Secret, bc.Cursor = nil, nil
	default:
		s.log.Warn("sync failed", "provider", bc.Provider, "err", safeErr(err))
	}
	bc.Status, bc.UpdatedAt = status, s.now()
	if uerr := s.store.UpdateConnection(r.Context(), bc); uerr != nil {
		s.fail(w, r, uerr)
		return
	}
	writeJSON(w, http.StatusOK, syncResp{Transactions: []remoteTxJSON{}, Accounts: []remoteAccountJSON{}, Status: status, Message: msg})
}

func (s *Server) syncConnection(w http.ResponseWriter, r *http.Request, c caller) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req syncReq
	if !decodeOptionalJSON(w, r, &req, smallBody) {
		return
	}
	if len(req.Accounts) > maxSyncAccounts {
		writeErr(w, http.StatusBadRequest, "too many accounts")
		return
	}
	ctx := r.Context()
	bc, err := s.ownConnection(ctx, c.AccountID, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	conn, err := s.connector(bc.Provider)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if err := s.requirePlus(ctx, c.AccountID, conn.ID()); err != nil {
		s.fail(w, r, err)
		return
	}
	now := s.now()
	if bc.Status == ConnDisconnected || bc.Secret == nil {
		writeJSON(w, http.StatusOK, syncResp{Transactions: []remoteTxJSON{}, Accounts: []remoteAccountJSON{},
			Status: ConnDisconnected, Message: "reconnect to resume sync"})
		return
	}
	if bc.ConsentExpiry != nil && !now.Before(*bc.ConsentExpiry) {
		s.syncProblem(w, r, bc, connectors.ErrConsentRequired)
		return
	}
	pc, err := s.openSecret(bc)
	if err != nil {
		// Typically a rotated CONNECTOR_KEY: re-consent stores fresh credentials.
		s.log.Error("cannot open connection credentials", "provider", bc.Provider)
		s.syncProblem(w, r, bc, connectors.ErrConsentRequired)
		return
	}
	cctx, cancel := context.WithTimeout(ctx, connectorTimeout)
	defer cancel()
	accts, err := conn.Accounts(cctx, pc)
	if err != nil {
		s.syncProblem(w, r, bc, err)
		return
	}
	if len(req.Accounts) > 0 {
		want := map[string]bool{}
		for _, a := range req.Accounts {
			want[a] = true
		}
		kept := accts[:0]
		for _, a := range accts {
			if want[a.ID] {
				kept = append(kept, a)
			}
		}
		accts = kept
	}
	cur := decodeCursor(bc.Cursor)
	floor := now.Add(-maxSyncBack)
	txs := []remoteTxJSON{}
	for _, a := range accts {
		since := now.Add(-defaultSyncBack)
		if req.Since != nil {
			since = time.UnixMilli(*req.Since)
		} else if last, ok := cur.Fetched[a.ID]; ok {
			since = time.UnixMilli(last).Add(-syncOverlap)
		}
		if since.Before(floor) {
			since = floor
		}
		got, err := conn.Transactions(cctx, pc, a.ID, since)
		if err != nil {
			s.syncProblem(w, r, bc, err)
			return
		}
		for _, t := range got {
			if t.AccountID == "" {
				t.AccountID = a.ID
			}
			if t.Currency == "" {
				t.Currency = a.Currency
			}
			k := seenKey(bc.ID, t)
			if _, dup := cur.Seen[k]; dup && !req.Full {
				continue
			}
			cur.Seen[k] = t.Date.Unix() / 86400
			txs = append(txs, remoteTxJSON{ID: t.ID, AccountID: t.AccountID, Date: t.Date.UnixMilli(), AmountMinor: t.AmountMinor,
				Currency: t.Currency, Description: t.Description, Pending: t.Pending})
		}
		cur.Fetched[a.ID] = now.UnixMilli()
	}
	cur.prune(now)
	raw, err := json.Marshal(cur)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	bc.Cursor, bc.Status, bc.LastSyncedAt, bc.UpdatedAt = raw, ConnActive, &now, now
	if err := s.store.UpdateConnection(ctx, bc); err != nil {
		s.fail(w, r, err)
		return
	}
	sort.SliceStable(txs, func(i, j int) bool {
		if txs[i].Date != txs[j].Date {
			return txs[i].Date < txs[j].Date
		}
		return txs[i].ID < txs[j].ID
	})
	writeJSON(w, http.StatusOK, syncResp{Transactions: txs, Accounts: toAccountsJSON(accts), Status: ConnActive})
}

// ---- entitlements ----

// verifyPlayPurchase decides until when a purchase grants Plus.
//
// TODO(before launch): this accepts any token. Replace it with a Google Play Developer API
// check: purchases.subscriptionsv2.get (subscriptions) or purchases.products.get (lifetime)
// for PLAY_PACKAGE_NAME with a service account (PLAY_SERVICE_ACCOUNT_JSON); use the returned
// expiry, reject pending/cancelled/refunded purchases, acknowledge the purchase, bind the
// token hash to one account, and handle Real-time Developer Notifications for revocations.
func verifyPlayPurchase(_ context.Context, productID, _ string, now time.Time) (time.Time, error) {
	if strings.Contains(strings.ToLower(productID), "lifetime") {
		return lifetimeUntil, nil
	}
	return now.Add(plusSubscription), nil
}

func (s *Server) entitlementJSON(e *Entitlement) entitlementJSON {
	out := entitlementJSON{Plus: s.dev}
	if e != nil {
		until := e.PlusUntil
		out.PlusUntil, out.ProductID, out.Store = msPtr(&until), e.ProductID, e.Store
		out.Plus = out.Plus || s.now().Before(e.PlusUntil)
	}
	return out
}

func (s *Server) putPlus(w http.ResponseWriter, r *http.Request, c caller) {
	var req entitlementReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	switch {
	case req.Store != entitlementStoreGP:
		writeErr(w, http.StatusBadRequest, "store must be play")
		return
	case req.PurchaseToken == "" || len(req.PurchaseToken) > maxConnectorToken:
		writeErr(w, http.StatusBadRequest, "purchaseToken must be 1 to 4096 characters")
		return
	case !idRe.MatchString(req.ProductID):
		writeErr(w, http.StatusBadRequest, "invalid productId")
		return
	}
	ctx := r.Context()
	now := s.now()
	until, err := verifyPlayPurchase(ctx, req.ProductID, req.PurchaseToken, now)
	if err != nil {
		writeErr(w, http.StatusPaymentRequired, "purchase could not be verified")
		return
	}
	e := Entitlement{AccountID: c.AccountID, Store: req.Store, ProductID: req.ProductID, TokenHash: sha256Hex(req.PurchaseToken),
		PlusUntil: until, UpdatedAt: now}
	if old, err := s.store.GetEntitlement(ctx, c.AccountID); err == nil && old.PlusUntil.After(until) {
		e.PlusUntil, e.ProductID = old.PlusUntil, old.ProductID // never shorten an existing grant
	} else if err != nil && !errors.Is(err, ErrNotFound) {
		s.fail(w, r, err)
		return
	}
	if err := s.store.PutEntitlement(ctx, e); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, s.entitlementJSON(&e))
}

func (s *Server) getPlus(w http.ResponseWriter, r *http.Request, c caller) {
	e, err := s.store.GetEntitlement(r.Context(), c.AccountID)
	if errors.Is(err, ErrNotFound) {
		writeJSON(w, http.StatusOK, s.entitlementJSON(nil))
		return
	} else if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, s.entitlementJSON(&e))
}
