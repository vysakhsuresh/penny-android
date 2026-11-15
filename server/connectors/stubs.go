package connectors

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"
)

// stub is a provider adapter that is registered (so it can be listed and planned for) but not
// implemented yet. Every call returns ErrNotConfigured naming the env vars it will need, and it
// stays disabled even when listed in CONNECTORS.
type stub struct {
	info Info
	env  []string
}

func (s stub) ID() string       { return s.info.ID }
func (s stub) Info() Info       { return s.info }
func (s stub) Configured() bool { return false }

func (s stub) err() error {
	missing := make([]string, 0, len(s.env))
	for _, k := range s.env {
		if os.Getenv(k) == "" {
			missing = append(missing, k)
		}
	}
	if len(missing) == 0 {
		return fmt.Errorf("%w: the %s adapter is not implemented yet", ErrNotConfigured, s.info.ID)
	}
	return fmt.Errorf("%w: %s needs %s", ErrNotConfigured, s.info.ID, strings.Join(missing, ", "))
}

func (s stub) Start(context.Context, string, string) (StartResult, error) {
	return StartResult{}, s.err()
}
func (s stub) Complete(context.Context, string, map[string]string) (Connection, error) {
	return Connection{}, s.err()
}
func (s stub) Accounts(context.Context, Connection) ([]RemoteAccount, error) { return nil, s.err() }
func (s stub) Transactions(context.Context, Connection, string, time.Time) ([]RemoteTx, error) {
	return nil, s.err()
}
func (s stub) Disconnect(context.Context, Connection) error { return s.err() }

func newStub(id, name, kind string, regions []string, env ...string) Connector {
	return stub{info: Info{ID: id, Name: name, Kind: kind, Regions: regions}, env: env}
}

// Plaid will call the Plaid API: /link/token/create, /item/public_token/exchange,
// /accounts/balance/get, /transactions/sync and /item/remove.
// Env: PLAID_CLIENT_ID, PLAID_SECRET, PLAID_ENV (sandbox, production).
func Plaid() Connector {
	return newStub("plaid", "Plaid", KindRedirect, []string{"US", "CA", "GB", "EU"}, "PLAID_CLIENT_ID", "PLAID_SECRET", "PLAID_ENV")
}

// MX will call the MX Platform API: /users, /widget_urls (Connect widget), /members,
// /accounts and /transactions, and DELETE /members on disconnect.
// Env: MX_CLIENT_ID, MX_API_KEY.
func MX() Connector {
	return newStub("mx", "MX", KindRedirect, []string{"US", "CA"}, "MX_CLIENT_ID", "MX_API_KEY")
}

// TrueLayer will call the TrueLayer Data API: auth-link + /connect/token (OAuth),
// /data/v1/accounts, /data/v1/accounts/{id}/balance and /transactions, and
// DELETE /api/delete on disconnect. Env: TRUELAYER_CLIENT_ID, TRUELAYER_CLIENT_SECRET.
func TrueLayer() Connector {
	return newStub("truelayer", "TrueLayer", KindRedirect, []string{"GB", "IE", "EU"}, "TRUELAYER_CLIENT_ID", "TRUELAYER_CLIENT_SECRET")
}

// GoCardless will call the GoCardless Bank Account Data API (formerly Nordigen):
// /token/new, /agreements/enduser, /requisitions, /accounts/{id}/details|balances|transactions,
// and DELETE /requisitions/{id} on disconnect. Env: GOCARDLESS_SECRET_ID, GOCARDLESS_SECRET_KEY.
func GoCardless() Connector {
	return newStub("gocardless", "GoCardless Bank Account Data", KindRedirect, []string{"GB", "EU"}, "GOCARDLESS_SECRET_ID", "GOCARDLESS_SECRET_KEY")
}

// Tink will call the Tink API: /api/v1/oauth/token, Tink Link, /data/v2/accounts and
// /data/v2/transactions, and DELETE the user on disconnect. Env: TINK_CLIENT_ID, TINK_CLIENT_SECRET.
func Tink() Connector {
	return newStub("tink", "Tink", KindRedirect, []string{"EU", "GB"}, "TINK_CLIENT_ID", "TINK_CLIENT_SECRET")
}

// SaltEdge will call the Salt Edge Account Information API v5: /customers, /connect_sessions,
// /accounts, /transactions, and DELETE /connections/{id} on disconnect.
// Env: SALTEDGE_APP_ID, SALTEDGE_SECRET.
func SaltEdge() Connector {
	return newStub("saltedge", "Salt Edge", KindRedirect, []string{"EU", "GB"}, "SALTEDGE_APP_ID", "SALTEDGE_SECRET")
}

// Basiq will call the Basiq API (Australian CDR): /token, /users, consent UI, /users/{id}/accounts
// and /users/{id}/transactions, and DELETE /users/{id}/consents on disconnect.
// Env: BASIQ_API_KEY.
func Basiq() Connector {
	return newStub("basiq", "Basiq", KindRedirect, []string{"AU"}, "BASIQ_API_KEY")
}

