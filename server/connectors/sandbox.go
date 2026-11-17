package connectors

import (
	"context"
	"fmt"
	"hash/fnv"
	"net/url"
	"time"
)

// SandboxCode is the callback code the sandbox's "consent screen" hands back.
const SandboxCode = "sandbox-consent"

const sandboxMaxDays = 90

// Sandbox is a deterministic fake provider for development and tests.
// Consent "happens" immediately: Start returns redirectURI with state and code.
// It has two accounts and generates the same transactions for the same dates every time.
type Sandbox struct {
	Now func() time.Time
}

func NewSandbox(now func() time.Time) *Sandbox {
	if now == nil {
		now = time.Now
	}
	return &Sandbox{Now: now}
}

func (s *Sandbox) ID() string { return "sandbox" }

func (s *Sandbox) Info() Info {
	return Info{ID: "sandbox", Name: "Sandbox (test data)", Kind: KindRedirect, Regions: []string{"*"}}
}

func (s *Sandbox) Start(_ context.Context, _ string, redirectURI string) (StartResult, error) {
	u, err := url.Parse(redirectURI)
	if redirectURI == "" || err != nil || u.Scheme == "" {
		return StartResult{}, fmt.Errorf("%w: redirectUri is required", ErrInvalidInput)
	}
	state := randomHex(16)
	q := u.Query()
	q.Set("state", state)
	q.Set("code", SandboxCode)
	u.RawQuery = q.Encode()
	return StartResult{Kind: KindRedirect, URL: u.String(), State: state}, nil
}

func (s *Sandbox) Complete(_ context.Context, _ string, params map[string]string) (Connection, error) {
	if params["code"] != SandboxCode {
		return Connection{}, fmt.Errorf("%w: consent was not granted", ErrInvalidInput)
	}
	exp := s.Now().Add(sandboxMaxDays * 24 * time.Hour).UTC()
	return Connection{ID: "sbx-" + randomHex(8), Institution: "Sandbox Bank", ConsentExpiry: &exp, Secret: "sandbox-secret-" + randomHex(16)}, nil
}

func (s *Sandbox) Accounts(context.Context, Connection) ([]RemoteAccount, error) {
	return []RemoteAccount{
		{ID: "sbx-checking", Name: "Everyday checking", Currency: "USD", Type: "checking", BalanceMinor: 245_067},
		{ID: "sbx-savings", Name: "Savings", Currency: "JPY", Type: "savings", BalanceMinor: 1_250_000},
	}, nil
}

var sandboxPayees = []string{"Corner grocery", "Coffee bar", "Transit card top-up", "Pharmacy", "Bookshop", "Lunch", "Fuel"}

func (s *Sandbox) Transactions(_ context.Context, _ Connection, accountID string, since time.Time) ([]RemoteTx, error) {
	now := s.Now().UTC()
	floor := now.AddDate(0, 0, -sandboxMaxDays)
	if since.Before(floor) {
		since = floor
	}
	var out []RemoteTx
	day := time.Date(since.Year(), since.Month(), since.Day(), 0, 0, 0, 0, time.UTC)
	for ; !day.After(now); day = day.AddDate(0, 0, 1) {
		at := day.Add(12 * time.Hour)
		if at.Before(since) || at.After(now) {
			continue
		}
		stamp := day.Format("20060102")
		switch accountID {
		case "sbx-checking":
			for n := 1; n <= 2; n++ {
				id := fmt.Sprintf("chk-%s-%d", stamp, n)
				h := hash32(id)
				out = append(out, RemoteTx{ID: id, AccountID: accountID, Date: at, Currency: "USD",
					AmountMinor: -int64(100 + h%9900), Description: sandboxPayees[h%uint32(len(sandboxPayees))]})
			}
			if day.YearDay()%14 == 0 {
				out = append(out, RemoteTx{ID: "chk-" + stamp + "-pay", AccountID: accountID, Date: at, Currency: "USD",
					AmountMinor: 250_000, Description: "Salary"})
			}
		case "sbx-savings":
			if day.YearDay()%7 == 0 {
				out = append(out, RemoteTx{ID: "sav-" + stamp, AccountID: accountID, Date: at, Currency: "JPY",
					AmountMinor: 5_000, Description: "Transfer in"})
			}
		default:
			return nil, fmt.Errorf("%w: unknown sandbox account", ErrInvalidInput)
		}
	}
	return out, nil
}

func (s *Sandbox) Disconnect(context.Context, Connection) error { return nil }

func hash32(s string) uint32 {
	h := fnv.New32a()
	h.Write([]byte(s))
	return h.Sum32()
}
