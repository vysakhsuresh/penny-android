package main

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// PostgresStore implements Store on Postgres. Event blobs are stored as bytea.
type PostgresStore struct {
	pool *pgxpool.Pool
}

func NewPostgresStore(ctx context.Context, url string) (*PostgresStore, error) {
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		return nil, err
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return &PostgresStore{pool: pool}, nil
}

func (p *PostgresStore) Close() { p.pool.Close() }

// Migrate applies embedded migrations that have not run yet, under an advisory lock.
func (p *PostgresStore) Migrate(ctx context.Context) error {
	names, err := fs.Glob(migrationsFS, "migrations/*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	return p.tx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(7366791)`); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
			name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
			return err
		}
		for _, name := range names {
			var done bool
			if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE name=$1)`, name).Scan(&done); err != nil {
				return err
			}
			if done {
				continue
			}
			sql, err := migrationsFS.ReadFile(name)
			if err != nil {
				return err
			}
			// No arguments: pgx uses the simple protocol, which allows multiple statements.
			if _, err := tx.Exec(ctx, string(sql)); err != nil {
				return fmt.Errorf("migration %s: %w", name, err)
			}
			if _, err := tx.Exec(ctx, `INSERT INTO schema_migrations(name) VALUES($1)`, name); err != nil {
				return err
			}
		}
		return nil
	})
}

func (p *PostgresStore) tx(ctx context.Context, fn func(pgx.Tx) error) error {
	return pgx.BeginFunc(ctx, p.pool, fn)
}

func pgErr(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23505" {
		return ErrConflict
	}
	return err
}

func (p *PostgresStore) PutAuthCode(ctx context.Context, c AuthCode) error {
	_, err := p.pool.Exec(ctx, `INSERT INTO auth_codes(email_hash, code_hash, expires_at, attempts)
		VALUES($1,$2,$3,0)
		ON CONFLICT (email_hash) DO UPDATE SET code_hash=EXCLUDED.code_hash, expires_at=EXCLUDED.expires_at, attempts=0`,
		c.EmailHash, c.CodeHash, c.ExpiresAt)
	return err
}

func (p *PostgresStore) UseAuthAttempt(ctx context.Context, emailHash string) (AuthCode, error) {
	c := AuthCode{EmailHash: emailHash}
	err := p.pool.QueryRow(ctx, `UPDATE auth_codes SET attempts=attempts+1 WHERE email_hash=$1
		RETURNING code_hash, expires_at, attempts`, emailHash).Scan(&c.CodeHash, &c.ExpiresAt, &c.Attempts)
	return c, pgErr(err)
}

func (p *PostgresStore) DeleteAuthCode(ctx context.Context, emailHash string) error {
	_, err := p.pool.Exec(ctx, `DELETE FROM auth_codes WHERE email_hash=$1`, emailHash)
	return err
}

func (p *PostgresStore) UpsertAccount(ctx context.Context, a Account) (Account, error) {
	if _, err := p.pool.Exec(ctx, `INSERT INTO accounts(id, email_hash, salt, created_at) VALUES($1,$2,$3,$4)
		ON CONFLICT (email_hash) DO NOTHING`, a.ID, a.EmailHash, a.Salt, a.CreatedAt); err != nil {
		return Account{}, err
	}
	var out Account
	err := p.pool.QueryRow(ctx, `SELECT id, email_hash, salt, created_at FROM accounts WHERE email_hash=$1`, a.EmailHash).
		Scan(&out.ID, &out.EmailHash, &out.Salt, &out.CreatedAt)
	return out, pgErr(err)
}

func (p *PostgresStore) CreateDevice(ctx context.Context, d Device, tokenHash string) error {
	return pgErr(p.tx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `INSERT INTO devices(id, account_id, name, platform, public_key, created_at, last_seen)
			VALUES($1,$2,$3,$4,$5,$6,$7)`, d.ID, d.AccountID, d.Name, d.Platform, d.PublicKey, d.CreatedAt, d.LastSeen); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO tokens(hash, device_id, account_id, created_at) VALUES($1,$2,$3,$4)`,
			tokenHash, d.ID, d.AccountID, d.CreatedAt)
		return err
	}))
}

