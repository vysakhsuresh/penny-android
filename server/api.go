package main

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"time"
)

const (
	brandName       = "Penny"
	smallBody       = 64 << 10
	maxPushBody     = 2 << 20
	maxPushBlobs    = 1000
	defaultPull     = 200
	maxPull         = 500
	maxSpaceMembers = 10
	maxWrappedKey   = 8 << 10
)

var idRe = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)

// Server is the relay HTTP API.
type Server struct {
	store      Store
	mailer     Mailer
	log        *slog.Logger
	region     string
	pepper     []byte
	trustProxy bool
	now        func() time.Time

	startByEmail *rateLimiter
	startByIP    *rateLimiter
	verifyByIP   *rateLimiter
}

type Options struct {
	Store      Store
	Mailer     Mailer
	Log        *slog.Logger
	Region     string
	Pepper     []byte
	TrustProxy bool
	Now        func() time.Time
}

func NewServer(o Options) *Server {
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.Log == nil {
		o.Log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Server{
		store: o.Store, mailer: o.Mailer, log: o.Log, region: o.Region, pepper: o.Pepper, trustProxy: o.TrustProxy, now: o.Now,
		startByEmail: newRateLimiter(2*time.Minute, 3, o.Now), // 3 codes, then one per 2 minutes
		startByIP:    newRateLimiter(10*time.Second, 20, o.Now),
		verifyByIP:   newRateLimiter(2*time.Second, 30, o.Now),
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "region": s.region})
	})

	mux.HandleFunc("POST /v1/auth/start", s.authStart)
	mux.HandleFunc("POST /v1/auth/verify", s.authVerify)

	mux.HandleFunc("POST /v1/streams/{stream}/events", s.authed(s.push))
	mux.HandleFunc("GET /v1/streams/{stream}/events", s.authed(s.pull))

	mux.HandleFunc("PUT /v1/keys/{stream}/{accountId}", s.authed(s.putKey))
	mux.HandleFunc("GET /v1/keys", s.authed(s.listKeys))

	mux.HandleFunc("POST /v1/spaces", s.authed(s.createSpace))
	mux.HandleFunc("POST /v1/spaces/{space}/invites", s.authed(s.createInvite))
	mux.HandleFunc("GET /v1/spaces/{space}/members", s.authed(s.listMembers))
	mux.HandleFunc("PUT /v1/spaces/{space}/members/{account}", s.authed(s.changeRole))
	mux.HandleFunc("DELETE /v1/spaces/{space}/members/{account}", s.authed(s.removeMember))

	mux.HandleFunc("GET /v1/invites/{token}", s.getInvite) // public: shows what will be shared
	mux.HandleFunc("POST /v1/invites/{token}/accept", s.authed(s.acceptInvite))
	mux.HandleFunc("DELETE /v1/invites/{token}", s.authed(s.revokeInvite))

	mux.HandleFunc("GET /v1/devices", s.authed(s.listDevices))
	mux.HandleFunc("DELETE /v1/devices/{id}", s.authed(s.revokeDevice))
	mux.HandleFunc("DELETE /v1/account", s.authed(s.deleteAccount))

	mux.HandleFunc("PUT /v1/advisor/{id}", s.authed(s.putAdvisor))
	mux.HandleFunc("DELETE /v1/advisor/{id}", s.authed(s.deleteAdvisor))
	mux.HandleFunc("GET /v1/advisor/{id}/blob", cors(s.advisorBlob))
	mux.HandleFunc("OPTIONS /v1/advisor/{id}/blob", cors(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	mux.HandleFunc("GET /a/{id}", cors(s.advisorPage))

	return s.middleware(mux)
}

// middleware adds security headers, panic recovery and request logging. It logs the route
// pattern, never the raw path, so invite tokens and ids in URLs stay out of logs.
func (s *Server) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		h := rec.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-store")
		defer func() {
			if p := recover(); p != nil {
				s.log.Error("panic", "route", r.Pattern, "panic", p)
				writeErr(rec, http.StatusInternalServerError, "internal error")
			}
			s.log.Info("request", "method", r.Method, "route", r.Pattern, "status", rec.status,
				"bytes", rec.bytes, "dur_ms", time.Since(start).Milliseconds())
		}()
		next.ServeHTTP(rec, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (r *statusRecorder) WriteHeader(code int) { r.status = code; r.ResponseWriter.WriteHeader(code) }
func (r *statusRecorder) Write(b []byte) (int, error) {
	n, err := r.ResponseWriter.Write(b)
	r.bytes += n
	return n, err
}

func cors(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
		w.Header().Set("Access-Control-Max-Age", "600")
		h(w, r)
	}
}

// ---- JSON helpers ----

type errorBody struct {
	Error string `json:"error"`
}

// apiError carries a specific status and message through store callbacks.
type apiError struct {
	status int
	msg    string
}

func (e apiError) Error() string { return e.msg }

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, errorBody{Error: msg})
}

