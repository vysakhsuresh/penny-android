// Package connectors adapts regional bank-data providers (open banking, account aggregators,
// SimpleFIN) to one interface. Adapters run on the server only: provider credentials never
// reach the client, and adapters return normalised data without persisting any of it.
package connectors

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"
)

// Connection kinds: how a person grants access.
const (
	// KindRedirect: the app opens StartResult.URL (provider consent screen), the provider
	// redirects back to the app with query parameters, and the app posts them to Complete.
	KindRedirect = "redirect"
	// KindToken: the person pastes a token (for example a SimpleFIN Setup Token) and the
	// connection completes in one call.
	KindToken = "token"
)

// Errors adapters return. Anything else is treated as a provider (upstream) failure.
var (
	// ErrNotConfigured: the adapter exists but its credentials or partner agreement are missing.
	ErrNotConfigured = errors.New("connector not configured")
	// ErrInvalidInput: the token, state or callback parameters are malformed or rejected.
	ErrInvalidInput = errors.New("invalid connector input")
	// ErrConsentRequired: consent expired or the institution needs the person to re-authenticate.
	ErrConsentRequired = errors.New("consent required")
	// ErrRevoked: the person or provider revoked access; stored credentials are useless.
	ErrRevoked = errors.New("access revoked")
)

// Info describes an adapter for listing.
type Info struct {
	ID      string
	Name    string
	Kind    string   // KindRedirect or KindToken
	Regions []string // ISO 3166-1 alpha-2 codes, "EU" for the EEA, "*" for anywhere
}

// StartResult is returned by Start. For KindToken, URL and State are empty.
type StartResult struct {
	Kind  string
	URL   string
	State string
}

// Connection is a completed provider connection. Secret holds whatever the adapter needs to
// call the provider again (access token, refresh token, access URL). The server seals it
// before storing it and never returns it to clients.
type Connection struct {
	ID            string // provider-side reference (item id, requisition id, ...)
	Institution   string
	ConsentExpiry *time.Time
	Secret        string
}

// RemoteAccount is an account at the institution. Balance is integer minor units.
type RemoteAccount struct {
	ID           string
	Name         string
	Currency     string
	Type         string // checking, savings, credit, loan, investment, or "" when unknown
	BalanceMinor int64
}

// RemoteTx is a normalised transaction. AmountMinor is signed integer minor units of
// Currency: negative is money out, positive is money in.
type RemoteTx struct {
	ID          string
	AccountID   string
	Date        time.Time
	AmountMinor int64
	Currency    string
	Description string
	Pending     bool
}

// Connector is one adapter per provider.
type Connector interface {
	ID() string
	Info() Info
	// Start begins a connection for the Penny account accountID. redirectURI is where a
	// KindRedirect provider sends the person back to.
	Start(ctx context.Context, accountID, redirectURI string) (StartResult, error)
	// Complete finishes it. params are the callback query parameters (KindRedirect) or
	// {"token": ...} (KindToken). The server adds "redirect_uri" and "state" when it has them.
	Complete(ctx context.Context, accountID string, params map[string]string) (Connection, error)
	Accounts(ctx context.Context, conn Connection) ([]RemoteAccount, error)
	// Transactions returns the account's transactions posted on or after since.
	Transactions(ctx context.Context, conn Connection, accountID string, since time.Time) ([]RemoteTx, error)
	// Disconnect revokes access at the provider where the provider supports it.
	Disconnect(ctx context.Context, conn Connection) error
}

// configurable is implemented by adapters that can be present but unusable (stubs).
type configurable interface {
	Configured() bool
}

// Registry holds every known adapter and which of them are enabled.
type Registry struct {
	byID    map[string]Connector
	order   []string
	enabled map[string]bool
}

// NewRegistry registers cs and enables the ids in enabled. It fails on an unknown or
// duplicate id. An enabled adapter that reports Configured() == false stays disabled; the
// returned list names those so the caller can warn.
func NewRegistry(enabled []string, cs ...Connector) (*Registry, []string, error) {
	r := &Registry{byID: map[string]Connector{}, enabled: map[string]bool{}}
	for _, c := range cs {
		if _, dup := r.byID[c.ID()]; dup {
			return nil, nil, fmt.Errorf("duplicate connector %q", c.ID())
		}
		r.byID[c.ID()] = c
		r.order = append(r.order, c.ID())
	}
	var unconfigured []string
	for _, id := range enabled {
		c, ok := r.byID[id]
		if !ok {
			return nil, nil, fmt.Errorf("unknown connector %q", id)
		}
		if cf, ok := c.(configurable); ok && !cf.Configured() {
			unconfigured = append(unconfigured, id)
			continue
		}
		r.enabled[id] = true
	}
	return r, unconfigured, nil
}

// ParseList splits a comma-separated id list, trimming blanks.
func ParseList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// Get returns an enabled adapter.
func (r *Registry) Get(id string) (Connector, bool) {
	if r == nil || !r.enabled[id] {
		return nil, false
	}
	return r.byID[id], true
}

// Listed is an adapter's Info with its enabled flag.
type Listed struct {
	Info
	Enabled bool
}

// List returns every registered adapter, enabled ones first, then by id.
func (r *Registry) List() []Listed {
	if r == nil {
		return nil
	}
	out := make([]Listed, 0, len(r.order))
	for _, id := range r.order {
		out = append(out, Listed{Info: r.byID[id].Info(), Enabled: r.enabled[id]})
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Enabled != out[j].Enabled {
			return out[i].Enabled
		}
		return out[i].ID < out[j].ID
	})
	return out
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}
