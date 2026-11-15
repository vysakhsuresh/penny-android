package main

import (
	"context"
	"sort"
	"sync"
	"time"
)

// MemoryStore is a mutex-guarded in-memory Store for tests and local development.
type MemoryStore struct {
	mu       sync.Mutex
	codes    map[string]AuthCode // by email hash
	accounts map[string]Account  // by id
	devices  map[string]Device   // by id
	tokens   map[string]string   // token hash -> device id
	streams  map[string]Stream
	events   map[string][]Event
	keys     map[[2]string]StreamKey // (stream, account)
	members  map[string][]Member     // by space id
	invites  map[string]Invite       // by token hash
	advisor  map[string]AdvisorLink
}

func NewMemoryStore() *MemoryStore {
	return &MemoryStore{
		codes: map[string]AuthCode{}, accounts: map[string]Account{}, devices: map[string]Device{},
		tokens: map[string]string{}, streams: map[string]Stream{}, events: map[string][]Event{},
		keys: map[[2]string]StreamKey{}, members: map[string][]Member{}, invites: map[string]Invite{},
		advisor: map[string]AdvisorLink{},
	}
}

func (m *MemoryStore) Close() {}

func (m *MemoryStore) PutAuthCode(_ context.Context, c AuthCode) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	c.Attempts = 0
	m.codes[c.EmailHash] = c
	return nil
}

func (m *MemoryStore) UseAuthAttempt(_ context.Context, emailHash string) (AuthCode, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.codes[emailHash]
	if !ok {
		return AuthCode{}, ErrNotFound
	}
	c.Attempts++
	m.codes[emailHash] = c
	return c, nil
}

func (m *MemoryStore) DeleteAuthCode(_ context.Context, emailHash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.codes, emailHash)
	return nil
}

func (m *MemoryStore) UpsertAccount(_ context.Context, a Account) (Account, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, x := range m.accounts {
		if x.EmailHash == a.EmailHash {
			return x, nil
		}
	}
	m.accounts[a.ID] = a
	return a, nil
}

func (m *MemoryStore) CreateDevice(_ context.Context, d Device, tokenHash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.accounts[d.AccountID]; !ok {
		return ErrNotFound
	}
	m.devices[d.ID] = d
	m.tokens[tokenHash] = d.ID
	return nil
}

func (m *MemoryStore) DeviceByToken(_ context.Context, tokenHash string) (Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	d, ok := m.devices[m.tokens[tokenHash]]
	if !ok {
		return Device{}, ErrNotFound
	}
	return d, nil
}

func (m *MemoryStore) TouchDevice(_ context.Context, id string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if d, ok := m.devices[id]; ok {
		d.LastSeen = at
		m.devices[id] = d
	}
	return nil
}

func (m *MemoryStore) ListDevices(_ context.Context, accountID string) ([]Device, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []Device
	for _, d := range m.devices {
		if d.AccountID == accountID {
			out = append(out, d)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if !out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].CreatedAt.Before(out[j].CreatedAt)
		}
		return out[i].ID < out[j].ID
	})
	return out, nil
}

func (m *MemoryStore) RevokeDevice(_ context.Context, accountID, deviceID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	d, ok := m.devices[deviceID]
	if !ok || d.AccountID != accountID {
		return ErrNotFound
	}
	m.deleteDeviceLocked(deviceID)
	return nil
}

func (m *MemoryStore) deleteDeviceLocked(deviceID string) {
	delete(m.devices, deviceID)
	for h, id := range m.tokens {
		if id == deviceID {
			delete(m.tokens, h)
		}
	}
}

func (m *MemoryStore) DeleteAccount(_ context.Context, accountID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if a, ok := m.accounts[accountID]; ok {
		delete(m.codes, a.EmailHash)
	}
	delete(m.accounts, accountID)
	for id, d := range m.devices {
		if d.AccountID == accountID {
			m.deleteDeviceLocked(id)
		}
	}
	for id, s := range m.streams {
		if s.Kind == StreamPersonal && s.Owner == accountID {
			m.deleteStreamLocked(id)
		}
	}
	for k := range m.keys {
		if k[1] == accountID {
			delete(m.keys, k)
		}
	}
	for sid, ms := range m.members {
		kept := ms[:0:0]
		for _, x := range ms {
			if x.AccountID != accountID {
				kept = append(kept, x)
			}
		}
		m.members[sid] = kept
	}
	for h, inv := range m.invites {
		if inv.InviterAccount == accountID {
			delete(m.invites, h)
		}
	}
	for id, a := range m.advisor {
		if a.AccountID == accountID {
			delete(m.advisor, id)
		}
	}
	return nil
}