func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	var ae apiError
	switch {
	case errors.As(err, &ae):
		writeErr(w, ae.status, ae.msg)
	case errors.Is(err, ErrNotFound):
		writeErr(w, http.StatusNotFound, "not found")
	case errors.Is(err, ErrForbidden):
		writeErr(w, http.StatusForbidden, "forbidden")
	case errors.Is(err, ErrConflict):
		writeErr(w, http.StatusConflict, "conflict")
	case errors.Is(err, ErrGone):
		writeErr(w, http.StatusGone, "gone")
	case errors.Is(err, ErrFull):
		writeErr(w, http.StatusConflict, "space is full")
	default:
		s.log.Error("internal error", "route", r.Pattern, "err", err)
		writeErr(w, http.StatusInternalServerError, "internal error")
	}
}

func decodeJSON(w http.ResponseWriter, r *http.Request, v any, limit int64) bool {
	err := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit)).Decode(v)
	if err == nil {
		return true
	}
	var mbe *http.MaxBytesError
	if errors.As(err, &mbe) {
		writeErr(w, http.StatusRequestEntityTooLarge, "request body too large")
	} else {
		writeErr(w, http.StatusBadRequest, "invalid JSON body")
	}
	return false
}

func pathID(w http.ResponseWriter, r *http.Request, name string) (string, bool) {
	v := r.PathValue(name)
	if !idRe.MatchString(v) {
		writeErr(w, http.StatusBadRequest, "invalid "+name)
		return "", false
	}
	return v, true
}

func validRole(r string) bool {
	switch r {
	case RoleOwner, RoleAdmin, RoleEditor, RoleContributor, RoleViewer, RoleNone:
		return true
	}
	return false
}

func isManager(role string) bool { return role == RoleOwner || role == RoleAdmin }

// ---- streams ----

type pushReq struct {
	Blobs []string `json:"blobs"`
}

type pushResp struct {
	Cursor int64 `json:"cursor"`
}

type pulledBlob struct {
	Cursor int64  `json:"cursor"`
	Blob   string `json:"blob"`
	Device string `json:"device"`
}

// access reports what the account may do on a stream: owner of a personal stream, or an active
// space member whose role is not NONE (VIEWER read-only). Content roles are enforced client-side.
func (s *Server) access(r *http.Request, st Stream, accountID string) (read, write bool, err error) {
	if st.Kind == StreamPersonal {
		own := st.Owner == accountID
		return own, own, nil
	}
	m, err := s.store.GetMember(r.Context(), st.ID, accountID)
	if errors.Is(err, ErrNotFound) {
		return false, false, nil
	} else if err != nil {
		return false, false, err
	}
	if m.Left || m.Role == RoleNone {
		return false, false, nil
	}
	return true, m.Role != RoleViewer, nil
}

func (s *Server) push(w http.ResponseWriter, r *http.Request, c caller) {
	id, ok := pathID(w, r, "stream")
	if !ok {
		return
	}
	var req pushReq
	if !decodeJSON(w, r, &req, maxPushBody) {
		return
	}
	if len(req.Blobs) == 0 || len(req.Blobs) > maxPushBlobs {
		writeErr(w, http.StatusBadRequest, "blobs must contain 1 to 1000 items")
		return
	}
	blobs := make([][]byte, len(req.Blobs))
	for i, b := range req.Blobs {
		raw, err := decodeB64(b)
		if err != nil || len(raw) == 0 {
			writeErr(w, http.StatusBadRequest, "blobs["+strconv.Itoa(i)+"] is not non-empty base64url")
			return
		}
		blobs[i] = raw
	}
	st, err := s.store.EnsurePersonalStream(r.Context(), id, c.AccountID, s.now())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	_, canWrite, err := s.access(r, st, c.AccountID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !canWrite {
		writeErr(w, http.StatusForbidden, "no write access to this stream")
		return
	}
	cur, err := s.store.AppendEvents(r.Context(), id, c.DeviceID, blobs, s.now())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, pushResp{Cursor: cur})
}

