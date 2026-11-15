package main

import (
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestNormalizeDescriptor(t *testing.T) {
	cases := map[string]string{
		"POS 4471 STARBUCKS #123":            "starbucks 123",
		"UPI/SWIGGY/123456789/ref":           "swiggy",
		"  Amazon   Mktp  US*2K3LQ ":         "amazon mktp us 2k3lq",
		"McDonald's 045":                     "mcdonalds 045",
		"7-ELEVEN 12345":                     "7 eleven",
		"TXN H&M STORE":                      "h&m store",
		"SQ *BLUE BOTTLE":                    "sq blue bottle",
		"ACH  NETFLIX.COM":                   "netflix com",
		"पेटीएम 99999":                       "पेटीएम",
		"1234567":                            "",
		"Café Nero":                          "café nero",
		"UPI-ZOMATO-ZOMATOONLINE@HDFC-98765": "zomato zomatoonline hdfc",
	}
	for in, want := range cases {
		if got := normalizeDescriptor(in); got != want {
			t.Errorf("normalizeDescriptor(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestLooksPersonal(t *testing.T) {
	personal := []string{"pay john@example.com", "+1 (555) 123-4567", "4111 1111 1111 1111", "ACCT 12345678",
		"GB29NWBK60161331926819", "de89370400440532013000"}
	fine := []string{"STARBUCKS 4471", "7-ELEVEN", "Amazon Mktp US", "Store 1234567", "IN12 cafe"}
	for _, s := range personal {
		if !looksPersonal(s) {
			t.Errorf("looksPersonal(%q) = false", s)
		}
	}
	for _, s := range fine {
		if looksPersonal(s) {
			t.Errorf("looksPersonal(%q) = true", s)
		}
	}
}

func (e *testEnv) contribute(tok, region string, items ...merchantItem) resp {
	e.t.Helper()
	return e.do("POST", "/v1/merchants/contribute", tok, merchantContributeReq{Region: region, Items: items})
}

func (e *testEnv) suggest(tok, region string, descs ...string) []merchantSuggestion {
	e.t.Helper()
	r := e.do("POST", "/v1/merchants/suggest", tok, merchantSuggestReq{Region: region, Descriptors: descs})
	expect(e.t, r, http.StatusOK)
	var out []merchantSuggestion
	r.decode(e.t, &out)
	return out
}

func TestMerchantKAnonymity(t *testing.T) {
	e := newEnv(t)
	var toks []string
	for i := range 4 {
		toks = append(toks, e.signIn(fmt.Sprintf("m%d@example.com", i)).Token)
	}
	item := merchantItem{Descriptor: "POS 9911 STARBUCKS", Name: "Starbucks", CategoryKey: "food.coffee"}

	for _, tok := range toks[:2] {
		r := e.contribute(tok, "IN", item)
		expect(t, r, http.StatusOK)
		var ok merchantContributeResp
		r.decode(t, &ok)
		if ok.Accepted != 1 {
			t.Fatalf("accepted = %d", ok.Accepted)
		}
	}
	// The same account voting again does not count twice.
	expect(t, e.contribute(toks[0], "in", item), http.StatusOK)
	if got := e.suggest(toks[3], "in", "starbucks 9911"); len(got) != 0 {
		t.Fatalf("2 accounts: got %+v, want nothing", got)
	}

	expect(t, e.contribute(toks[2], "in", merchantItem{Descriptor: "starbucks  POS", Name: "STARBUCKS", CategoryKey: "food.coffee"}), http.StatusOK)
	got := e.suggest(toks[3], "IN", "UPI STARBUCKS 123456", "unknown shop", "STARBUCKS")
	if len(got) != 2 {
		t.Fatalf("3 accounts: got %+v", got)
	}
	want := merchantSuggestion{Descriptor: "UPI STARBUCKS 123456", Name: "STARBUCKS", CategoryKey: "food.coffee", Confidence: 1}
	if got[0] != want || got[1].Descriptor != "STARBUCKS" {
		t.Fatalf("got %+v, want %+v", got, want)
	}
	// Regions are separate maps.
	if got := e.suggest(toks[3], "us", "STARBUCKS"); len(got) != 0 {
		t.Fatalf("other region: %+v", got)
	}
}

func TestMerchantMajorityRule(t *testing.T) {
	e := newEnv(t)
	var toks []string
	for i := range 7 {
		toks = append(toks, e.signIn(fmt.Sprintf("v%d@example.com", i)).Token)
	}
	a := merchantItem{Descriptor: "ZOMATO", Name: "Zomato", CategoryKey: "food.delivery"}
	b := merchantItem{Descriptor: "ZOMATO", Name: "Zomato", CategoryKey: "food.restaurant"}
	for _, tok := range toks[:3] {
		expect(t, e.contribute(tok, "in", a), http.StatusOK)
	}
	for _, tok := range toks[3:5] {
		expect(t, e.contribute(tok, "in", b), http.StatusOK)
	}
	got := e.suggest(toks[6], "in", "zomato")
	if len(got) != 1 || got[0].CategoryKey != "food.delivery" || got[0].Confidence != 0.6 {
		t.Fatalf("3 of 5 (60%%): %+v", got)
	}
	expect(t, e.contribute(toks[5], "in", b), http.StatusOK)
	if got := e.suggest(toks[6], "in", "zomato"); len(got) != 0 {
		t.Fatalf("3 of 6 (50%%): %+v", got)
	}
	// A correction replaces the contributor's earlier vote: 4 of 6 for a.
	expect(t, e.contribute(toks[5], "in", a), http.StatusOK)
	got = e.suggest(toks[6], "in", "zomato")
	if len(got) != 1 || got[0].CategoryKey != "food.delivery" || got[0].Confidence != 0.67 {
		t.Fatalf("after correction: %+v", got)
	}
}

func TestMerchantValidation(t *testing.T) {
	e := newEnv(t)
	tok := e.signIn("val@example.com").Token
	ok := merchantItem{Descriptor: "TESCO STORES", Name: "Tesco", CategoryKey: "groceries"}
	bad := map[string]merchantContributeReq{
		"region":         {Region: "GBR", Items: []merchantItem{ok}},
		"no region":      {Items: []merchantItem{ok}},
		"no items":       {Region: "gb"},
		"long desc":      {Region: "gb", Items: []merchantItem{{Descriptor: strings.Repeat("a", 65), Name: "A", CategoryKey: "shops"}}},
		"empty desc":     {Region: "gb", Items: []merchantItem{{Descriptor: "POS 123456", Name: "A", CategoryKey: "shops"}}},
		"long name":      {Region: "gb", Items: []merchantItem{{Descriptor: "TESCO", Name: strings.Repeat("n", 49), CategoryKey: "shops"}}},
		"empty name":     {Region: "gb", Items: []merchantItem{{Descriptor: "TESCO", Name: "   ", CategoryKey: "shops"}}},
		"category caps":  {Region: "gb", Items: []merchantItem{{Descriptor: "TESCO", Name: "Tesco", CategoryKey: "Groceries"}}},
		"category depth": {Region: "gb", Items: []merchantItem{{Descriptor: "TESCO", Name: "Tesco", CategoryKey: "a.b.c"}}},
		"email":          {Region: "gb", Items: []merchantItem{ok, {Descriptor: "PAYPAL jane.doe@mail.com", Name: "PayPal", CategoryKey: "shops"}}},
		"phone in name":  {Region: "gb", Items: []merchantItem{{Descriptor: "TAXI", Name: "Call 0207 946 0018", CategoryKey: "transport"}}},
		"account number": {Region: "gb", Items: []merchantItem{{Descriptor: "TFR 12345678 SAVINGS", Name: "Transfer", CategoryKey: "transfer"}}},
		"iban":           {Region: "gb", Items: []merchantItem{{Descriptor: "SEPA GB29NWBK60161331926819", Name: "Rent", CategoryKey: "housing"}}},
	}
	for name, req := range bad {
		if r := e.do("POST", "/v1/merchants/contribute", tok, req); r.status != http.StatusBadRequest {
			t.Errorf("%s: status %d, want 400 (%s)", name, r.status, r.body)
		}
	}
	many := make([]merchantItem, 101)
	for i := range many {
		many[i] = ok
	}
	expect(t, e.contribute(tok, "gb", many...), http.StatusBadRequest)
	tooManyDescs := make([]string, 201)
	expect(t, e.do("POST", "/v1/merchants/suggest", tok, merchantSuggestReq{Region: "gb", Descriptors: tooManyDescs}), http.StatusBadRequest)
	expect(t, e.do("POST", "/v1/merchants/suggest", tok, merchantSuggestReq{Region: "g1", Descriptors: []string{"x"}}), http.StatusBadRequest)
	if got := e.suggest(tok, "gb"); len(got) != 0 {
		t.Fatalf("empty suggest = %+v", got)
	}
	expect(t, e.contribute(tok, "gb", ok), http.StatusOK)
	expect(t, e.do("POST", "/v1/merchants/contribute", "", merchantContributeReq{Region: "gb", Items: []merchantItem{ok}}), http.StatusUnauthorized)
}

func TestMerchantContributeRateLimit(t *testing.T) {
	e := newEnv(t)
	tok := e.signIn("rl@example.com").Token
	other := e.signIn("rl2@example.com").Token
	batch := func(prefix string, n int) []merchantItem {
		items := make([]merchantItem, n)
		for i := range items {
			items[i] = merchantItem{Descriptor: fmt.Sprintf("%s shop %c%c", prefix, 'a'+i/26, 'a'+i%26), Name: "Shop", CategoryKey: "shops"}
		}
		return items
	}
	expect(t, e.contribute(tok, "us", batch("x", 60)...), http.StatusOK)
	expect(t, e.contribute(tok, "us", batch("y", 40)...), http.StatusOK)
	r := e.contribute(tok, "us", batch("z", 1)...)
	expect(t, r, http.StatusTooManyRequests)
	if r.header.Get("Retry-After") == "" {
		t.Fatal("no Retry-After")
	}
	// Limits are per account.
	expect(t, e.contribute(other, "us", batch("z", 1)...), http.StatusOK)
	// Refills at 100 a day.
	e.clock.Advance(24 * time.Hour)
	expect(t, e.contribute(tok, "us", batch("z", 100)...), http.StatusOK)
}

func TestMerchantVotesSurviveAccountDeletion(t *testing.T) {
	e := newEnv(t)
	var toks []string
	for i := range 4 {
		toks = append(toks, e.signIn(fmt.Sprintf("d%d@example.com", i)).Token)
	}
	for _, tok := range toks[:3] {
		expect(t, e.contribute(tok, "jp", merchantItem{Descriptor: "LAWSON", Name: "Lawson", CategoryKey: "groceries"}), http.StatusOK)
	}
	expect(t, e.do("DELETE", "/v1/account", toks[0], nil), http.StatusNoContent)
	if got := e.suggest(toks[3], "jp", "LAWSON"); len(got) != 1 {
		t.Fatalf("anonymous aggregate should remain: %+v", got)
	}
	for k := range e.store.votes {
		if len(k[2]) != 64 || strings.Trim(k[2], "0123456789abcdef") != "" {
			t.Fatalf("contributor is not an HMAC: %q", k[2])
		}
	}
}
