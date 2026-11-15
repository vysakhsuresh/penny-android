package connectors

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	sfMaxBody     = 16 << 20
	sfMaxTokenLen = 4096
)

// SimpleFIN implements the SimpleFIN Bridge protocol (https://www.simplefin.org/protocol.html).
// The person creates a Setup Token at their bridge and pastes it into the app. The server
// base64-decodes it to a claim URL, POSTs to claim it, and receives an Access URL carrying
// HTTP basic-auth credentials. The Access URL is the connection secret: it is sealed at rest
// and never returned to the client. No env vars are needed.
type SimpleFIN struct {
	Client *http.Client
	// AllowInsecure permits http:// URLs and private or loopback addresses. Tests only.
	AllowInsecure bool
	Now           func() time.Time
}

// NewSimpleFIN returns the adapter with an HTTP client that only dials public addresses,
// because the claim URL comes from user input.
func NewSimpleFIN() *SimpleFIN {
	dialer := &net.Dialer{Timeout: 10 * time.Second, Control: publicOnly}
	tr := &http.Transport{
		Proxy:                 nil,
		DialContext:           dialer.DialContext,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 45 * time.Second,
		MaxIdleConns:          10,
		IdleConnTimeout:       90 * time.Second,
	}
	return &SimpleFIN{Client: &http.Client{Transport: tr, Timeout: 60 * time.Second, CheckRedirect: noRedirects}, Now: time.Now}
}

func noRedirects(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }

// publicOnly refuses connections to loopback, private, link-local, CGNAT and unspecified addresses.
func publicOnly(_, address string, _ syscall.RawConn) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}
	ip := net.ParseIP(host)
	if ip == nil || !isPublicIP(ip) {
		return fmt.Errorf("refusing to connect to non-public address %s", host)
	}
	return nil
}

var cgnat = &net.IPNet{IP: net.IPv4(100, 64, 0, 0), Mask: net.CIDRMask(10, 32)}

func isPublicIP(ip net.IP) bool {
	return !(ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() ||
		ip.IsInterfaceLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() || cgnat.Contains(ip))
}

func (s *SimpleFIN) ID() string { return "simplefin" }

func (s *SimpleFIN) Info() Info {
	return Info{ID: "simplefin", Name: "SimpleFIN Bridge", Kind: KindToken, Regions: []string{"US", "CA"}}
}

func (s *SimpleFIN) Start(context.Context, string, string) (StartResult, error) {
	return StartResult{Kind: KindToken}, nil
}

// Complete claims the Setup Token in params["token"] and validates the Access URL by fetching
// balances once (which also yields the institution name).
func (s *SimpleFIN) Complete(ctx context.Context, _ string, params map[string]string) (Connection, error) {
	claimURL, err := s.decodeSetupToken(params["token"])
	if err != nil {
		return Connection{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, claimURL, nil)
	if err != nil {
		return Connection{}, fmt.Errorf("%w: bad claim URL", ErrInvalidInput)
	}
	req.ContentLength = 0
	resp, err := s.Client.Do(req)
	if err != nil {
		return Connection{}, fmt.Errorf("simplefin claim: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, sfMaxTokenLen+1))
	if err != nil {
		return Connection{}, fmt.Errorf("simplefin claim: %w", err)
	}
	switch {
	case resp.StatusCode == http.StatusForbidden:
		return Connection{}, fmt.Errorf("%w: setup token was already claimed or is not valid", ErrInvalidInput)
	case resp.StatusCode != http.StatusOK:
		return Connection{}, fmt.Errorf("simplefin claim: status %d", resp.StatusCode)
	case len(body) > sfMaxTokenLen:
		return Connection{}, errors.New("simplefin claim: access URL too long")
	}
	access := strings.TrimSpace(string(body))
	if _, _, err := s.splitAccess(access); err != nil {
		return Connection{}, err
	}
	conn := Connection{ID: "sfin-" + randomHex(8), Secret: access}
	accts, err := s.fetch(ctx, conn, url.Values{"balances-only": {"1"}})
	if err != nil {
		return Connection{}, err
	}
	conn.Institution = institutionName(accts)
	return conn, nil
}

func (s *SimpleFIN) decodeSetupToken(tok string) (string, error) {
	tok = strings.Join(strings.Fields(tok), "")
	if tok == "" || len(tok) > sfMaxTokenLen {
		return "", fmt.Errorf("%w: setup token is required", ErrInvalidInput)
	}
	var raw []byte
	var err error
	for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
		if raw, err = enc.DecodeString(tok); err == nil {
			break
		}
	}
	if err != nil {
		return "", fmt.Errorf("%w: setup token is not base64", ErrInvalidInput)
	}
	u, err := url.Parse(strings.TrimSpace(string(raw)))
	if err != nil || u.Host == "" || u.User != nil || !s.schemeOK(u.Scheme) {
		return "", fmt.Errorf("%w: setup token does not contain a valid claim URL", ErrInvalidInput)
	}
	return u.String(), nil
}

func (s *SimpleFIN) schemeOK(scheme string) bool {
	return scheme == "https" || (s.AllowInsecure && scheme == "http")
}

// splitAccess separates the Access URL into a credential-free base URL and its basic auth.
func (s *SimpleFIN) splitAccess(access string) (*url.URL, *url.Userinfo, error) {
	u, err := url.Parse(access)
	if err != nil || u.Host == "" || u.User == nil || !s.schemeOK(u.Scheme) {
		return nil, nil, errors.New("simplefin: bridge returned an invalid access URL")
	}
	user := u.User
	u.User = nil
	return u, user, nil
}

type sfResponse struct {
	Errors   []string    `json:"errors"`
	Accounts []sfAccount `json:"accounts"`
}

type sfAccount struct {
	Org struct {
		Name   string `json:"name"`
		Domain string `json:"domain"`
	} `json:"org"`
	ID           string          `json:"id"`
	Name         string          `json:"name"`
	Currency     string          `json:"currency"`
	Balance      json.RawMessage `json:"balance"`
	Transactions []sfTx          `json:"transactions"`
}

type sfTx struct {
	ID           string          `json:"id"`
	Posted       int64           `json:"posted"`
	TransactedAt int64           `json:"transacted_at"`
	Amount       json.RawMessage `json:"amount"`
	Description  string          `json:"description"`
	Payee        string          `json:"payee"`
	Pending      bool            `json:"pending"`
}

// decimalString accepts a JSON string ("12.34") or a bare JSON number (12.34) as its literal
// text, so no value ever passes through float64.
func decimalString(raw json.RawMessage) (string, error) {
	t := strings.TrimSpace(string(raw))
	if strings.HasPrefix(t, `"`) {
		var s string
		if err := json.Unmarshal(raw, &s); err != nil {
			return "", err
		}
		return s, nil
	}
	if t == "" || t == "null" {
		return "", errors.New("missing amount")
	}
	return t, nil
}

func (s *SimpleFIN) fetch(ctx context.Context, conn Connection, q url.Values) ([]sfAccount, error) {
	base, user, err := s.splitAccess(conn.Secret)
	if err != nil {
		return nil, err
	}
	base.Path = strings.TrimRight(base.Path, "/") + "/accounts"
	base.RawQuery = q.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base.String(), nil)
	if err != nil {
		return nil, err
	}
	pw, _ := user.Password()
	req.SetBasicAuth(user.Username(), pw)
	req.Header.Set("Accept", "application/json")
	resp, err := s.Client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("simplefin accounts: %w", err)
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusOK:
	case http.StatusForbidden, http.StatusUnauthorized:
		return nil, fmt.Errorf("%w: the bridge no longer accepts this access", ErrRevoked)
	case http.StatusPaymentRequired:
		return nil, fmt.Errorf("%w: the bridge subscription needs attention", ErrConsentRequired)
	default:
		return nil, fmt.Errorf("simplefin accounts: status %d", resp.StatusCode)
	}
	var out sfResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, sfMaxBody)).Decode(&out); err != nil {
		return nil, fmt.Errorf("simplefin accounts: %w", err)
	}
	if len(out.Accounts) == 0 && len(out.Errors) > 0 {
		return nil, fmt.Errorf("simplefin: %s", strings.Join(out.Errors, "; "))
	}
	return out.Accounts, nil
}