func (s *Server) pull(w http.ResponseWriter, r *http.Request, c caller) {
	id, ok := pathID(w, r, "stream")
	if !ok {
		return
	}
	q := r.URL.Query()
	after, limit := int64(0), defaultPull
	if v := q.Get("after"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n < 0 {
			writeErr(w, http.StatusBadRequest, "after must be a non-negative integer")
			return
		}
		after = n
	}
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			writeErr(w, http.StatusBadRequest, "limit must be a positive integer")
			return
		}
		limit = min(n, maxPull)
	}
	st, err := s.store.GetStream(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		// A profile stream that nobody has pushed to yet: nothing to pull.
		writeJSON(w, http.StatusOK, []pulledBlob{})
		return
	} else if err != nil {
		s.fail(w, r, err)
		return
	}
	canRead, _, err := s.access(r, st, c.AccountID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !canRead {
		writeErr(w, http.StatusForbidden, "no read access to this stream")
		return
	}
	evs, err := s.store.ListEvents(r.Context(), id, after, limit)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]pulledBlob, len(evs))
	for i, e := range evs {
		out[i] = pulledBlob{Cursor: e.Cursor, Blob: b64.EncodeToString(e.Blob), Device: e.Device}
	}
	writeJSON(w, http.StatusOK, out)
}

// ---- keys ----

type streamKeyJSON struct {
	Stream    string `json:"stream"`
	AccountID string `json:"accountId"`
	Wrapped   string `json:"wrapped"`
	Kind      string `json:"kind"`
}

func (s *Server) putKey(w http.ResponseWriter, r *http.Request, c caller) {
	streamID, ok := pathID(w, r, "stream")
	if !ok {
		return
	}
	target, ok := pathID(w, r, "accountId")
	if !ok {
		return
	}
	var req streamKeyJSON
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	switch {
	case (req.Stream != "" && req.Stream != streamID) || (req.AccountID != "" && req.AccountID != target):
		writeErr(w, http.StatusBadRequest, "body does not match path")
		return
	case req.Wrapped == "" || len(req.Wrapped) > maxWrappedKey:
		writeErr(w, http.StatusBadRequest, "wrapped must be 1 to 8192 characters")
		return
	case len(req.Kind) > 64:
		writeErr(w, http.StatusBadRequest, "kind too long")
		return
	}
	ctx := r.Context()
	st, err := s.store.GetStream(ctx, streamID)
	if errors.Is(err, ErrNotFound) && target == c.AccountID {
		st, err = s.store.EnsurePersonalStream(ctx, streamID, c.AccountID, s.now())
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if st.Kind == StreamPersonal {
		if st.Owner != c.AccountID || target != c.AccountID {
			writeErr(w, http.StatusForbidden, "only the owner may store keys for a personal stream")
			return
		}
	} else {
		tm, err := s.store.GetMember(ctx, streamID, target)
		if errors.Is(err, ErrNotFound) || (err == nil && tm.Left) {
			writeErr(w, http.StatusNotFound, "account is not a member of this space")
			return
		} else if err != nil {
			s.fail(w, r, err)
			return
		}
		if target != c.AccountID {
			me, err := s.store.GetMember(ctx, streamID, c.AccountID)
			if err != nil || me.Left || !isManager(me.Role) {
				writeErr(w, http.StatusForbidden, "only space owners and admins may store keys for others")
				return
			}
		}
	}
	if err := s.store.PutKey(ctx, StreamKey{Stream: streamID, AccountID: target, Wrapped: req.Wrapped, Kind: req.Kind}, s.now()); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) listKeys(w http.ResponseWriter, r *http.Request, c caller) {
	keys, err := s.store.ListKeys(r.Context(), c.AccountID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]streamKeyJSON, len(keys))
	for i, k := range keys {
		out[i] = streamKeyJSON{Stream: k.Stream, AccountID: k.AccountID, Wrapped: k.Wrapped, Kind: k.Kind}
	}
	writeJSON(w, http.StatusOK, out)
}

// ---- spaces, invites and members ----