func (m *MemoryStore) deleteStreamLocked(id string) {
	delete(m.streams, id)
	delete(m.events, id)
	delete(m.members, id)
	for k := range m.keys {
		if k[0] == id {
			delete(m.keys, k)
		}
	}
	for h, inv := range m.invites {
		if inv.SpaceID == id {
			delete(m.invites, h)
		}
	}
}

func (m *MemoryStore) GetStream(_ context.Context, id string) (Stream, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.streams[id]
	if !ok {
		return Stream{}, ErrNotFound
	}
	return s, nil
}

func (m *MemoryStore) EnsurePersonalStream(_ context.Context, id, owner string, _ time.Time) (Stream, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s, ok := m.streams[id]; ok {
		return s, nil
	}
	if _, ok := m.accounts[owner]; !ok {
		return Stream{}, ErrNotFound
	}
	s := Stream{ID: id, Kind: StreamPersonal, Owner: owner}
	m.streams[id] = s
	return s, nil
}

func (m *MemoryStore) AppendEvents(_ context.Context, streamID, deviceID string, blobs [][]byte, _ time.Time) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.streams[streamID]; !ok {
		return 0, ErrNotFound
	}
	evs := m.events[streamID]
	var last int64
	if n := len(evs); n > 0 {
		last = evs[n-1].Cursor
	}
	for _, b := range blobs {
		last++
		evs = append(evs, Event{Cursor: last, Blob: append([]byte(nil), b...), Device: deviceID})
	}
	m.events[streamID] = evs
	return last, nil
}

func (m *MemoryStore) ListEvents(_ context.Context, streamID string, after int64, limit int) ([]Event, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	evs := m.events[streamID]
	i := sort.Search(len(evs), func(i int) bool { return evs[i].Cursor > after })
	end := min(i+limit, len(evs))
	return append([]Event(nil), evs[i:end]...), nil
}

func (m *MemoryStore) PutKey(_ context.Context, k StreamKey, _ time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.streams[k.Stream]; !ok {
		return ErrNotFound
	}
	m.keys[[2]string{k.Stream, k.AccountID}] = k
	return nil
}

func (m *MemoryStore) ListKeys(_ context.Context, accountID string) ([]StreamKey, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []StreamKey
	for k, v := range m.keys {
		if k[1] == accountID {
			out = append(out, v)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Stream < out[j].Stream })
	return out, nil
}

func (m *MemoryStore) CreateSpace(_ context.Context, spaceID string, owner Member) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.streams[spaceID]; ok {
		return ErrConflict
	}
	m.streams[spaceID] = Stream{ID: spaceID, Kind: StreamSpace}
	owner.SpaceID = spaceID
	m.members[spaceID] = []Member{owner}
	return nil
}

func (m *MemoryStore) GetMember(_ context.Context, spaceID, accountID string) (Member, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	ms := m.members[spaceID]
	if i := findMember(ms, accountID); i >= 0 {
		return ms[i], nil
	}
	return Member{}, ErrNotFound
}

func (m *MemoryStore) ListMembers(_ context.Context, spaceID string) ([]Member, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s, ok := m.streams[spaceID]; !ok || s.Kind != StreamSpace {
		return nil, ErrNotFound
	}
	out := append([]Member(nil), m.members[spaceID]...)
	sortMembers(out)
	return out, nil
}

func sortMembers(ms []Member) {
	sort.SliceStable(ms, func(i, j int) bool {
		if !ms[i].JoinedAt.Equal(ms[j].JoinedAt) {
			return ms[i].JoinedAt.Before(ms[j].JoinedAt)
		}
		return ms[i].AccountID < ms[j].AccountID
	})
}

func (m *MemoryStore) ListMemberships(_ context.Context, accountID string) ([]Member, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []Member
	for _, ms := range m.members {
		for _, x := range ms {
			if x.AccountID == accountID && !x.Left {
				out = append(out, x)
			}
		}
	}
	return out, nil
}