const deviceCols = `d.id, d.account_id, d.name, d.platform, d.public_key, d.created_at, d.last_seen`

func scanDevice(row pgx.Row) (Device, error) {
	var d Device
	err := row.Scan(&d.ID, &d.AccountID, &d.Name, &d.Platform, &d.PublicKey, &d.CreatedAt, &d.LastSeen)
	return d, err
}

func (p *PostgresStore) DeviceByToken(ctx context.Context, tokenHash string) (Device, error) {
	d, err := scanDevice(p.pool.QueryRow(ctx, `SELECT `+deviceCols+` FROM tokens t JOIN devices d ON d.id=t.device_id WHERE t.hash=$1`, tokenHash))
	return d, pgErr(err)
}

func (p *PostgresStore) TouchDevice(ctx context.Context, id string, at time.Time) error {
	_, err := p.pool.Exec(ctx, `UPDATE devices SET last_seen=$2 WHERE id=$1`, id, at)
	return err
}

func (p *PostgresStore) ListDevices(ctx context.Context, accountID string) ([]Device, error) {
	rows, err := p.pool.Query(ctx, `SELECT `+deviceCols+` FROM devices d WHERE d.account_id=$1 ORDER BY d.created_at, d.id`, accountID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Device, error) { return scanDevice(r) })
}

func (p *PostgresStore) RevokeDevice(ctx context.Context, accountID, deviceID string) error {
	// Tokens cascade with the device row.
	tag, err := p.pool.Exec(ctx, `DELETE FROM devices WHERE id=$1 AND account_id=$2`, deviceID, accountID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *PostgresStore) DeleteAccount(ctx context.Context, accountID string) error {
	// Foreign keys cascade to devices, tokens, personal streams (and their events and keys),
	// stream keys, memberships, invites created by the account, advisor links, bank connections
	// (sealed credentials and cursors), connect states and the entitlement.
	return p.tx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `DELETE FROM auth_codes WHERE email_hash IN (SELECT email_hash FROM accounts WHERE id=$1)`, accountID); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `DELETE FROM accounts WHERE id=$1`, accountID)
		return err
	})
}

func (p *PostgresStore) GetStream(ctx context.Context, id string) (Stream, error) {
	var s Stream
	err := p.pool.QueryRow(ctx, `SELECT id, kind, coalesce(owner_account, '') FROM streams WHERE id=$1`, id).Scan(&s.ID, &s.Kind, &s.Owner)
	return s, pgErr(err)
}

func (p *PostgresStore) EnsurePersonalStream(ctx context.Context, id, owner string, now time.Time) (Stream, error) {
	if _, err := p.pool.Exec(ctx, `INSERT INTO streams(id, kind, owner_account, created_at) VALUES($1,'personal',$2,$3)
		ON CONFLICT (id) DO NOTHING`, id, owner, now); err != nil {
		return Stream{}, pgErr(err)
	}
	return p.GetStream(ctx, id)
}

func (p *PostgresStore) AppendEvents(ctx context.Context, streamID, deviceID string, blobs [][]byte, now time.Time) (int64, error) {
	var last int64
	err := p.tx(ctx, func(tx pgx.Tx) error {
		var one int
		if err := tx.QueryRow(ctx, `SELECT 1 FROM streams WHERE id=$1 FOR UPDATE`, streamID).Scan(&one); err != nil {
			return pgErr(err)
		}
		if err := tx.QueryRow(ctx, `SELECT coalesce(max(cursor), 0) FROM events WHERE stream_id=$1`, streamID).Scan(&last); err != nil {
			return err
		}
		rows := make([][]any, len(blobs))
		for i, b := range blobs {
			last++
			rows[i] = []any{streamID, last, b, deviceID, now}
		}
		_, err := tx.CopyFrom(ctx, pgx.Identifier{"events"},
			[]string{"stream_id", "cursor", "blob", "device", "created_at"}, pgx.CopyFromRows(rows))
		return err
	})
	return last, err
}