type spaceCreateReq struct {
	SpaceID string `json:"spaceId"`
}

type inviteCreateReq struct {
	Role          string `json:"role"`
	ExpiresInDays int    `json:"expiresInDays"`
	Summary       string `json:"summary"`
}

type inviteInfo struct {
	Token            string `json:"token"`
	SpaceID          string `json:"spaceId"`
	Role             string `json:"role"`
	ExpiresAt        int64  `json:"expiresAt"`
	Summary          string `json:"summary"`
	InviterPublicKey string `json:"inviterPublicKey"`
}

type inviteAcceptReq struct {
	PublicKey string `json:"publicKey"`
}

type memberInfo struct {
	AccountID string `json:"accountId"`
	Role      string `json:"role"`
	PublicKey string `json:"publicKey"`
	JoinedAt  int64  `json:"joinedAt"`
	Left      bool   `json:"left"`
}

type roleChangeReq struct {
	Role string `json:"role"`
}

func toInviteInfo(token string, inv Invite) inviteInfo {
	return inviteInfo{Token: token, SpaceID: inv.SpaceID, Role: inv.Role, ExpiresAt: inv.ExpiresAt.UnixMilli(),
		Summary: inv.Summary, InviterPublicKey: inv.InviterPublicKey}
}

func (s *Server) createSpace(w http.ResponseWriter, r *http.Request, c caller) {
	var req spaceCreateReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	if !idRe.MatchString(req.SpaceID) {
		writeErr(w, http.StatusBadRequest, "invalid spaceId")
		return
	}
	err := s.store.CreateSpace(r.Context(), req.SpaceID, Member{AccountID: c.AccountID, Role: RoleOwner, PublicKey: c.PublicKey, JoinedAt: s.now()})
	if errors.Is(err, ErrConflict) {
		writeErr(w, http.StatusConflict, "a stream with this id already exists")
		return
	} else if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, spaceCreateReq{SpaceID: req.SpaceID})
}

// activeMember returns the caller's active membership, or an apiError (403 for non-members).
func (s *Server) activeMember(r *http.Request, spaceID, accountID string) (Member, error) {
	m, err := s.store.GetMember(r.Context(), spaceID, accountID)
	if errors.Is(err, ErrNotFound) || (err == nil && (m.Left || m.Role == RoleNone)) {
		return Member{}, apiError{http.StatusForbidden, "not a member of this space"}
	}
	return m, err
}

func (s *Server) createInvite(w http.ResponseWriter, r *http.Request, c caller) {
	spaceID, ok := pathID(w, r, "space")
	if !ok {
		return
	}
	var req inviteCreateReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	if req.ExpiresInDays == 0 {
		req.ExpiresInDays = 7
	}
	switch {
	case req.Role != RoleAdmin && req.Role != RoleEditor && req.Role != RoleContributor && req.Role != RoleViewer:
		writeErr(w, http.StatusBadRequest, "role must be ADMIN, EDITOR, CONTRIBUTOR or VIEWER")
		return
	case req.ExpiresInDays < 1 || req.ExpiresInDays > 30:
		writeErr(w, http.StatusBadRequest, "expiresInDays must be between 1 and 30")
		return
	case len(req.Summary) > 1000:
		writeErr(w, http.StatusBadRequest, "summary too long")
		return
	}
	me, err := s.activeMember(r, spaceID, c.AccountID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !isManager(me.Role) {
		writeErr(w, http.StatusForbidden, "only owners and admins may invite")
		return
	}
	now := s.now()
	token := randomB64(24)
	inv := Invite{TokenHash: sha256Hex(token), SpaceID: spaceID, Role: req.Role, Summary: req.Summary,
		InviterAccount: c.AccountID, InviterPublicKey: c.PublicKey, CreatedAt: now,
		ExpiresAt: now.Add(time.Duration(req.ExpiresInDays) * 24 * time.Hour)}
	if err := s.store.CreateInvite(r.Context(), inv); err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, toInviteInfo(token, inv))
}

// usableInvite loads an invite by token and checks it can still be accepted.
func (s *Server) usableInvite(r *http.Request, token string) (Invite, error) {
	inv, err := s.store.GetInvite(r.Context(), sha256Hex(token))
	if err != nil {
		return inv, err
	}
	switch {
	case inv.Revoked:
		return inv, apiError{http.StatusGone, "invite was revoked"}
	case inv.AcceptedBy != "":
		return inv, apiError{http.StatusGone, "invite was already used"}
	case !s.now().Before(inv.ExpiresAt):
		return inv, apiError{http.StatusGone, "invite has expired"}
	}
	return inv, nil
}

