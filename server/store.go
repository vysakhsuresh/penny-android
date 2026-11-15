package main

import (
	"context"
	"errors"
	"time"
)

// Store errors. Handlers map them to HTTP status codes.
var (
	ErrNotFound  = errors.New("not found")
	ErrConflict  = errors.New("conflict")
	ErrForbidden = errors.New("forbidden")
	ErrGone      = errors.New("gone")
	ErrFull      = errors.New("space is full")
)

// Roles a space member can hold. NONE keeps the membership row but grants no access.
const (
	RoleOwner       = "OWNER"
	RoleAdmin       = "ADMIN"
	RoleEditor      = "EDITOR"
	RoleContributor = "CONTRIBUTOR"
	RoleViewer      = "VIEWER"
	RoleNone        = "NONE"
)

// inviteRetention is how long an expired invite row is kept (to answer 410 instead of 404).
const inviteRetention = 7 * 24 * time.Hour

// Stream kinds.
const (
	StreamPersonal = "personal"
	StreamSpace    = "space"
)

// Account holds no email: only a keyed hash of it, used to find the account at sign-in.
type Account struct {
	ID        string
	EmailHash string
	Salt      string
	CreatedAt time.Time
}

type AuthCode struct {
	EmailHash string
	CodeHash  string
	ExpiresAt time.Time
	Attempts  int
}

type Device struct {
	ID        string
	AccountID string
	Name      string
	Platform  string
	PublicKey string
	CreatedAt time.Time
	LastSeen  time.Time
}

type Stream struct {
	ID    string
	Kind  string // StreamPersonal or StreamSpace
	Owner string // account id for personal streams, empty for spaces
}

type Event struct {
	Cursor int64
	Blob   []byte
	Device string
}

type StreamKey struct {
	Stream    string
	AccountID string
	Wrapped   string
	Kind      string
}

type Member struct {
	SpaceID   string
	AccountID string
	Role      string
	PublicKey string
	JoinedAt  time.Time
	Left      bool
}

type Invite struct {
	TokenHash        string
	SpaceID          string
	Role             string
	Summary          string
	InviterAccount   string
	InviterPublicKey string
	ExpiresAt        time.Time
	CreatedAt        time.Time
	Revoked          bool
	AcceptedBy       string
}

type AdvisorLink struct {
	ID        string
	AccountID string
	Blob      []byte
	ExpiresAt time.Time
	CreatedAt time.Time
}

// Bank connection statuses.
const (
	ConnActive       = "active"
	ConnNeedsConsent = "needs_consent"
	ConnBroken       = "broken"
	ConnDisconnected = "disconnected"
)

// BankConnection is a provider connection owned by an account. Secret is the AES-256-GCM
// sealed provider credential (nil once access was revoked). Cursor is opaque sync state
// (last fetch time and hashes of seen transaction ids); no transaction data is stored.
type BankConnection struct {
	ID            string
	AccountID     string
	Provider      string
	Institution   string
	Status        string
	ConsentExpiry *time.Time
	Secret        []byte
	Cursor        []byte
	LastSyncedAt  *time.Time
	CreatedAt     time.Time
	UpdatedAt     time.Time
}

// ConnectState is a pending redirect consent. Only a hash of the state value is stored.
// ConnectionID is set when the consent renews an existing connection.
type ConnectState struct {
	StateHash    string
	AccountID    string
	Provider     string
	ConnectionID string
	RedirectURI  string
	ExpiresAt    time.Time
}

// Entitlement records a Plus purchase. Only a hash of the store purchase token is kept.
type Entitlement struct {
	AccountID string
	Store     string
	ProductID string
	TokenHash string
	PlusUntil time.Time
	UpdatedAt time.Time
}