func (p *PostgresStore) ListEvents(ctx context.Context, streamID string, after int64, limit int) ([]Event, error) {
	rows, err := p.pool.Query(ctx, `SELECT cursor, blob, device FROM events WHERE stream_id=$1 AND cursor>$2 ORDER BY cursor LIMIT $3`,
		streamID, after, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Event, error) {
		var e Event
		err := r.Scan(&e.Cursor, &e.Blob, &e.Device)
		return e, err
	})
}

func (p *PostgresStore) PutKey(ctx context.Context, k StreamKey, now time.Time) error {
	_, err := p.pool.Exec(ctx, `INSERT INTO stream_keys(stream_id, account_id, wrapped, kind, updated_at) VALUES($1,$2,$3,$4,$5)
		ON CONFLICT (stream_id, account_id) DO UPDATE SET wrapped=EXCLUDED.wrapped, kind=EXCLUDED.kind, updated_at=EXCLUDED.updated_at`,
		k.Stream, k.AccountID, k.Wrapped, k.Kind, now)
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23503" { // foreign key: stream or account gone
		return ErrNotFound
	}
	return err
}

func (p *PostgresStore) ListKeys(ctx context.Context, accountID string) ([]StreamKey, error) {
	rows, err := p.pool.Query(ctx, `SELECT stream_id, account_id, wrapped, kind FROM stream_keys WHERE account_id=$1 ORDER BY stream_id`, accountID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (StreamKey, error) {
		var k StreamKey
		err := r.Scan(&k.Stream, &k.AccountID, &k.Wrapped, &k.Kind)
		return k, err
	})
}

func (p *PostgresStore) CreateSpace(ctx context.Context, spaceID string, owner Member) error {
	return pgErr(p.tx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `INSERT INTO streams(id, kind, owner_account, created_at) VALUES($1,'space',NULL,$2)`, spaceID, owner.JoinedAt); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO spaces(id, created_at) VALUES($1,$2)`, spaceID, owner.JoinedAt); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `INSERT INTO members(space_id, account_id, role, public_key, joined_at, left_space) VALUES($1,$2,$3,$4,$5,false)`,
			spaceID, owner.AccountID, owner.Role, owner.PublicKey, owner.JoinedAt)
		return err
	}))
}

const memberCols = `space_id, account_id, role, public_key, joined_at, left_space`

func collectMembers(rows pgx.Rows) ([]Member, error) {
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (Member, error) {
		var m Member
		err := r.Scan(&m.SpaceID, &m.AccountID, &m.Role, &m.PublicKey, &m.JoinedAt, &m.Left)
		return m, err
	})
}

func (p *PostgresStore) GetMember(ctx context.Context, spaceID, accountID string) (Member, error) {
	var m Member
	err := p.pool.QueryRow(ctx, `SELECT `+memberCols+` FROM members WHERE space_id=$1 AND account_id=$2`, spaceID, accountID).
		Scan(&m.SpaceID, &m.AccountID, &m.Role, &m.PublicKey, &m.JoinedAt, &m.Left)
	return m, pgErr(err)
}

func (p *PostgresStore) ListMembers(ctx context.Context, spaceID string) ([]Member, error) {
	var exists bool
	if err := p.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM spaces WHERE id=$1)`, spaceID).Scan(&exists); err != nil {
		return nil, err
	}
	if !exists {
		return nil, ErrNotFound
	}
	rows, err := p.pool.Query(ctx, `SELECT `+memberCols+` FROM members WHERE space_id=$1 ORDER BY joined_at, account_id`, spaceID)
	if err != nil {
		return nil, err
	}
	return collectMembers(rows)
}

func (p *PostgresStore) ListMemberships(ctx context.Context, accountID string) ([]Member, error) {
	rows, err := p.pool.Query(ctx, `SELECT `+memberCols+` FROM members WHERE account_id=$1 AND NOT left_space ORDER BY space_id`, accountID)
	if err != nil {
		return nil, err
	}
	return collectMembers(rows)
}