func (s *Server) getInvite(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	inv, err := s.usableInvite(r, token)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, toInviteInfo(token, inv))
}

func (s *Server) acceptInvite(w http.ResponseWriter, r *http.Request, c caller) {
	token := r.PathValue("token")
	var req inviteAcceptReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	if len(req.PublicKey) > 2048 {
		writeErr(w, http.StatusBadRequest, "publicKey too long")
		return
	}
	if req.PublicKey == "" {
		req.PublicKey = c.PublicKey
	}
	if _, err := s.usableInvite(r, token); err != nil {
		s.fail(w, r, err)
		return
	}
	inv, err := s.store.AcceptInvite(r.Context(), sha256Hex(token), Member{AccountID: c.AccountID, PublicKey: req.PublicKey}, s.now(), maxSpaceMembers)
	switch {
	case errors.Is(err, ErrConflict):
		writeErr(w, http.StatusConflict, "already a member of this space")
	case errors.Is(err, ErrFull):
		writeErr(w, http.StatusConflict, "space has reached its member limit")
	case errors.Is(err, ErrGone):
		writeErr(w, http.StatusGone, "invite is no longer valid")
	case err != nil:
		s.fail(w, r, err)
	default:
		writeJSON(w, http.StatusOK, toInviteInfo(token, inv))
	}
}

func (s *Server) revokeInvite(w http.ResponseWriter, r *http.Request, c caller) {
	th := sha256Hex(r.PathValue("token"))
	inv, err := s.store.GetInvite(r.Context(), th)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	me, err := s.activeMember(r, inv.SpaceID, c.AccountID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !isManager(me.Role) {
		writeErr(w, http.StatusForbidden, "only owners and admins may revoke invites")
		return
	}
	if err := s.store.RevokeInvite(r.Context(), th); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) listMembers(w http.ResponseWriter, r *http.Request, c caller) {
	spaceID, ok := pathID(w, r, "space")
	if !ok {
		return
	}
	if _, err := s.activeMember(r, spaceID, c.AccountID); err != nil {
		s.fail(w, r, err)
		return
	}
	ms, err := s.store.ListMembers(r.Context(), spaceID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]memberInfo, len(ms))
	for i, m := range ms {
		out[i] = memberInfo{AccountID: m.AccountID, Role: m.Role, PublicKey: m.PublicKey, JoinedAt: m.JoinedAt.UnixMilli(), Left: m.Left}
	}
	writeJSON(w, http.StatusOK, out)
}

var (
	errNotManager = apiError{http.StatusForbidden, "only owners and admins may manage members"}
	errOwnerOnly  = apiError{http.StatusForbidden, "only an owner may grant, change or remove the OWNER role"}
	errLastOwner  = apiError{http.StatusConflict, "a space must keep at least one owner; transfer ownership first"}
	errNoMember   = apiError{http.StatusNotFound, "member not found"}
)

func activeIdx(ms []Member, accountID string) int {
	if i := findMember(ms, accountID); i >= 0 && !ms[i].Left {
		return i
	}
	return -1
}

