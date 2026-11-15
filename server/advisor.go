package main

import (
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"net/http"
	"strings"
	"time"
)

const (
	maxAdvisorBlob = 256 << 10
	maxAdvisorTTL  = 90 * 24 * time.Hour
	minAdvisorBlob = 12 + 16 + 1 // IV + GCM tag + at least one byte
)

type advisorPutReq struct {
	Blob      string `json:"blob"`
	ExpiresAt int64  `json:"expiresAt"`
}

type advisorBlobResp struct {
	Blob      string `json:"blob"`
	ExpiresAt int64  `json:"expiresAt"`
}

func (s *Server) putAdvisor(w http.ResponseWriter, r *http.Request, c caller) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var req advisorPutReq
	if !decodeJSON(w, r, &req, 2*maxAdvisorBlob) {
		return
	}
	blob, err := decodeB64(req.Blob)
	if err != nil || len(blob) < minAdvisorBlob || len(blob) > maxAdvisorBlob {
		writeErr(w, http.StatusBadRequest, "blob must be base64url IV||ciphertext, at most 256 KiB")
		return
	}
	now := s.now()
	exp := time.UnixMilli(req.ExpiresAt)
	if !exp.After(now) || exp.Sub(now) > maxAdvisorTTL {
		writeErr(w, http.StatusBadRequest, "expiresAt must be in the future and within 90 days")
		return
	}
	err = s.store.PutAdvisor(r.Context(), AdvisorLink{ID: id, AccountID: c.AccountID, Blob: blob, ExpiresAt: exp, CreatedAt: now})
	if errors.Is(err, ErrForbidden) {
		writeErr(w, http.StatusForbidden, "this link id belongs to another account")
		return
	} else if err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) deleteAdvisor(w http.ResponseWriter, r *http.Request, c caller) {
	if err := s.store.DeleteAdvisor(r.Context(), r.PathValue("id"), c.AccountID); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) advisorBlob(w http.ResponseWriter, r *http.Request) {
	a, err := s.store.GetAdvisor(r.Context(), r.PathValue("id"))
	if errors.Is(err, ErrNotFound) || (err == nil && !s.now().Before(a.ExpiresAt)) {
		writeErr(w, http.StatusNotFound, "link expired or removed")
		return
	} else if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, advisorBlobResp{Blob: b64.EncodeToString(a.Blob), ExpiresAt: a.ExpiresAt.UnixMilli()})
}

func (s *Server) advisorPage(w http.ResponseWriter, r *http.Request) {
	if !idRe.MatchString(r.PathValue("id")) {
		writeErr(w, http.StatusNotFound, "not found")
		return
	}
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Content-Security-Policy", advisorCSP)
	h.Set("X-Frame-Options", "DENY")
	h.Set("X-Robots-Tag", "noindex, nofollow")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(advisorHTML))
}

// The page decrypts in the browser; the key stays in the URL fragment, which browsers never send.
// The CSP pins the one inline script by hash and only allows fetches back to this origin.
var (
	advisorHTML = strings.ReplaceAll(advisorHead+"<script>"+advisorScript+"</script></body></html>", "{{BRAND}}", brandName)
	advisorCSP  = "default-src 'none'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; script-src 'sha256-" +
		cspHash(strings.ReplaceAll(advisorScript, "{{BRAND}}", brandName)) + "'"
)

func cspHash(s string) string {
	h := sha256.Sum256([]byte(s))
	return base64.StdEncoding.EncodeToString(h[:])
}

