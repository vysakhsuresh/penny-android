package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"math"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Community merchant map (PY-03). Opt-in clients contribute (descriptor -> clean name,
// category) corrections per region and ask for suggestions. Nothing is seeded. The server
// keeps one vote per (region, normalised descriptor, contributor), where contributor is
// HMAC-SHA256(MERCHANT_SALT, account id, region, descriptor): it deduplicates an account's
// votes on one descriptor but cannot link one account's votes across descriptors. No amounts,
// dates or account data are ever received. A mapping is suggested only once at least
// merchantMinAccounts contributors agree on it and it holds at least 60% of the votes.
const (
	maxContributePerDay   = 100
	maxContributeItems    = 100
	maxSuggestDescriptors = 200
	maxDescriptorRunes    = 64
	maxMerchantNameRunes  = 48
	maxMerchantBody       = 128 << 10
	merchantMinAccounts   = 3
	merchantMinSharePct   = 60
)

var (
	categoryKeyRe = regexp.MustCompile(`^[a-z]+(\.[a-z]+)?$`)
	regionRe      = regexp.MustCompile(`^[a-z]{2}$`)
	longDigitsRe  = regexp.MustCompile(`\p{Nd}{4,}`)
	emailLikeRe   = regexp.MustCompile(`[^\s@]+@[^\s@]+\.[^\s@]+`)
	ibanLikeRe    = regexp.MustCompile(`(?i)\b[a-z]{2}[0-9]{2}[a-z0-9]{11,30}\b`)
)

// noiseWords are payment-rail and terminal tokens that carry no merchant identity.
var noiseWords = map[string]bool{
	"pos": true, "upi": true, "ref": true, "txn": true, "trx": true, "ach": true, "imps": true,
	"neft": true, "rtgs": true, "vpa": true, "ecom": true, "pymt": true,
}

// ---- JSON shapes ----

type merchantSuggestReq struct {
	Region      string   `json:"region"`
	Descriptors []string `json:"descriptors"`
}

type merchantSuggestion struct {
	Descriptor  string  `json:"descriptor"`
	Name        string  `json:"name"`
	CategoryKey string  `json:"categoryKey"`
	Confidence  float64 `json:"confidence"`
}

type merchantItem struct {
	Descriptor  string `json:"descriptor"`
	Name        string `json:"name"`
	CategoryKey string `json:"categoryKey"`
}

type merchantContributeReq struct {
	Region string         `json:"region"`
	Items  []merchantItem `json:"items"`
}

type merchantContributeResp struct {
	Accepted int `json:"accepted"`
}

// ---- normalisation and validation ----

// normalizeDescriptor lowercases, drops apostrophes, turns other punctuation into spaces,
// strips digit runs of 4 or more, removes noise words and collapses whitespace.
func normalizeDescriptor(d string) string {
	d = strings.ToLower(d)
	var b strings.Builder
	for _, r := range d {
		switch {
		case r == '\'' || r == '’':
		case unicode.IsLetter(r) || unicode.IsDigit(r) || unicode.IsMark(r) || r == '&':
			b.WriteRune(r)
		default:
			b.WriteByte(' ')
		}
	}
	d = longDigitsRe.ReplaceAllString(b.String(), " ")
	fields := strings.Fields(d)
	kept := fields[:0]
	for _, f := range fields {
		if !noiseWords[f] {
			kept = append(kept, f)
		}
	}
	return strings.Join(kept, " ")
}

// looksPersonal reports text that may carry an email address, a phone, card or account
// number (8 or more digits, allowing space, dash, dot, parenthesis and plus separators) or an
// IBAN.
func looksPersonal(s string) bool {
	if emailLikeRe.MatchString(s) || ibanLikeRe.MatchString(s) {
		return true
	}
	digits := 0
	for _, r := range s {
		switch {
		case unicode.IsDigit(r):
			digits++
			if digits >= 8 {
				return true
			}
		case r == ' ' || r == '-' || r == '.' || r == '(' || r == ')' || r == '+':
		default:
			digits = 0
		}
	}
	return false
}

func normalizeRegion(r string) (string, bool) {
	r = strings.ToLower(strings.TrimSpace(r))
	return r, regionRe.MatchString(r)
}

// contributorHash keys a vote to (account, region, descriptor) without storing any of them
// in a joinable form.
func (s *Server) contributorHash(accountID, region, descriptor string) string {
	m := hmac.New(sha256.New, s.merchantSalt)
	m.Write([]byte(accountID))
	m.Write([]byte{0})
	m.Write([]byte(region))
	m.Write([]byte{0})
	m.Write([]byte(descriptor))
	return hex.EncodeToString(m.Sum(nil))
}

// ---- handlers ----

func (s *Server) suggestMerchants(w http.ResponseWriter, r *http.Request, c caller) {
	var req merchantSuggestReq
	if !decodeJSON(w, r, &req, maxMerchantBody) {
		return
	}
	region, ok := normalizeRegion(req.Region)
	switch {
	case !ok:
		writeErr(w, http.StatusBadRequest, "region must be a two-letter region code")
		return
	case len(req.Descriptors) > maxSuggestDescriptors:
		writeErr(w, http.StatusBadRequest, "descriptors must contain at most 200 items")
		return
	}
	if ok, wait := s.suggestByAcct.allow(c.AccountID); !ok {
		tooMany(w, wait)
		return
	}
	norm := make([]string, len(req.Descriptors))
	var keys []string
	seen := map[string]bool{}
	for i, d := range req.Descriptors {
		if utf8.RuneCountInString(d) > maxDescriptorRunes {
			continue // never matches: contributions are limited to the same length
		}
		n := normalizeDescriptor(d)
		norm[i] = n
		if n != "" && !seen[n] {
			seen[n] = true
			keys = append(keys, n)
		}
	}
	out := []merchantSuggestion{}
	if len(keys) == 0 {
		writeJSON(w, http.StatusOK, out)
		return
	}
	cands, err := s.store.MerchantCandidates(r.Context(), region, keys)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	for i, d := range req.Descriptors {
		if norm[i] == "" {
			continue
		}
		if top, conf, ok := pickMerchant(cands[norm[i]]); ok {
			out = append(out, merchantSuggestion{Descriptor: d, Name: top.Name, CategoryKey: top.CategoryKey, Confidence: conf})
		}
	}
	writeJSON(w, http.StatusOK, out)
}

// pickMerchant applies the visibility rule: the leading candidate needs at least
// merchantMinAccounts distinct contributors and at least 60% of all votes for the descriptor.
// Confidence is its vote share, rounded to two decimals.
func pickMerchant(cs []MerchantCandidate) (MerchantCandidate, float64, bool) {
	if len(cs) == 0 {
		return MerchantCandidate{}, 0, false
	}
	total := 0
	for _, c := range cs {
		total += c.Votes
	}
	top := cs[0] // sorted by votes, descending
	if top.Votes < merchantMinAccounts || top.Votes*100 < total*merchantMinSharePct {
		return MerchantCandidate{}, 0, false
	}
	return top, math.Round(float64(top.Votes)/float64(total)*100) / 100, true
}

func (s *Server) contributeMerchants(w http.ResponseWriter, r *http.Request, c caller) {
	var req merchantContributeReq
	if !decodeJSON(w, r, &req, maxMerchantBody) {
		return
	}
	region, ok := normalizeRegion(req.Region)
	switch {
	case !ok:
		writeErr(w, http.StatusBadRequest, "region must be a two-letter region code")
		return
	case len(req.Items) == 0 || len(req.Items) > maxContributeItems:
		writeErr(w, http.StatusBadRequest, "items must contain 1 to 100 items")
		return
	}
	now := s.now()
	byDesc := map[string]int{} // normalised descriptor -> index in votes (last item wins)
	var votes []MerchantVote
	for i, it := range req.Items {
		bad := func(msg string) {
			writeErr(w, http.StatusBadRequest, "items["+strconv.Itoa(i)+"]: "+msg)
		}
		name := strings.Join(strings.Fields(it.Name), " ")
		desc := normalizeDescriptor(it.Descriptor)
		switch {
		case utf8.RuneCountInString(it.Descriptor) > maxDescriptorRunes:
			bad("descriptor must be at most 64 characters")
			return
		case desc == "":
			bad("descriptor is empty after normalisation")
			return
		case name == "" || utf8.RuneCountInString(name) > maxMerchantNameRunes:
			bad("name must be 1 to 48 characters")
			return
		case !categoryKeyRe.MatchString(it.CategoryKey):
			bad("categoryKey must match ^[a-z]+(\\.[a-z]+)?$")
			return
		case looksPersonal(it.Descriptor) || looksPersonal(name):
			bad("looks like it contains an email, phone, card or account number")
			return
		}
		v := MerchantVote{Region: region, Descriptor: desc, Contributor: s.contributorHash(c.AccountID, region, desc),
			Name: name, CategoryKey: it.CategoryKey, UpdatedAt: now}
		if j, dup := byDesc[desc]; dup {
			votes[j] = v
			continue
		}
		byDesc[desc] = len(votes)
		votes = append(votes, v)
	}
	if ok, wait := s.contributeByAcct.allowN(c.AccountID, len(votes)); !ok {
		tooMany(w, wait)
		return
	}
	if err := s.store.AddMerchantVotes(r.Context(), votes); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, merchantContributeResp{Accepted: len(votes)})
}