func lockSpace(ctx context.Context, tx pgx.Tx, spaceID string) error {
	var one int
	return pgErr(tx.QueryRow(ctx, `SELECT 1 FROM spaces WHERE id=$1 FOR UPDATE`, spaceID).Scan(&one))
}

func (p *PostgresStore) UpdateMembers(ctx context.Context, spaceID string, fn func([]Member) ([]Member, error)) error {
	return p.tx(ctx, func(tx pgx.Tx) error {
		if err := lockSpace(ctx, tx, spaceID); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT `+memberCols+` FROM members WHERE space_id=$1 ORDER BY joined_at, account_id`, spaceID)
		if err != nil {
			return err
		}
		cur, err := collectMembers(rows)
		if err != nil {
			return err
		}
		changed, err := fn(cur)
		if err != nil {
			return err
		}
		for _, c := range changed {
			if _, err := tx.Exec(ctx, `UPDATE members SET role=$3, public_key=$4, left_space=$5 WHERE space_id=$1 AND account_id=$2`,
				spaceID, c.AccountID, c.Role, c.PublicKey, c.Left); err != nil {
				return err
			}
			if c.Left {
				if _, err := tx.Exec(ctx, `DELETE FROM stream_keys WHERE stream_id=$1 AND account_id=$2`, spaceID, c.AccountID); err != nil {
					return err
				}
			}
		}
		return nil
	})
}

func (p *PostgresStore) DeleteSpaceIfEmpty(ctx context.Context, spaceID string) (bool, error) {
	deleted := false
	err := p.tx(ctx, func(tx pgx.Tx) error {
		if err := lockSpace(ctx, tx, spaceID); err != nil {
			if errors.Is(err, ErrNotFound) {
				return nil
			}
			return err
		}
		var active int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM members WHERE space_id=$1 AND NOT left_space`, spaceID).Scan(&active); err != nil {
			return err
		}
		if active > 0 {
			return nil
		}
		// Cascades to spaces, members, invites, events and keys.
		if _, err := tx.Exec(ctx, `DELETE FROM streams WHERE id=$1`, spaceID); err != nil {
			return err
		}
		deleted = true
		return nil
	})
	return deleted, err
}

func (p *PostgresStore) CreateInvite(ctx context.Context, inv Invite) error {
	_, err := p.pool.Exec(ctx, `INSERT INTO invites(token_hash, space_id, role, summary, inviter_account, inviter_public_key, expires_at, created_at)
		VALUES($1,$2,$3,$4,$5,$6,$7,$8)`,
		inv.TokenHash, inv.SpaceID, inv.Role, inv.Summary, inv.InviterAccount, inv.InviterPublicKey, inv.ExpiresAt, inv.CreatedAt)
	return pgErr(err)
}

const inviteCols = `token_hash, space_id, role, summary, inviter_account, inviter_public_key, expires_at, created_at, revoked, coalesce(accepted_by, '')`

func scanInvite(row pgx.Row) (Invite, error) {
	var i Invite
	err := row.Scan(&i.TokenHash, &i.SpaceID, &i.Role, &i.Summary, &i.InviterAccount, &i.InviterPublicKey,
		&i.ExpiresAt, &i.CreatedAt, &i.Revoked, &i.AcceptedBy)
	return i, pgErr(err)
}

func (p *PostgresStore) GetInvite(ctx context.Context, tokenHash string) (Invite, error) {
	return scanInvite(p.pool.QueryRow(ctx, `SELECT `+inviteCols+` FROM invites WHERE token_hash=$1`, tokenHash))
}

