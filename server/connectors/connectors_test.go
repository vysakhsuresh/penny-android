package connectors

import (
	"context"
	"encoding/base64"
	"errors"
	"net"
	"testing"
	"time"
)

func TestParseMinor(t *testing.T) {
	cases := []struct {
		in, cur string
		want    int64
	}{
		{"-12.34", "USD", -1234},
		{"1000", "USD", 100000},
		{"1000", "EUR", 100000},
		{"0.1", "USD", 10},
		{".5", "USD", 50},
		{"+7.", "USD", 700},
		{" 42.00 ", "GBP", 4200},
		{"-0.00", "USD", 0},
		{"1500", "JPY", 1500},
		{"-1500", "jpy", -1500},
		{"1500.0", "JPY", 1500},
		{"12.345", "KWD", 12345},
		{"-0.5", "KWD", -500},
		{"1.23456", "CLF", 12346},
		{"123456789.12", "INR", 12345678912},
		{"92233720368547758.07", "USD", 9223372036854775807},
		// Half-even rounding of excess precision.
		{"1.005", "USD", 100},
		{"1.015", "USD", 102},
		{"1.0051", "USD", 101},
		{"-2.675", "USD", -268},
		{"0.5", "JPY", 0},
		{"1.5", "JPY", 2},
	}
	for _, c := range cases {
		got, err := ParseMinor(c.in, c.cur)
		if err != nil || got != c.want {
			t.Errorf("ParseMinor(%q, %q) = %d, %v; want %d", c.in, c.cur, got, err, c.want)
		}
	}
	for _, bad := range []string{"", "-", ".", "1,000.00", "1e3", "12.3.4", "abc", "--1", "92233720368547758.08", " - 1"} {
		if v, err := ParseMinor(bad, "USD"); err == nil {
			t.Errorf("ParseMinor(%q) = %d, want error", bad, v)
		}
	}
}

func TestMinorDigits(t *testing.T) {
	for cur, want := range map[string]int{"USD": 2, "JPY": 0, "KWD": 3, "INR": 2, "BHD": 3, "KRW": 0, "XYZ": 2, "": 2} {
		if got := MinorDigits(cur); got != want {
			t.Errorf("MinorDigits(%q) = %d, want %d", cur, got, want)
		}
	}
}

func TestRegistry(t *testing.T) {
	r, unconfigured, err := NewRegistry([]string{"sandbox", "plaid"}, All(time.Now)...)
	if err != nil {
		t.Fatal(err)
	}
	if len(unconfigured) != 1 || unconfigured[0] != "plaid" {
		t.Fatalf("unconfigured = %v", unconfigured)
	}
	if _, ok := r.Get("sandbox"); !ok {
		t.Fatal("sandbox should be enabled")
	}
	if _, ok := r.Get("plaid"); ok {
		t.Fatal("a stub must stay disabled")
	}
	if _, ok := r.Get("simplefin"); ok {
		t.Fatal("simplefin was not enabled")
	}
	ls := r.List()
	if len(ls) != 19 || ls[0].ID != "sandbox" || !ls[0].Enabled || ls[1].Enabled {
		t.Fatalf("list = %+v", ls)
	}
	if _, _, err := NewRegistry([]string{"nope"}, All(time.Now)...); err == nil {
		t.Fatal("unknown id must fail")
	}
	if got := ParseList(" sandbox, ,simplefin "); len(got) != 2 || got[1] != "simplefin" {
		t.Fatalf("ParseList = %v", got)
	}
}

func TestStubsReturnNotConfigured(t *testing.T) {
	ctx := context.Background()
	for _, c := range Stubs() {
		if _, err := c.Start(ctx, "a", "app://cb"); !errors.Is(err, ErrNotConfigured) {
			t.Errorf("%s Start: %v", c.ID(), err)
		}
		if _, err := c.Transactions(ctx, Connection{}, "x", time.Now()); !errors.Is(err, ErrNotConfigured) {
			t.Errorf("%s Transactions: %v", c.ID(), err)
		}
		if c.Info().Kind == "" || len(c.Info().Regions) == 0 || c.Info().Name == "" {
			t.Errorf("%s info incomplete", c.ID())
		}
	}
}

func TestSimpleFINRejectsUnsafeClaimURLs(t *testing.T) {
	s := NewSimpleFIN()
	for _, raw := range []string{"http://bridge.example/claim/x", "https://user:pw@bridge.example/claim", "not a url", "ftp://x/y"} {
		tok := base64.StdEncoding.EncodeToString([]byte(raw))
		if _, err := s.Complete(context.Background(), "a", map[string]string{"token": tok}); !errors.Is(err, ErrInvalidInput) {
			t.Errorf("%q: err = %v, want ErrInvalidInput", raw, err)
		}
	}
	if _, err := s.Complete(context.Background(), "a", map[string]string{"token": "%%%"}); !errors.Is(err, ErrInvalidInput) {
		t.Errorf("non-base64 token: %v", err)
	}
	// The dialer refuses non-public addresses even for https URLs.
	tok := base64.StdEncoding.EncodeToString([]byte("https://127.0.0.1:1/claim/x"))
	if _, err := s.Complete(context.Background(), "a", map[string]string{"token": tok}); err == nil {
		t.Error("loopback claim URL must fail")
	}
	for ip, public := range map[string]bool{"8.8.8.8": true, "127.0.0.1": false, "10.1.2.3": false, "192.168.0.1": false,
		"169.254.169.254": false, "100.64.0.1": false, "::1": false, "fd00::1": false, "2606:4700::1111": true, "0.0.0.0": false} {
		if got := isPublicIP(net.ParseIP(ip)); got != public {
			t.Errorf("isPublicIP(%s) = %v", ip, got)
		}
	}
}

func TestSandboxIsDeterministic(t *testing.T) {
	now := time.Date(2026, 3, 29, 15, 0, 0, 0, time.UTC)
	s := NewSandbox(func() time.Time { return now })
	since := now.AddDate(0, 0, -30)
	a, _ := s.Transactions(context.Background(), Connection{}, "sbx-checking", since)
	b, _ := s.Transactions(context.Background(), Connection{}, "sbx-checking", since)
	if len(a) < 60 || len(a) != len(b) {
		t.Fatalf("len %d vs %d", len(a), len(b))
	}
	for i := range a {
		if a[i] != b[i] {
			t.Fatalf("tx %d differs", i)
		}
		if a[i].Date.Before(since) || a[i].Date.After(now) {
			t.Fatalf("tx %d out of range: %v", i, a[i].Date)
		}
	}
}