// Akahu will call the Akahu API (New Zealand): OAuth /token, /accounts, /transactions, and
// DELETE /token on disconnect. Env: AKAHU_APP_ID, AKAHU_APP_SECRET.
func Akahu() Connector {
	return newStub("akahu", "Akahu", KindRedirect, []string{"NZ"}, "AKAHU_APP_ID", "AKAHU_APP_SECRET")
}

// Belvo will call the Belvo API (LATAM): widget access token, /api/links, /api/accounts,
// /api/transactions, and DELETE /api/links/{id} on disconnect. Env: BELVO_SECRET_ID, BELVO_SECRET_PASSWORD.
func Belvo() Connector {
	return newStub("belvo", "Belvo", KindRedirect, []string{"MX", "CO", "BR"}, "BELVO_SECRET_ID", "BELVO_SECRET_PASSWORD")
}

// Pluggy will call the Pluggy API (Open Finance Brazil): /auth, /connect_token, /items,
// /accounts, /transactions, and DELETE /items/{id} on disconnect. Env: PLUGGY_CLIENT_ID, PLUGGY_CLIENT_SECRET.
func Pluggy() Connector {
	return newStub("pluggy", "Pluggy", KindRedirect, []string{"BR"}, "PLUGGY_CLIENT_ID", "PLUGGY_CLIENT_SECRET")
}

// Mono will call the Mono API (Africa): Mono Connect widget, /v2/accounts/auth (code exchange),
// /v2/accounts/{id}, /v2/accounts/{id}/transactions, and /v2/accounts/{id}/unlink on disconnect.
// Env: MONO_SECRET_KEY.
func Mono() Connector {
	return newStub("mono", "Mono", KindRedirect, []string{"NG", "GH", "KE"}, "MONO_SECRET_KEY")
}

// Okra will call the Okra API (Africa): Okra widget, /accounts/getById, /transactions/getByCustomer,
// and /customers/deactivate on disconnect. Env: OKRA_CLIENT_TOKEN, OKRA_SECRET_KEY.
func Okra() Connector {
	return newStub("okra", "Okra", KindRedirect, []string{"NG"}, "OKRA_CLIENT_TOKEN", "OKRA_SECRET_KEY")
}

// Stitch will call the Stitch GraphQL API (South Africa): OAuth authorization code + PKCE at
// secure.stitch.money, bankAccounts and transactions queries, and token revocation on disconnect.
// Env: STITCH_CLIENT_ID, STITCH_CLIENT_SECRET.
func Stitch() Connector {
	return newStub("stitch", "Stitch", KindRedirect, []string{"ZA"}, "STITCH_CLIENT_ID", "STITCH_CLIENT_SECRET")
}

// Brankas will call the Brankas Statement API (Southeast Asia): /statement-init, /statements
// and /statements/{id}/transactions. Env: BRANKAS_API_KEY.
func Brankas() Connector {
	return newStub("brankas", "Brankas", KindRedirect, []string{"PH", "ID", "TH"}, "BRANKAS_API_KEY")
}

// Finverse will call the Finverse API (Southeast Asia): /auth/customer/token, link token,
// /auth/token (code exchange), /accounts, /transactions, and DELETE /login_identity on disconnect.
// Env: FINVERSE_CLIENT_ID, FINVERSE_CLIENT_SECRET.
func Finverse() Connector {
	return newStub("finverse", "Finverse", KindRedirect, []string{"SG", "HK", "PH", "VN", "ID"}, "FINVERSE_CLIENT_ID", "FINVERSE_CLIENT_SECRET")
}

// AASetu will call Setu's Account Aggregator FIU APIs (India, Sahamati AA framework):
// POST /consents, GET /consents/{id}, POST /sessions (FI data request), GET /sessions/{id},
// and consent revocation on disconnect. Env: SETU_CLIENT_ID, SETU_CLIENT_SECRET, SETU_PRODUCT_INSTANCE_ID.
func AASetu() Connector {
	return newStub("aa_setu", "Account Aggregator (Setu)", KindRedirect, []string{"IN"}, "SETU_CLIENT_ID", "SETU_CLIENT_SECRET", "SETU_PRODUCT_INSTANCE_ID")
}

// SGFinDex will call the Singapore Financial Data Exchange via an approved MyInfo/SGFinDex
// partner: Singpass OAuth authorize + token, then the partner's financial-data endpoints.
// Env: SGFINDEX_CLIENT_ID, SGFINDEX_CLIENT_SECRET, SGFINDEX_PRIVATE_KEY.
func SGFinDex() Connector {
	return newStub("sgfindex", "SGFinDex", KindRedirect, []string{"SG"}, "SGFINDEX_CLIENT_ID", "SGFINDEX_CLIENT_SECRET", "SGFINDEX_PRIVATE_KEY")
}

// Stubs returns every not-yet-implemented adapter.
func Stubs() []Connector {
	return []Connector{Plaid(), MX(), TrueLayer(), GoCardless(), Tink(), SaltEdge(), Basiq(), Akahu(),
		Belvo(), Pluggy(), Mono(), Okra(), Stitch(), Brankas(), Finverse(), AASetu(), SGFinDex()}
}

// All returns the sandbox, SimpleFIN and every stub, ready for NewRegistry.
func All(now func() time.Time) []Connector {
	return append([]Connector{NewSandbox(now), NewSimpleFIN()}, Stubs()...)
}