func (p *PostgresStore) RevokeInvite(ctx context.Context, tokenHash string) error {
	tag, err := p.pool.Exec(ctx, `UPDATE invites SET revoked=true WHERE token_hash=$1`, tokenHash)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *PostgresStore) AcceptInvite(ctx context.Context, tokenHash string, m Member, now time.Time, limit int) (Invite, error) {
	var inv Invite
	err := p.tx(ctx, func(tx pgx.Tx) error {
		var err error
		if inv, err = scanInvite(tx.QueryRow(ctx, `SELECT `+inviteCols+` FROM invites WHERE token_hash=$1 FOR UPDATE`, tokenHash)); err != nil {
			return err
		}
		if inv.Revoked || inv.AcceptedBy != "" || !now.Before(inv.ExpiresAt) {
			return ErrGone
		}
		if err := lockSpace(ctx, tx, inv.SpaceID); err != nil {
			return err
		}
		var already bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM members WHERE space_id=$1 AND account_id=$2 AND NOT left_space)`,
			inv.SpaceID, m.AccountID).Scan(&already); err != nil {
			return err
		}
		if already {
			return ErrConflict
		}
		var active int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM members WHERE space_id=$1 AND NOT left_space`, inv.SpaceID).Scan(&active); err != nil {
			return err
		}
		if active >= limit {
			return ErrFull
		}
		if _, err := tx.Exec(ctx, `INSERT INTO members(space_id, account_id, role, public_key, joined_at, left_space) VALUES($1,$2,$3,$4,$5,false)
			ON CONFLICT (space_id, account_id) DO UPDATE SET role=EXCLUDED.role, public_key=EXCLUDED.public_key,
			joined_at=EXCLUDED.joined_at, left_space=false`, inv.SpaceID, m.AccountID, inv.Role, m.PublicKey, now); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `UPDATE invites SET accepted_by=$2, accepted_at=$3 WHERE token_hash=$1`, tokenHash, m.AccountID, now)
		inv.AcceptedBy = m.AccountID
		return err
	})
	return inv, err
}

func (p *PostgresStore) PutAdvisor(ctx context.Context, a AdvisorLink) error {
	tag, err := p.pool.Exec(ctx, `INSERT INTO advisor_links(id, account_id, blob, expires_at, created_at) VALUES($1,$2,$3,$4,$5)
		ON CONFLICT (id) DO UPDATE SET blob=EXCLUDED.blob, expires_at=EXCLUDED.expires_at
		WHERE advisor_links.account_id=EXCLUDED.account_id`, a.ID, a.AccountID, a.Blob, a.ExpiresAt, a.CreatedAt)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrForbidden
	}
	return nil
}

func (p *PostgresStore) GetAdvisor(ctx context.Context, id string) (AdvisorLink, error) {
	var a AdvisorLink
	err := p.pool.QueryRow(ctx, `SELECT id, account_id, blob, expires_at, created_at FROM advisor_links WHERE id=$1`, id).
		Scan(&a.ID, &a.AccountID, &a.Blob, &a.ExpiresAt, &a.CreatedAt)
	return a, pgErr(err)
}

func (p *PostgresStore) DeleteAdvisor(ctx context.Context, id, accountID string) error {
	tag, err := p.pool.Exec(ctx, `DELETE FROM advisor_links WHERE id=$1 AND account_id=$2`, id, accountID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *PostgresStore) PurgeExpired(ctx context.Context, now time.Time) error {
	return p.tx(ctx, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, `DELETE FROM auth_codes WHERE expires_at <= $1`, now); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM advisor_links WHERE expires_at <= $1`, now); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM connect_states WHERE expires_at <= $1`, now); err != nil {
			return err
		}
		_, err := tx.Exec(ctx, `DELETE FROM invites WHERE expires_at < $1`, now.Add(-inviteRetention))
		return err
	})
}

const connCols = `id, account_id, provider, institution, status, consent_expiry, secret, cursor, last_synced_at, created_at, updated_at`

func scanConn(row pgx.Row) (BankConnection, error) {
	var c BankConnection
	err := row.Scan(&c.ID, &c.AccountID, &c.Provider, &c.Institution, &c.Status, &c.ConsentExpiry, &c.Secret,
		&c.Cursor, &c.LastSyncedAt, &c.CreatedAt, &c.UpdatedAt)
	return c, err
}

func nonNil(b []byte) []byte {
	if b == nil {
		return []byte{}
	}
	return b
}

func (p *PostgresStore) CreateConnection(ctx context.Context, c BankConnection) error {
	_, err := p.pool.Exec(ctx, `INSERT INTO bank_connections(`+connCols+`) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)`,
		c.ID, c.AccountID, c.Provider, c.Institution, c.Status, c.ConsentExpiry, c.Secret, nonNil(c.Cursor),
		c.LastSyncedAt, c.CreatedAt, c.UpdatedAt)
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23503" {
		return ErrNotFound
	}
	return pgErr(err)
}