const advisorHead = `<!doctype html>
<html lang="en" dir="auto">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta name="robots" content="noindex,nofollow">
<title>Shared summary · {{BRAND}}</title>
<style>
:root{--bg:#fbfaf7;--fg:#1b1b1f;--muted:#55555c;--line:#d9d6cf;--card:#fff;--accent:#24614f}
@media (prefers-color-scheme:dark){:root{--bg:#121214;--fg:#ececf0;--muted:#a9a9b2;--line:#34343a;--card:#1b1b1f;--accent:#7fd1b5}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:44rem;margin:0 auto;padding:1.5rem 1rem 3rem}
.wm{display:inline-block;margin:0 0 1rem;padding:.25rem .6rem;border:1px solid var(--line);border-radius:999px;color:var(--muted);font-size:.875rem}
h1{font-size:1.5rem;line-height:1.25;margin:0 0 .25rem}
.meta{color:var(--muted);margin:0 0 1.25rem;font-size:.9375rem}
table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:8px;overflow:hidden}
caption{text-align:start;color:var(--muted);padding:0 0 .5rem;font-size:.875rem}
th,td{padding:.75rem 1rem;border-bottom:1px solid var(--line);text-align:start;vertical-align:top}
td.v{text-align:end;font-variant-numeric:tabular-nums;white-space:nowrap}
tr:last-child td{border-bottom:0}
thead th{font-size:.8125rem;color:var(--muted);font-weight:600}
.note{margin-top:1.25rem;white-space:pre-wrap}
.status{padding:1rem;border:1px solid var(--line);border-radius:8px;background:var(--card)}
footer{margin-top:2rem;color:var(--muted);font-size:.8125rem}
</style>
</head>
<body>
<main>
<p class="wm">Read-only · shared from {{BRAND}}</p>
<h1 id="title">Opening shared summary…</h1>
<p class="meta" id="meta"></p>
<div class="status" id="status" role="status" aria-live="polite">Decrypting on this device…</div>
<table id="tbl" hidden>
<caption id="cap">Summary values</caption>
<thead><tr><th scope="col">Item</th><th scope="col" style="text-align:end">Value</th></tr></thead>
<tbody id="rows"></tbody>
</table>
<p class="note" id="note" hidden></p>
<footer>Decrypted in your browser. The server that delivered this page cannot read it.</footer>
</main>
`

const advisorScript = `(async function(){
var $=function(i){return document.getElementById(i)};
function fail(m){$('title').textContent='Summary unavailable';$('status').textContent=m;}
function unb64(s){s=s.replace(/=+$/,'').replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';var r=atob(s),u=new Uint8Array(r.length);for(var i=0;i<r.length;i++)u[i]=r.charCodeAt(i);return u;}
function when(v){if(v===undefined||v===null||v==='')return '';var d=typeof v==='number'?new Date(v):new Date(String(v));return isNaN(d)?String(v):d.toLocaleString();}
var id=location.pathname.split('/').pop();
var k=location.hash.slice(1);
if(!k){fail('This link is missing its key. Ask the sender for the full link.');return;}
var res;
try{res=await fetch('/v1/advisor/'+encodeURIComponent(id)+'/blob',{cache:'no-store',credentials:'omit',referrerPolicy:'no-referrer'});}
catch(e){fail('You appear to be offline. Reconnect and reload this page.');return;}
if(res.status===404){fail('This link has expired or was removed by its owner.');return;}
if(!res.ok){fail('Could not load the summary. Try again later.');return;}
try{
var j=await res.json(),raw=unb64(j.blob),key=unb64(k);
if(key.length!==32||raw.length<29)throw new Error('format');
var ck=await crypto.subtle.importKey('raw',key,{name:'AES-GCM'},false,['decrypt']);
var pt=await crypto.subtle.decrypt({name:'AES-GCM',iv:raw.slice(0,12)},ck,raw.slice(12));
var d=JSON.parse(new TextDecoder().decode(pt));
$('title').textContent=d.title||'Shared summary';
document.title=(d.title||'Shared summary')+' · {{BRAND}}';
var meta=[];if(d.generatedAt)meta.push('Generated '+when(d.generatedAt));if(d.currency)meta.push('Currency '+d.currency);if(j.expiresAt)meta.push('Link expires '+when(j.expiresAt));
$('meta').textContent=meta.join(' · ');
var tb=$('rows');(d.rows||[]).forEach(function(r){var tr=document.createElement('tr'),th=document.createElement('th'),td=document.createElement('td');th.scope='row';th.textContent=String(r.label==null?'':r.label);td.className='v';td.textContent=String(r.value==null?'':r.value);tr.appendChild(th);tr.appendChild(td);tb.appendChild(tr);});
$('cap').textContent=(d.title||'Summary')+(d.currency?' ('+d.currency+')':'');
if(d.note){$('note').textContent=d.note;$('note').hidden=false;}
$('tbl').hidden=false;$('status').hidden=true;
}catch(e){fail('Could not decrypt this summary. Check that the whole link was copied.');}
})();`
