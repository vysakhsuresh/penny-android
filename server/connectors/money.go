package connectors

import (
	"fmt"
	"math/big"
	"strings"
)

// minorDigits lists ISO 4217 currencies whose minor unit is not 2 digits. Everything else,
// including non-ISO currency identifiers, uses 2.
var minorDigits = map[string]int{
	"BIF": 0, "CLP": 0, "DJF": 0, "GNF": 0, "ISK": 0, "JPY": 0, "KMF": 0, "KRW": 0,
	"PYG": 0, "RWF": 0, "UGX": 0, "UYI": 0, "VND": 0, "VUV": 0, "XAF": 0, "XOF": 0, "XPF": 0,
	"BHD": 3, "IQD": 3, "JOD": 3, "KWD": 3, "LYD": 3, "OMR": 3, "TND": 3,
	"CLF": 4, "UYW": 4,
}

// MinorDigits returns the number of minor-unit digits for an ISO 4217 code.
func MinorDigits(currency string) int {
	if d, ok := minorDigits[strings.ToUpper(strings.TrimSpace(currency))]; ok {
		return d
	}
	return 2
}

// ParseMinor converts a provider decimal string ("-12.34", "1000", "+0.5") to signed integer
// minor units of currency without floating point. Digits beyond the currency's precision are
// rounded half to even. Exponents, grouping separators and empty strings are rejected.
func ParseMinor(s, currency string) (int64, error) {
	orig := s
	s = strings.TrimSpace(s)
	neg := false
	if s != "" && (s[0] == '-' || s[0] == '+') {
		neg = s[0] == '-'
		s = s[1:]
	}
	intPart, frac, _ := strings.Cut(s, ".")
	if (intPart == "" && frac == "") || !allDigits(intPart) || !allDigits(frac) {
		return 0, fmt.Errorf("invalid amount %q", orig)
	}
	if intPart == "" {
		intPart = "0"
	}
	d := MinorDigits(currency)
	roundUp := false
	if len(frac) > d {
		kept, rest := frac[:d], frac[d:]
		switch {
		case rest[0] > '5':
			roundUp = true
		case rest[0] == '5':
			if strings.Trim(rest[1:], "0") != "" {
				roundUp = true
			} else { // exactly half: round to even
				last := intPart[len(intPart)-1]
				if kept != "" {
					last = kept[len(kept)-1]
				}
				roundUp = (last-'0')%2 == 1
			}
		}
		frac = kept
	} else {
		frac += strings.Repeat("0", d-len(frac))
	}
	n, ok := new(big.Int).SetString(intPart+frac, 10)
	if !ok {
		return 0, fmt.Errorf("invalid amount %q", orig)
	}
	if roundUp {
		n.Add(n, big.NewInt(1))
	}
	if neg {
		n.Neg(n)
	}
	if !n.IsInt64() {
		return 0, fmt.Errorf("amount %q out of range", orig)
	}
	return n.Int64(), nil
}

func allDigits(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}