func (p *PostgresStore) GetConnection(ctx context.Context, id string) (BankConnection, error) {
	c, err := scanConn(p.pool.QueryRow(ctx, `SELECT `+connCols+` FROM bank_connections WHERE id=$1`, id))
	return c, pgErr(err)
}

func (p *PostgresStore) ListConnections(ctx context.Context, accountID string) ([]BankConnection, error) {
	rows, err := p.pool.Query(ctx, `SELECT `+connCols+` FROM bank_connections WHERE account_id=$1 ORDER BY created_at, id`, accountID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (BankConnection, error) { return scanConn(r) })
}

func (p *PostgresStore) UpdateConnection(ctx context.Context, c BankConnection) error {
	tag, err := p.pool.Exec(ctx, `UPDATE bank_connections SET institution=$3, status=$4, consent_expiry=$5, secret=$6,
		cursor=$7, last_synced_at=$8, updated_at=$9 WHERE id=$1 AND account_id=$2`,
		c.ID, c.AccountID, c.Institution, c.Status, c.ConsentExpiry, c.Secret, nonNil(c.Cursor), c.LastSyncedAt, c.UpdatedAt)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *PostgresStore) DeleteConnection(ctx context.Context, accountID, id string) error {
	// connect_states referencing the connection cascade.
	tag, err := p.pool.Exec(ctx, `DELETE FROM bank_connections WHERE id=$1 AND account_id=$2`, id, accountID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *PostgresStore) PutConnectState(ctx context.Context, st ConnectState) error {
	var connID *string
	if st.ConnectionID != "" {
		connID = &st.ConnectionID
	}
	_, err := p.pool.Exec(ctx, `INSERT INTO connect_states(state_hash, account_id, provider, connection_id, redirect_uri, expires_at)
		VALUES($1,$2,$3,$4,$5,$6)`, st.StateHash, st.AccountID, st.Provider, connID, st.RedirectURI, st.ExpiresAt)
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23503" {
		return ErrNotFound
	}
	return pgErr(err)
}

func (p *PostgresStore) TakeConnectState(ctx context.Context, stateHash, accountID string) (ConnectState, error) {
	st := ConnectState{StateHash: stateHash, AccountID: accountID}
	err := p.pool.QueryRow(ctx, `DELETE FROM connect_states WHERE state_hash=$1 AND account_id=$2
		RETURNING provider, coalesce(connection_id, ''), redirect_uri, expires_at`, stateHash, accountID).
		Scan(&st.Provider, &st.ConnectionID, &st.RedirectURI, &st.ExpiresAt)
	return st, pgErr(err)
}

func (p *PostgresStore) GetEntitlement(ctx context.Context, accountID string) (Entitlement, error) {
	e := Entitlement{AccountID: accountID}
	err := p.pool.QueryRow(ctx, `SELECT store, product_id, token_hash, plus_until, updated_at FROM entitlements WHERE account_id=$1`, accountID).
		Scan(&e.Store, &e.ProductID, &e.TokenHash, &e.PlusUntil, &e.UpdatedAt)
	return e, pgErr(err)
}

func (p *PostgresStore) PutEntitlement(ctx context.Context, e Entitlement) error {
	_, err := p.pool.Exec(ctx, `INSERT INTO entitlements(account_id, store, product_id, token_hash, plus_until, updated_at)
		VALUES($1,$2,$3,$4,$5,$6)
		ON CONFLICT (account_id) DO UPDATE SET store=EXCLUDED.store, product_id=EXCLUDED.product_id,
		token_hash=EXCLUDED.token_hash, plus_until=EXCLUDED.plus_until, updated_at=EXCLUDED.updated_at`,
		e.AccountID, e.Store, e.ProductID, e.TokenHash, e.PlusUntil, e.UpdatedAt)
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23503" {
		return ErrNotFound
	}
	return err
}