func (s *Server) changeRole(w http.ResponseWriter, r *http.Request, c caller) {
	spaceID, ok := pathID(w, r, "space")
	if !ok {
		return
	}
	target, ok := pathID(w, r, "account")
	if !ok {
		return
	}
	var req roleChangeReq
	if !decodeJSON(w, r, &req, smallBody) {
		return
	}
	if !validRole(req.Role) {
		writeErr(w, http.StatusBadRequest, "unknown role")
		return
	}
	err := s.store.UpdateMembers(r.Context(), spaceID, func(ms []Member) ([]Member, error) {
		mi := activeIdx(ms, c.AccountID)
		if mi < 0 {
			return nil, apiError{http.StatusForbidden, "not a member of this space"}
		}
		if !isManager(ms[mi].Role) {
			return nil, errNotManager
		}
		ti := activeIdx(ms, target)
		if ti < 0 {
			return nil, errNoMember
		}
		t := ms[ti]
		if (req.Role == RoleOwner || t.Role == RoleOwner) && ms[mi].Role != RoleOwner {
			return nil, errOwnerOnly
		}
		if t.Role == RoleOwner && req.Role != RoleOwner && countActive(ms, RoleOwner) == 1 {
			return nil, errLastOwner
		}
		t.Role = req.Role
		return []Member{t}, nil
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) removeMember(w http.ResponseWriter, r *http.Request, c caller) {
	spaceID, ok := pathID(w, r, "space")
	if !ok {
		return
	}
	target, ok := pathID(w, r, "account")
	if !ok {
		return
	}
	self := target == c.AccountID
	err := s.store.UpdateMembers(r.Context(), spaceID, func(ms []Member) ([]Member, error) {
		mi := activeIdx(ms, c.AccountID)
		if mi < 0 {
			if self {
				return nil, errNoMember
			}
			return nil, apiError{http.StatusForbidden, "not a member of this space"}
		}
		ti := activeIdx(ms, target)
		if ti < 0 {
			return nil, errNoMember
		}
		t := ms[ti]
		if !self {
			if !isManager(ms[mi].Role) {
				return nil, errNotManager
			}
			if t.Role == RoleOwner && ms[mi].Role != RoleOwner {
				return nil, errOwnerOnly
			}
		}
		if t.Role == RoleOwner && countActive(ms, RoleOwner) == 1 && countActive(ms, "") > 1 {
			return nil, errLastOwner
		}
		t.Left = true
		return []Member{t}, nil
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	// The last person out: nobody can read the space any more, so drop its ciphertext.
	if _, err := s.store.DeleteSpaceIfEmpty(r.Context(), spaceID); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- devices and account ----

type deviceInfo struct {
	DeviceID string `json:"deviceId"`
	Name     string `json:"name"`
	Platform string `json:"platform"`
	LastSeen int64  `json:"lastSeen"`
	Current  bool   `json:"current"`
}

func (s *Server) listDevices(w http.ResponseWriter, r *http.Request, c caller) {
	ds, err := s.store.ListDevices(r.Context(), c.AccountID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]deviceInfo, len(ds))
	for i, d := range ds {
		out[i] = deviceInfo{DeviceID: d.ID, Name: d.Name, Platform: d.Platform, LastSeen: d.LastSeen.UnixMilli(), Current: d.ID == c.DeviceID}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) revokeDevice(w http.ResponseWriter, r *http.Request, c caller) {
	id := r.PathValue("id")
	if err := s.store.RevokeDevice(r.Context(), c.AccountID, id); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// deleteAccount leaves every space (handing ownership on where needed), drops spaces nobody
// else is in, then deletes everything the account owns. Each step is idempotent, so a retry
// after a partial failure completes the job.
func (s *Server) deleteAccount(w http.ResponseWriter, r *http.Request, c caller) {
	ctx := r.Context()
	ms, err := s.store.ListMemberships(ctx, c.AccountID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	for _, m := range ms {
		err := s.store.UpdateMembers(ctx, m.SpaceID, func(all []Member) ([]Member, error) {
			i := activeIdx(all, c.AccountID)
			if i < 0 {
				return nil, nil
			}
			me := all[i]
			me.Left = true
			changed := []Member{me}
			if me.Role == RoleOwner && countActive(all, RoleOwner) == 1 {
				if heir := pickHeir(all, c.AccountID); heir >= 0 {
					h := all[heir]
					h.Role = RoleOwner
					changed = append(changed, h)
				}
			}
			return changed, nil
		})
		if err != nil && !errors.Is(err, ErrNotFound) {
			s.fail(w, r, err)
			return
		}
		if _, err := s.store.DeleteSpaceIfEmpty(ctx, m.SpaceID); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	if err := s.store.DeleteAccount(ctx, c.AccountID); err != nil {
		s.fail(w, r, err)
		return
	}
	s.log.Info("account deleted", "spaces", len(ms))
	w.WriteHeader(http.StatusNoContent)
}

// pickHeir returns the earliest-joined active admin, else the earliest-joined other active
// member. ms is ordered by joinedAt.
func pickHeir(ms []Member, leaving string) int {
	best := -1
	for i, m := range ms {
		if m.Left || m.AccountID == leaving {
			continue
		}
		if m.Role == RoleAdmin {
			return i
		}
		if best < 0 {
			best = i
		}
	}
	return best
}