func (m *MemoryStore) UpdateMembers(_ context.Context, spaceID string, fn func([]Member) ([]Member, error)) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s, ok := m.streams[spaceID]; !ok || s.Kind != StreamSpace {
		return ErrNotFound
	}
	cur := append([]Member(nil), m.members[spaceID]...)
	sortMembers(cur)
	changed, err := fn(cur)
	if err != nil {
		return err
	}
	ms := m.members[spaceID]
	for _, c := range changed {
		if i := findMember(ms, c.AccountID); i >= 0 {
			ms[i].Role, ms[i].PublicKey, ms[i].Left = c.Role, c.PublicKey, c.Left
		}
		if c.Left {
			delete(m.keys, [2]string{spaceID, c.AccountID})
		}
	}
	return nil
}

func (m *MemoryStore) DeleteSpaceIfEmpty(_ context.Context, spaceID string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s, ok := m.streams[spaceID]; !ok || s.Kind != StreamSpace {
		return false, nil
	}
	if countActive(m.members[spaceID], "") > 0 {
		return false, nil
	}
	m.deleteStreamLocked(spaceID)
	return true, nil
}

func (m *MemoryStore) CreateInvite(_ context.Context, inv Invite) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.invites[inv.TokenHash]; ok {
		return ErrConflict
	}
	m.invites[inv.TokenHash] = inv
	return nil
}

func (m *MemoryStore) GetInvite(_ context.Context, tokenHash string) (Invite, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.invites[tokenHash]
	if !ok {
		return Invite{}, ErrNotFound
	}
	return inv, nil
}

func (m *MemoryStore) RevokeInvite(_ context.Context, tokenHash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.invites[tokenHash]
	if !ok {
		return ErrNotFound
	}
	inv.Revoked = true
	m.invites[tokenHash] = inv
	return nil
}

func (m *MemoryStore) AcceptInvite(_ context.Context, tokenHash string, mem Member, now time.Time, limit int) (Invite, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, ok := m.invites[tokenHash]
	if !ok {
		return Invite{}, ErrNotFound
	}
	if inv.Revoked || inv.AcceptedBy != "" || !now.Before(inv.ExpiresAt) {
		return Invite{}, ErrGone
	}
	ms := m.members[inv.SpaceID]
	i := findMember(ms, mem.AccountID)
	if i >= 0 && !ms[i].Left {
		return Invite{}, ErrConflict
	}
	if countActive(ms, "") >= limit {
		return Invite{}, ErrFull
	}
	mem.SpaceID, mem.Role, mem.JoinedAt, mem.Left = inv.SpaceID, inv.Role, now, false
	if i >= 0 {
		ms[i] = mem
	} else {
		ms = append(ms, mem)
	}
	m.members[inv.SpaceID] = ms
	inv.AcceptedBy = mem.AccountID
	m.invites[tokenHash] = inv
	return inv, nil
}

func (m *MemoryStore) PutAdvisor(_ context.Context, a AdvisorLink) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if old, ok := m.advisor[a.ID]; ok {
		if old.AccountID != a.AccountID {
			return ErrForbidden
		}
		a.CreatedAt = old.CreatedAt
	}
	m.advisor[a.ID] = a
	return nil
}

func (m *MemoryStore) GetAdvisor(_ context.Context, id string) (AdvisorLink, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	a, ok := m.advisor[id]
	if !ok {
		return AdvisorLink{}, ErrNotFound
	}
	return a, nil
}

func (m *MemoryStore) DeleteAdvisor(_ context.Context, id, accountID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if a, ok := m.advisor[id]; !ok || a.AccountID != accountID {
		return ErrNotFound
	}
	delete(m.advisor, id)
	return nil
}

func (m *MemoryStore) PurgeExpired(_ context.Context, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for h, c := range m.codes {
		if !now.Before(c.ExpiresAt) {
			delete(m.codes, h)
		}
	}
	for id, a := range m.advisor {
		if !now.Before(a.ExpiresAt) {
			delete(m.advisor, id)
		}
	}
	for h, inv := range m.invites {
		if now.Sub(inv.ExpiresAt) > inviteRetention {
			delete(m.invites, h)
		}
	}
	return nil
}