func institutionName(accts []sfAccount) string {
	seen := map[string]bool{}
	var names []string
	for _, a := range accts {
		n := a.Org.Name
		if n == "" {
			n = a.Org.Domain
		}
		if n != "" && !seen[n] {
			seen[n] = true
			names = append(names, n)
		}
	}
	sort.Strings(names)
	if len(names) == 0 {
		return "SimpleFIN"
	}
	return strings.Join(names, ", ")
}

func (s *SimpleFIN) Accounts(ctx context.Context, conn Connection) ([]RemoteAccount, error) {
	accts, err := s.fetch(ctx, conn, url.Values{"balances-only": {"1"}})
	if err != nil {
		return nil, err
	}
	out := make([]RemoteAccount, 0, len(accts))
	for _, a := range accts {
		bal := int64(0)
		if len(a.Balance) > 0 {
			str, err := decimalString(a.Balance)
			if err != nil {
				return nil, fmt.Errorf("simplefin account balance: %w", err)
			}
			if bal, err = ParseMinor(str, a.Currency); err != nil {
				return nil, err
			}
		}
		out = append(out, RemoteAccount{ID: a.ID, Name: a.Name, Currency: a.Currency, BalanceMinor: bal})
	}
	return out, nil
}

func (s *SimpleFIN) Transactions(ctx context.Context, conn Connection, accountID string, since time.Time) ([]RemoteTx, error) {
	q := url.Values{"start-date": {strconv.FormatInt(since.Unix(), 10)}, "pending": {"1"}, "account": {accountID}}
	accts, err := s.fetch(ctx, conn, q)
	if err != nil {
		return nil, err
	}
	now := time.Now
	if s.Now != nil {
		now = s.Now
	}
	var out []RemoteTx
	for _, a := range accts {
		if a.ID != accountID {
			continue
		}
		for _, t := range a.Transactions {
			str, err := decimalString(t.Amount)
			if err != nil {
				return nil, fmt.Errorf("simplefin transaction %s: %w", t.ID, err)
			}
			amt, err := ParseMinor(str, a.Currency)
			if err != nil {
				return nil, fmt.Errorf("simplefin transaction %s: %w", t.ID, err)
			}
			ts := t.Posted
			if ts == 0 {
				ts = t.TransactedAt
			}
			date := now().UTC()
			if ts > 0 {
				date = time.Unix(ts, 0).UTC()
			}
			if date.Before(since) {
				continue
			}
			desc := t.Description
			if desc == "" {
				desc = t.Payee
			}
			out = append(out, RemoteTx{ID: t.ID, AccountID: a.ID, Date: date, AmountMinor: amt, Currency: a.Currency,
				Description: desc, Pending: t.Pending || t.Posted == 0})
		}
	}
	return out, nil
}

// Disconnect has nothing to call: the SimpleFIN protocol has no revoke endpoint. Deleting the
// sealed Access URL makes it unusable to Penny; the person can also revoke it at their bridge.
func (s *SimpleFIN) Disconnect(context.Context, Connection) error { return nil }