// Store persists opaque relay state. Policy (who may do what) lives in the HTTP layer;
// the store only guarantees atomicity where a policy check and a write must not race.
type Store interface {
	// Auth codes. UseAuthAttempt atomically increments the attempt counter and returns the updated row.
	PutAuthCode(ctx context.Context, c AuthCode) error
	UseAuthAttempt(ctx context.Context, emailHash string) (AuthCode, error)
	DeleteAuthCode(ctx context.Context, emailHash string) error

	// Accounts and devices. UpsertAccount inserts a unless an account with the same email hash
	// exists, and returns the stored account.
	UpsertAccount(ctx context.Context, a Account) (Account, error)
	CreateDevice(ctx context.Context, d Device, tokenHash string) error
	DeviceByToken(ctx context.Context, tokenHash string) (Device, error)
	TouchDevice(ctx context.Context, deviceID string, at time.Time) error
	ListDevices(ctx context.Context, accountID string) ([]Device, error)
	RevokeDevice(ctx context.Context, accountID, deviceID string) error
	// DeleteAccount removes the account, its devices, tokens, keys, personal streams, membership
	// rows, invites it created, advisor links, bank connections (with their sealed credentials
	// and cursors), connect states and entitlement. It is idempotent.
	DeleteAccount(ctx context.Context, accountID string) error

	// Streams. EnsurePersonalStream creates a personal stream owned by owner if id is unused and
	// returns whatever stream now has that id.
	GetStream(ctx context.Context, id string) (Stream, error)
	EnsurePersonalStream(ctx context.Context, id, owner string, now time.Time) (Stream, error)
	AppendEvents(ctx context.Context, streamID, deviceID string, blobs [][]byte, now time.Time) (int64, error)
	ListEvents(ctx context.Context, streamID string, after int64, limit int) ([]Event, error)

	PutKey(ctx context.Context, k StreamKey, now time.Time) error
	ListKeys(ctx context.Context, accountID string) ([]StreamKey, error)

	// Spaces. UpdateMembers runs fn under a per-space lock with every member row (including
	// left ones) and writes back the rows fn returns. Keys of members written with Left=true
	// are deleted.
	CreateSpace(ctx context.Context, spaceID string, owner Member) error
	GetMember(ctx context.Context, spaceID, accountID string) (Member, error)
	ListMembers(ctx context.Context, spaceID string) ([]Member, error)
	ListMemberships(ctx context.Context, accountID string) ([]Member, error)
	UpdateMembers(ctx context.Context, spaceID string, fn func([]Member) ([]Member, error)) error
	DeleteSpaceIfEmpty(ctx context.Context, spaceID string) (bool, error)

	CreateInvite(ctx context.Context, inv Invite) error
	GetInvite(ctx context.Context, tokenHash string) (Invite, error)
	RevokeInvite(ctx context.Context, tokenHash string) error
	// AcceptInvite consumes the invite and adds (or re-activates) m as a member with the invite's
	// role. ErrGone: revoked, expired or used. ErrConflict: already an active member. ErrFull: limit reached.
	AcceptInvite(ctx context.Context, tokenHash string, m Member, now time.Time, limit int) (Invite, error)

	// PutAdvisor returns ErrForbidden if the id belongs to another account.
	PutAdvisor(ctx context.Context, a AdvisorLink) error
	GetAdvisor(ctx context.Context, id string) (AdvisorLink, error)
	DeleteAdvisor(ctx context.Context, id, accountID string) error

	// Bank connections. UpdateConnection writes institution, status, consent expiry, secret,
	// cursor, last sync and updated time. DeleteConnection also drops pending states for it.
	CreateConnection(ctx context.Context, c BankConnection) error
	GetConnection(ctx context.Context, id string) (BankConnection, error)
	ListConnections(ctx context.Context, accountID string) ([]BankConnection, error)
	UpdateConnection(ctx context.Context, c BankConnection) error
	DeleteConnection(ctx context.Context, accountID, id string) error
	// TakeConnectState returns and deletes the state if it belongs to accountID (ErrNotFound otherwise).
	PutConnectState(ctx context.Context, st ConnectState) error
	TakeConnectState(ctx context.Context, stateHash, accountID string) (ConnectState, error)

	GetEntitlement(ctx context.Context, accountID string) (Entitlement, error)
	PutEntitlement(ctx context.Context, e Entitlement) error

	// PurgeExpired drops expired auth codes, advisor links, connect states and long-dead invites.
	PurgeExpired(ctx context.Context, now time.Time) error
	Close()
}

// findMember returns the index of accountID in ms, or -1.
func findMember(ms []Member, accountID string) int {
	for i, m := range ms {
		if m.AccountID == accountID {
			return i
		}
	}
	return -1
}

func countActive(ms []Member, role string) int {
	n := 0
	for _, m := range ms {
		if !m.Left && (role == "" || m.Role == role) {
			n++
		}
	}
	return n
}
