/* Penny prototype core: money, dates, periods, event-sourced store, persistence with encryption, domain logic, rules, alerts, importers. No DOM here. */
(function(PO){
const CUR = PO.CUR, pad = n => String(n).padStart(2,'0');
PO.ENT = ['accts','tx','cats','payees','tags','rules','envs','alloc','goals','recur','occ','people','lend','groups','gexp','remit','circles','contrib','watch','saved','links','invites','review','alertlog','attach'];
PO.uid = (p='id') => p + '_' + Date.now().toString(36) + Math.random().toString(36).slice(2,7);
PO.clone = o => o == null ? o : JSON.parse(JSON.stringify(o));

/* ---------- dates (calendar dates as YYYY-MM-DD strings, no timezone math) */
PO.ymd = d => `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`;
PO.today = () => PO.ymd(new Date());
PO.nowHM = () => { const d=new Date(); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
PO.parseYmd = s => { const [y,m,d] = s.split('-').map(Number); return {y,m,d}; };
PO.toDate = s => { const {y,m,d}=PO.parseYmd(s); return new Date(y,m-1,d); };
PO.addDays = (s,n) => { const d=PO.toDate(s); d.setDate(d.getDate()+n); return PO.ymd(d); };
PO.daysBetween = (a,b) => Math.round((PO.toDate(b)-PO.toDate(a))/864e5);
PO.lastDay = (y,m) => new Date(y,m,0).getDate();
PO.mk = (y,m,d) => { while(m>12){m-=12;y++;} while(m<1){m+=12;y--;} return `${y}-${pad(m)}-${pad(Math.min(d,PO.lastDay(y,m)))}`; };
PO.addMonths = (s,n) => { const {y,m,d}=PO.parseYmd(s); return PO.mk(y,m+n,d); };
PO.weekday = s => PO.toDate(s).getDay();
PO.fmtDate = (s, opts) => { try { return new Intl.DateTimeFormat(PO.uiLocale(), opts||{day:'numeric',month:'short'}).format(PO.toDate(s)); } catch(e){ return s; } };
PO.relDate = s => { const t=PO.today(); if(s===t) return PO.t('today'); if(s===PO.addDays(t,-1)) return PO.t('yesterday'); return PO.fmtDate(s, {day:'numeric',month:'short', year: s.slice(0,4)!==t.slice(0,4)?'numeric':undefined}); };

/* ---------- periods */
function dayOrLast(v,y,m){ return v==='last' ? PO.lastDay(y,m) : Math.min(v, PO.lastDay(y,m)); }
PO.periodFor = (s, rule, ctx={}) => {
  const {y,m,d} = PO.parseYmd(s); let start, end;
  switch(rule.type){
    case 'semimonthly': { const [a0,b0]=rule.days||[15,'last']; const A=dayOrLast(a0,y,m), B=dayOrLast(b0,y,m); const lo=Math.min(A,B), hi=Math.max(A,B);
      if(d>=hi){ start=PO.mk(y,m,hi); const ny=m===12?y+1:y, nm=m===12?1:m+1; end=PO.addDays(PO.mk(ny,nm,Math.min(dayOrLast(a0,ny,nm),dayOrLast(b0,ny,nm))),-1); }
      else if(d>=lo){ start=PO.mk(y,m,lo); end=PO.addDays(PO.mk(y,m,hi),-1); }
      else { const py=m===1?y-1:y, pm=m===1?12:m-1; start=PO.mk(py,pm,Math.max(dayOrLast(a0,py,pm),dayOrLast(b0,py,pm))); end=PO.addDays(PO.mk(y,m,lo),-1); }
      break; }
    case 'weekly': { const w=rule.weekday??1; const diff=(PO.weekday(s)-w+7)%7; start=PO.addDays(s,-diff); end=PO.addDays(start,6); break; }
    case 'biweekly': case 'fourweekly': { const len=rule.type==='biweekly'?14:28; const anchor=rule.anchor||'2026-01-05'; const n=Math.floor(PO.daysBetween(anchor,s)/len); start=PO.addDays(anchor,n*len); end=PO.addDays(start,len-1); break; }
    case 'quarterly': case 'yearly': { const [fm,fd]=(rule.fiscal||'01-01').split('-').map(Number); const span=rule.type==='quarterly'?3:12;
      let sy=y, sm=fm; while(PO.mk(sy,sm,fd)>s){ sm-=span; if(sm<1){sm+=12;sy--;} } while(PO.mk(sy,sm+span,fd)<=s){ sm+=span; if(sm>12){sm-=12;sy++;} }
      start=PO.mk(sy,sm,fd); end=PO.addDays(PO.mk(sy,sm+span,fd),-1); break; }
    case 'onincome': { const dates=(ctx.incomeDates||[]).filter(x=>x<=s); if(!dates.length) return PO.periodFor(s,{type:'monthly',day:1}); start=dates[dates.length-1]; const next=(ctx.incomeDates||[]).find(x=>x>start); end=next?PO.addDays(next,-1):PO.addDays(start,30); if(end<s) end=PO.addDays(s,0); break; }
    default: { const sd=rule.day||1; let sy=y, sm=m; if(d<Math.min(sd,PO.lastDay(y,m))){ sm--; if(sm<1){sm=12;sy--;} } start=PO.mk(sy,sm,sd); end=PO.addDays(PO.mk(sy,sm+1,sd),-1); }
  }
  return {start, end, key:start, days:PO.daysBetween(start,end)+1};
};
PO.shiftPeriod = (p, n, rule, ctx) => { let cur=p; for(let i=0;i<Math.abs(n);i++){ cur = PO.periodFor(n>0?PO.addDays(cur.end,1):PO.addDays(cur.start,-1), rule, ctx); } return cur; };
PO.periodLabel = p => `${PO.fmtDate(p.start)} – ${PO.fmtDate(p.end, {day:'numeric',month:'short',year:'numeric'})}`;
PO.cycleName = r => ({monthly:`Monthly from day ${r.day||1}`, semimonthly:`Twice a month (${(r.days||[15,'last']).join(' and ')})`, weekly:'Weekly', biweekly:'Every two weeks', fourweekly:'Every four weeks', quarterly:'Quarterly', yearly:'Yearly', onincome:'When money lands'})[r.type]||r.type;

/* ---------- money */
PO.dec = cur => (CUR[cur]||{d:2}).d;
PO.sym = cur => (CUR[cur]||{s:cur+' '}).s;
PO.toMinor = (major, cur) => Math.round(Number(major) * Math.pow(10, PO.dec(cur)));
PO.toMajor = (minor, cur) => minor / Math.pow(10, PO.dec(cur));
PO.numLocale = () => { const s=PO.state?.settings||{}; const pk=PO.pack(); if(s.numbering==='in' || (s.numbering!=='std' && pk.numbering==='in')) return 'en-IN'; if(s.numbering==='std' && pk.numbering==='in') return 'en-US'; return pk.locale; };
PO.uiLocale = () => { const l=PO.state?.settings?.lang||'en'; return ({en:'en-GB',es:'es',pt:'pt-BR',de:'de',hi:'hi',fil:'fil',ar:'ar',ja:'ja',id:'id'})[l]||'en'; };
PO.decSep = () => { const parts = new Intl.NumberFormat(PO.numLocale()).formatToParts(1.1); return (parts.find(p=>p.type==='decimal')||{value:'.'}).value; };
PO.groupSep = () => { const parts = new Intl.NumberFormat(PO.numLocale()).formatToParts(1000); return (parts.find(p=>p.type==='group')||{value:','}).value; };
PO.fmt = (minor, cur, o={}) => { cur=cur||PO.cur(); if(PO.state?.settings?.hideAmounts && !o.force) return '•••'; const d=PO.dec(cur); const n=Math.abs(minor)/Math.pow(10,d);
  let s; try{ s=new Intl.NumberFormat(PO.numLocale(),{minimumFractionDigits:o.compact?0:d, maximumFractionDigits:d, notation:o.compact?'compact':'standard'}).format(n); }catch(e){ s=n.toFixed(d); }
  return (minor<0?'−':(o.plus&&minor>0?'+':'')) + (o.noSym?'':PO.sym(cur)) + s; };
PO.convert = (minor, from, to) => { if(from===to) return minor; const usd = PO.toMajor(minor,from)/(PO.RATES[from]||1); return PO.toMinor(usd*(PO.RATES[to]||1), to); };
PO.toBase = (minor, cur) => PO.convert(minor, cur, PO.cur());

/* calculator: locale-aware expression evaluation without eval */
PO.evalAmount = str => {
  if(str==null) return NaN; let s=String(str).trim(); if(!s) return NaN;
  const ds=PO.decSep(), gs=PO.groupSep();
  s = s.split(gs).join('').replace(/\s/g,''); if(ds!=='.') s=s.split(ds).join('.'); s=s.replace(/×/g,'*').replace(/÷/g,'/').replace(/−/g,'-');
  const tok=[]; let i=0; while(i<s.length){ const c=s[i]; if(/[0-9.]/.test(c)){ let j=i; while(j<s.length&&/[0-9.]/.test(s[j])) j++; tok.push({n:parseFloat(s.slice(i,j))}); i=j; } else if('+-*/()%'.includes(c)){ tok.push({o:c}); i++; } else return NaN; }
  let p=0; const peek=()=>tok[p], next=()=>tok[p++];
  function prim(){ const t=next(); if(!t) return NaN; if(t.o==='('){ const v=expr(); if(!peek()||next().o!==')') return NaN; return post(v);} if(t.o==='-') return -prim(); if(t.o==='+') return prim(); if(t.n!==undefined) return post(t.n); return NaN; }
  function post(v){ while(peek()&&peek().o==='%'){ next(); v=v/100; } return v; }
  function term(){ let v=prim(); while(peek()&&(peek().o==='*'||peek().o==='/')){ const o=next().o; const r=prim(); v = o==='*'?v*r:v/r; } return v; }
  function expr(){ let v=term(); while(peek()&&(peek().o==='+'||peek().o==='-')){ const o=next().o; const r=term(); v = o==='+'?v+r:v-r; } return v; }
  const v=expr(); if(p!==tok.length) return NaN; return v;
};
PO.validateAmount = (major, cur) => { if(!isFinite(major)) return 'Enter a valid amount'; if(major<=0) return 'Amount must be more than zero'; const d=PO.dec(cur); const scaled=major*Math.pow(10,d); if(Math.abs(scaled-Math.round(scaled))>1e-6) return d===0?`${cur} has no decimals`:`${cur} allows ${d} decimal places`; if(major>=1e13) return 'Amount is too large'; return null; };

/* ---------- state */
PO.newState = () => ({ v:1, login:{id:'u1', name:'You', email:'you@example.com'}, actingAs:'u1', currentProfile:null, profiles:{}, ent:Object.fromEntries(PO.ENT.map(e=>[e,{}])), events:[], seq:0, learn:{},
  settings:{ lang:'en', theme:'system', text:'normal', hideAmounts:false, numbering:'auto', lockPin:null, duressPin:null, autoLock:60, screenshots:true, plus:null, receipts:[], devices:[{id:'d1',name:'This phone',last:'now',current:true},{id:'d2',name:'Laptop (web)',last:'2 days ago'}], recoveryShown:false, notifPermission:'default', quiet:{on:true,from:'21:00',to:'09:00'}, digestDay:1, analytics:false, residency:'auto', capture:{notif:true,voice:true,ocr:true,email:false,location:false}, alertsGlobal:{near:true,over:true,pace:true,bills:true,large:true}, backups:[], onboarded:false } });
PO.state = null;
PO.profile = () => PO.state.profiles[PO.state.currentProfile] || Object.values(PO.state.profiles)[0];
PO.pack = () => PO.PACKS[(PO.profile()||{}).region] || PO.PACKS.XX;
PO.cur = () => (PO.profile()||{}).cur || PO.pack().cur;
PO.t = (k) => { const l=PO.state?.settings?.lang||'en'; return (PO.I18N[l]&&PO.I18N[l][k]) || PO.I18N.en[k] || k; };
PO.isRTL = () => PO.RTL.includes(PO.state?.settings?.lang||'en');
PO.actor = () => { const p=PO.profile(); const m=(p.members||[]).find(x=>x.id===PO.state.actingAs); return m || {id:PO.state.login.id, name:PO.state.login.name, role:'owner'}; };
PO.get = (ent,id) => PO.state.ent[ent][id];
PO.list = (ent, o={}) => { const pid=o.profile||PO.state.currentProfile; return Object.values(PO.state.ent[ent]).filter(x => (o.any || x.profileId===pid) && (o.all || !x.deleted_at)); };
PO.listDeleted = (ent) => Object.values(PO.state.ent[ent]).filter(x=>x.profileId===PO.state.currentProfile && x.deleted_at);

let _group=null, _saveTimer=null; PO.listeners=[];
PO.onChange = fn => PO.listeners.push(fn);
PO.commit = (ent, id, patch, label='') => {
  const col=PO.state.ent[ent]; const before=col[id]?PO.clone(col[id]):null; const now=new Date().toISOString();
  const after = before ? {...before, ...patch, updated_at:now} : {id, profileId:PO.state.currentProfile, created_at:now, updated_at:now, actor:PO.actor().id, ...patch};
  col[id]=after; PO.state.seq++;
  PO.state.events.push({seq:PO.state.seq, ts:now, actor:PO.actor().id, actorName:PO.actor().name, profileId:after.profileId, ent, id, before, after:PO.clone(after), label: label || (before?(patch.deleted_at?'delete':'edit'):'create'), group:_group});
  if(PO.state.events.length>3000) PO.state.events.splice(0, PO.state.events.length-3000);
  PO.save(); return after;
};
PO.remove = (ent,id,label) => PO.commit(ent,id,{deleted_at:new Date().toISOString()}, label||'delete');
PO.restore = (ent,id) => PO.commit(ent,id,{deleted_at:null},'restore');
PO.purge = (ent,id) => { const before=PO.clone(PO.state.ent[ent][id]); delete PO.state.ent[ent][id]; PO.state.seq++; PO.state.events.push({seq:PO.state.seq, ts:new Date().toISOString(), actor:PO.actor().id, actorName:PO.actor().name, profileId:before?.profileId, ent, id, before, after:null, label:'purge', group:_group}); PO.save(); };
PO.group = fn => { const g=PO.uid('g'); const prev=_group; _group=g; try{ fn(); } finally { _group=prev; } return g; };
PO.revertEvent = ev => { const col=PO.state.ent[ev.ent]; if(ev.before) col[ev.id]=PO.clone(ev.before); else delete col[ev.id]; PO.state.seq++; PO.state.events.push({seq:PO.state.seq, ts:new Date().toISOString(), actor:PO.actor().id, actorName:PO.actor().name, profileId:ev.profileId, ent:ev.ent, id:ev.id, before:ev.after, after:PO.clone(ev.before), label:'undo', group:_group}); PO.save(); };
PO.undoGroup = gid => { const evs=PO.state.events.filter(e=>e.group===gid && e.label!=='undo').reverse(); PO.group(()=>evs.forEach(PO.revertEvent)); };
PO.audit = (text) => { const p=PO.profile(); if(!p.audit) p.audit=[]; p.audit.unshift({ts:new Date().toISOString(), actor:PO.actor().name, text}); if(p.audit.length>500) p.audit.length=500; };

/* ---------- persistence and crypto */
const KEY='penny.v1', BKEY='penny.backups', DKEY='penny.duress';
const enc = s => new TextEncoder().encode(s); const b64 = b => btoa(String.fromCharCode(...new Uint8Array(b))); const unb64 = s => Uint8Array.from(atob(s), c=>c.charCodeAt(0));
PO.deriveKey = async (pin, saltB64) => { const salt = saltB64 ? unb64(saltB64) : crypto.getRandomValues(new Uint8Array(16)); const km = await crypto.subtle.importKey('raw', enc(pin), 'PBKDF2', false, ['deriveKey']); const key = await crypto.subtle.deriveKey({name:'PBKDF2', salt, iterations:120000, hash:'SHA-256'}, km, {name:'AES-GCM', length:256}, false, ['encrypt','decrypt']); return {key, salt:b64(salt)}; };
PO.encryptStr = async (key, text) => { const iv=crypto.getRandomValues(new Uint8Array(12)); const ct=await crypto.subtle.encrypt({name:'AES-GCM', iv}, key, enc(text)); return {iv:b64(iv), data:b64(ct)}; };
PO.decryptStr = async (key, iv, data) => new TextDecoder().decode(await crypto.subtle.decrypt({name:'AES-GCM', iv:unb64(iv)}, key, unb64(data)));
PO._key=null; PO._salt=null; PO.decoy=false;
PO.saveNow = async () => { if(!PO.state || PO.decoy) return; try { const json=JSON.stringify(PO.state); if(PO.state.settings.lockPin && PO._key){ const e=await PO.encryptStr(PO._key, json); localStorage.setItem(KEY, JSON.stringify({enc:true, salt:PO._salt, iv:e.iv, data:e.data})); } else localStorage.setItem(KEY, json); PO.saveState='saved'; } catch(e){ PO.saveState='error'; } PO.listeners.forEach(f=>f('saved')); };
PO.save = () => { PO.saveState='pending'; clearTimeout(_saveTimer); _saveTimer=setTimeout(PO.saveNow, 250); };
PO.loadRaw = () => { try { const raw=localStorage.getItem(KEY); return raw?JSON.parse(raw):null; } catch(e){ return null; } };
PO.unlock = async (pin) => { const raw=PO.loadRaw(); if(!raw||!raw.enc) return false; try { const {key}=await PO.deriveKey(pin, raw.salt); const json=await PO.decryptStr(key, raw.iv, raw.data); PO.state=JSON.parse(json); PO._key=key; PO._salt=raw.salt; PO.decoy=false; return true; } catch(e){}
  try { const d=JSON.parse(localStorage.getItem(DKEY)||'null'); if(d){ const {key}=await PO.deriveKey(pin, d.salt); const json=await PO.decryptStr(key, d.iv, d.data); PO.state=JSON.parse(json); PO.decoy=true; return 'duress'; } } catch(e){}
  return false; };
PO.setPin = async (pin) => { if(!pin){ PO.state.settings.lockPin=null; PO._key=null; PO._salt=null; await PO.saveNow(); return; } const {key,salt}=await PO.deriveKey(pin); PO._key=key; PO._salt=salt; PO.state.settings.lockPin='set'; await PO.saveNow(); };
PO.setDuress = async (pin) => { if(!pin){ PO.state.settings.duressPin=null; localStorage.removeItem(DKEY); PO.save(); return; } const decoy=PO.decoyState(); const {key,salt}=await PO.deriveKey(pin); const e=await PO.encryptStr(key, JSON.stringify(decoy)); localStorage.setItem(DKEY, JSON.stringify({salt, iv:e.iv, data:e.data})); PO.state.settings.duressPin='set'; PO.save(); };
PO.backup = (label='Manual') => { const snaps=JSON.parse(localStorage.getItem(BKEY)||'[]'); snaps.unshift({ts:new Date().toISOString(), label, size:JSON.stringify(PO.state).length, data:PO.state}); while(snaps.length>5) snaps.pop(); try{ localStorage.setItem(BKEY, JSON.stringify(snaps)); }catch(e){ return false; } return true; };
PO.backups = () => { try{ return JSON.parse(localStorage.getItem(BKEY)||'[]').map((s,i)=>({i, ts:s.ts, label:s.label, size:s.size})); }catch(e){ return []; } };
PO.restoreBackup = i => { const snaps=JSON.parse(localStorage.getItem(BKEY)||'[]'); if(!snaps[i]) return false; PO.state=snaps[i].data; PO.save(); return true; };
PO.exportJSON = () => JSON.stringify({app:PO.BRAND, schema:1, exported:new Date().toISOString(), state:PO.state}, null, 1);
PO.importJSON = text => { const o=JSON.parse(text); if(!o||o.schema!==1||!o.state||!o.state.ent) throw new Error('Not a Penny export (schema 1 expected)'); PO.state=o.state; PO.save(); };
PO.exportCSV = () => { const rows=[['date','time','type','amount','currency','account','to_account','category','payee','note','tags','status']]; PO.list('tx').forEach(t=>rows.push([t.date,t.time||'',t.kind,PO.toMajor(t.amt,t.cur),t.cur,PO.get('accts',t.acct)?.name||'',PO.get('accts',t.toAcct)?.name||'',PO.catName(t.cat),PO.payeeName(t),(t.note||'').replace(/"/g,'""'),(t.tags||[]).join('|'),t.status||''])); return rows.map(r=>r.map(v=>/[",\n]/.test(String(v))?`"${v}"`:v).join(',')).join('\n'); };
PO.wipeAll = () => { localStorage.removeItem(KEY); localStorage.removeItem(BKEY); localStorage.removeItem(DKEY); };

/* ---------- names and lookups */
PO.catName = id => { const c=id&&PO.get('cats',id); return c?c.name:(id==='uncat'||!id?PO.t('uncategorised'):id); };
PO.catPath = id => { const c=PO.get('cats',id); if(!c) return PO.catName(id); return c.parent? `${PO.catName(c.parent)} › ${c.name}` : c.name; };
PO.catChildren = id => PO.list('cats').filter(c=>c.parent===id);
PO.catDesc = id => { const out=[id]; PO.catChildren(id).forEach(c=>out.push(...PO.catDesc(c.id))); return out; };
PO.catRoot = id => { let c=PO.get('cats',id); while(c&&c.parent){ c=PO.get('cats',c.parent); } return c?c.id:id; };
PO.catExcluded = id => { let c=PO.get('cats',id); while(c){ if(c.excluded) return true; c=c.parent?PO.get('cats',c.parent):null; } return false; };
PO.payeeName = t => t.payeeId ? (PO.get('payees',t.payeeId)?.name || t.payee || '') : (t.payee||'');
PO.acctName = id => PO.get('accts',id)?.name || '';
PO.personName = id => PO.get('people',id)?.name || '';
PO.memberName = id => { const p=PO.profile(); return (p.members||[]).find(m=>m.id===id)?.name || (id===PO.state.login.id?PO.state.login.name:'Member'); };

/* ---------- permissions */
PO.role = () => { const p=PO.profile(); if(p.kind!=='space') return p.supervisorId && PO.state.actingAs!==PO.state.login.id ? 'contributor' : 'owner'; const m=(p.members||[]).find(x=>x.id===PO.state.actingAs); return m?m.role:'viewer'; };
PO.roleFor = (envId) => { const p=PO.profile(); const m=(p.members||[]).find(x=>x.id===PO.state.actingAs); if(m&&m.overrides&&envId&&m.overrides[envId]) return m.overrides[envId]; return PO.role(); };
const RANK={none:0, viewer:1, contributor:2, editor:3, admin:4, owner:5};
PO.can = (action, obj) => { const r=PO.roleFor(obj&&obj.envId); if(r==='none') return false; if(action==='view') return RANK[r]>=1; if(action==='create') return RANK[r]>=2; if(action==='edit'||action==='delete') return RANK[r]>=3 || (r==='contributor' && obj && obj.actor===PO.state.actingAs); if(action==='manage') return RANK[r]>=4; if(action==='own') return r==='owner'; return false; };
PO.visible = t => !(t.private && t.actor!==PO.state.actingAs && PO.profile().kind==='space');

/* ---------- domain: balances, periods, budgets */
PO.cycle = () => PO.profile().cycle || PO.pack().cycle;
PO.incomeDates = () => PO.list('tx').filter(t=>t.kind==='income' && PO.toBase(t.amt,t.cur) >= (PO.cycle().threshold||0)).map(t=>t.date).sort();
PO.ctx = () => ({incomeDates: PO.cycle().type==='onincome' ? PO.incomeDates() : []});
PO.periodOf = s => PO.periodFor(s, PO.cycle(), PO.ctx());
PO.curPeriod = () => PO.periodOf(PO.today());
PO.effects = t => { const out=[]; const a=t.amt; switch(t.kind){ case 'expense': out.push([t.acct,-a]); break; case 'income': out.push([t.acct,a]); break; case 'refund': out.push([t.acct,a]); break; case 'adjustment': out.push([t.acct, t.dir==='in'?a:-a]); break; case 'transfer': out.push([t.acct,-a]); out.push([t.toAcct, t.toAmt!=null?t.toAmt:a]); break; } return out; };
PO.balance = (acctId, asOf) => { const a=PO.get('accts',acctId); if(!a) return 0; let b=a.opening||0; PO.list('tx').forEach(t=>{ if(asOf && t.date>asOf) return; PO.effects(t).forEach(([id,d])=>{ if(id===acctId) b+=d; }); }); return b; };
PO.netWorth = (asOf) => PO.list('accts').filter(a=>!a.archived && a.includeNW!==false).reduce((s,a)=>s+PO.toBase(PO.balance(a.id,asOf),a.cur),0);
PO.spendRows = (p, o={}) => { const rows=[]; PO.list('tx').forEach(t=>{ if(t.date<p.start||t.date>p.end) return; if(t.kind!=='expense'&&t.kind!=='refund') return; if(t.excludeBudget&&!o.includeExcluded) return; const sign=t.kind==='refund'?-1:1; const parts=(t.splits&&t.splits.length)?t.splits:[{cat:t.cat,amt:t.amt}]; parts.forEach(sp=>{ if(PO.catExcluded(sp.cat)&&!o.includeExcluded) return; rows.push({tx:t, cat:sp.cat||'uncat', amt:sign*PO.toBase(sp.amt,t.cur)}); }); }); return rows; };
PO.catSpend = p => { const m={}; PO.spendRows(p).forEach(r=>{ m[r.cat]=(m[r.cat]||0)+r.amt; }); return m; };
PO.spentTotal = p => PO.spendRows(p).reduce((s,r)=>s+r.amt,0);
PO.incomeIn = p => PO.list('tx').filter(t=>t.kind==='income'&&t.date>=p.start&&t.date<=p.end&&!t.excludeBudget).reduce((s,t)=>s+PO.toBase(t.amt,t.cur),0);
PO.envScope = e => { if(e.scope&&e.scope.length) return new Set(e.scope.flatMap(PO.catDesc)); return new Set(PO.catDesc(e.cat)); };
PO.envSpent = (e,p) => { const sc=PO.envScope(e); return PO.spendRows(p).filter(r=>sc.has(r.cat)).reduce((s,r)=>s+r.amt,0); };
PO.envPeriod = (e,p) => e.periodRule ? PO.periodFor(p.start, e.periodRule) : p;
PO.envAlloc = (e,p) => PO.list('alloc').find(a=>a.envId===e.id && a.period===p.key);
PO.envCarry = (e,p,depth=0) => { if(!e.rollover||e.rollover==='none'||depth>12) return 0; const prev=PO.shiftPeriod(p,-1,PO.cycle(),PO.ctx()); if(prev.start < (e.created_at||'').slice(0,10) && !PO.envAlloc(e,prev)) return 0; const limitPrev=PO.envLimit(e,prev,depth+1); const left=limitPrev-PO.envSpent(e,prev); let c = e.rollover==='positive'?Math.max(0,left):left; if(e.cap!=null&&c>e.cap) c=e.cap; return c; };
PO.envLimit = (e,p,depth=0) => { const a=PO.envAlloc(e,p); const base = a?a.assigned:e.limit||0; return base + PO.envCarry(e,p,depth); };
PO.assignedIn = p => PO.list('envs').filter(e=>!e.archived).reduce((s,e)=>{ const a=PO.envAlloc(e,p); return s+(a?a.assigned:e.limit||0); },0);
PO.goalContribIn = p => PO.list('goals').filter(g=>g.status!=='paused'&&g.status!=='done').reduce((s,g)=>s+(g.perPeriod||0),0);
PO.availableToAssign = p => PO.incomeIn(p) - PO.assignedIn(p) - PO.goalContribIn(p);
PO.upcomingBills = p => { PO.list('recur').forEach(PO.ensureOcc); return PO.list('occ').filter(o=>o.status==='upcoming'&&o.date>=PO.today()&&o.date<=p.end).map(o=>({o, r:PO.get('recur',o.recId)})).filter(x=>x.r&&!x.r.deleted_at); };
PO.committed = p => PO.upcomingBills(p).filter(x=>x.r.kind!=='income').reduce((s,x)=>s+PO.toBase(x.o.amt??x.r.amt, x.r.cur),0);
PO.expectedIncome = p => PO.upcomingBills(p).filter(x=>x.r.kind==='income').reduce((s,x)=>s+PO.toBase(x.o.amt??x.r.amt, x.r.cur),0);
PO.safeToSpend = p => { const inc=PO.incomeIn(p)+PO.expectedIncome(p); return inc - PO.committed(p) - PO.goalContribIn(p) - PO.spentTotal(p); };
PO.pace = p => { const t=PO.today(); const elapsed=Math.min(p.days, Math.max(0, PO.daysBetween(p.start,t)+1)); const left=Math.max(0, PO.daysBetween(t,p.end)); return {elapsed, left, share: elapsed/p.days}; };
PO.levelOf = (spent, limit) => limit<=0 ? (spent>0?2:0) : spent/limit>=1?2 : spent/limit>=0.8?1 : spent/limit>=0.5?0.5 : 0;

/* ---------- recurring */
PO.nextDate = (r, from) => { let d=from; const n=r.interval||1; switch(r.freq){ case 'daily': d=PO.addDays(from,n); break; case 'weekly': d=PO.addDays(from,7*n); break; case 'yearly': d=PO.addMonths(from,12*n); break; default: { const {y,m}=PO.parseYmd(from); const day=r.byDay==='last'?'last':(r.byDay||PO.parseYmd(r.start).d); let nm=m+n, ny=y; while(nm>12){nm-=12;ny++;} d=PO.mk(ny,nm, day==='last'?31:day); } } return d; };
PO.ensureOcc = r => { if(r.deleted_at||r.paused) return; const existing=PO.list('occ',{all:true}).filter(o=>o.recId===r.id); let last=existing.map(o=>o.date).sort().pop(); let d = last ? PO.nextDate(r,last) : r.start; let guard=0; const horizon=PO.addDays(PO.today(), 120);
  while(guard++<60){ const upcoming=PO.list('occ').filter(o=>o.recId===r.id&&o.status==='upcoming'&&o.date>=PO.today()).length; if(upcoming>=3 && d>horizon) break; if(r.until && d>r.until) break; if(r.count && existing.length+guard-1>=r.count) break; if(!existing.find(o=>o.date===d)){ const k=PO.uid('occ'); PO.state.ent.occ[k]={id:k, profileId:r.profileId, recId:r.id, date:d, status:'upcoming', created_at:new Date().toISOString()}; existing.push(PO.state.ent.occ[k]); } if(d>horizon && upcoming>=3) break; d=PO.nextDate(r,d); } };
PO.fixOccIds = () => { Object.entries(PO.state.ent.occ).forEach(([k,o])=>{ if(o.id!==k){ o.id=k; } }); };

/* ---------- rules and learning */
PO.matchRule = (r, t) => { const c=r.cond||{}; const p=(PO.payeeName(t)||t.rawText||'').toLowerCase(); if(c.payeeContains && !p.includes(c.payeeContains.toLowerCase())) return false; if(c.payeeRegex){ try{ if(!new RegExp(c.payeeRegex,'i').test(p)) return false; }catch(e){ return false; } } if(c.kind && t.kind!==c.kind) return false; if(c.acct && t.acct!==c.acct) return false; if(c.source && t.source!==c.source) return false; const base=PO.toBase(t.amt||0,t.cur||PO.cur()); if(c.min!=null && base<c.min) return false; if(c.max!=null && base>c.max) return false; return true; };
PO.applyRules = t => { const patch={}; const hits=[]; PO.list('rules').filter(r=>r.enabled!==false).sort((a,b)=>(a.order||0)-(b.order||0)).forEach(r=>{ if(!PO.matchRule(r,t)) return; hits.push(r); Object.assign(patch, r.act||{}); if(!r.cont) return; }); return {patch, hits}; };
PO.learn = (payee, cat) => { if(!payee||!cat) return null; const k=payee.toLowerCase(); const L=PO.state.learn; L[k]=L[k]||{}; L[k][cat]=(L[k][cat]||0)+1; const n=L[k][cat]; const existing=PO.list('rules').find(r=>r.learned&&r.cond?.payeeContains?.toLowerCase()===k); if(n>=2&&!existing){ PO.commit('rules',PO.uid('rule'),{name:`${payee} → ${PO.catName(cat)}`, cond:{payeeContains:payee}, act:{cat}, learned:true, enabled:n>=3, order:PO.list('rules').length},'learn'); return n>=3?'enabled':'draft'; } if(existing&&n>=3&&existing.enabled===false){ PO.commit('rules',existing.id,{enabled:true},'learn'); return 'enabled'; } return null; };
PO.suggestCat = (payee) => { const k=(payee||'').toLowerCase(); const p=PO.list('payees').find(x=>x.name.toLowerCase()===k || (x.aliases||[]).some(a=>a.toLowerCase()===k)); if(p&&p.defaultCat) return p.defaultCat; const L=PO.state.learn[k]; if(L){ return Object.entries(L).sort((a,b)=>b[1]-a[1])[0][0]; } return null; };
PO.findDupes = t => PO.list('tx').filter(x=>x.id!==t.id && x.acct===t.acct && x.amt===t.amt && x.cur===t.cur && Math.abs(PO.daysBetween(x.date,t.date))<=1 && (PO.payeeName(x)||'').toLowerCase().slice(0,5)===(PO.payeeName(t)||'').toLowerCase().slice(0,5));
PO.ensurePayee = name => { if(!name) return null; const k=name.trim().toLowerCase(); let p=PO.list('payees').find(x=>x.name.toLowerCase()===k || (x.aliases||[]).some(a=>a.toLowerCase()===k)); if(!p) p=PO.commit('payees',PO.uid('py'),{name:name.trim(), aliases:[]},'auto'); return p.id; };

/* ---------- alerts */
PO.inQuiet = () => { const q=PO.state.settings.quiet; if(!q.on) return false; const hm=PO.nowHM(); return q.from>q.to ? (hm>=q.from||hm<q.to) : (hm>=q.from&&hm<q.to); };
PO.computeAlerts = () => { const p=PO.curPeriod(); const out=[]; const S=PO.state.settings.alertsGlobal; const pc=PO.pace(p);
  PO.list('envs').filter(e=>!e.archived && e.kind!=='goal').forEach(e=>{ const mode=e.alerts||'all'; if(mode==='off') return; const sp=PO.envSpent(e,p), lim=PO.envLimit(e,p); const lv=PO.levelOf(sp,lim);
    if(lv>=2 && S.over && mode==='all') out.push({id:`over:${e.id}`, kind:'over', level:2, envId:e.id, title:`${e.name} ${PO.t('over_par').toLowerCase()} by ${PO.fmt(sp-lim)}`, body:`${pc.left} ${PO.t('days_left')}.`, action:'cover'});
    else if(lv===1 && S.near) out.push({id:`near:${e.id}`, kind:'near', level:1, envId:e.id, title:`${e.name} ${PO.t('near_par').toLowerCase()}: ${Math.round(sp/lim*100)}%`, body:`${PO.fmt(lim-sp)} ${PO.t('left')} · ${PO.fmt(Math.round((lim-sp)/Math.max(1,pc.left)))} ${PO.t('per_day')}`, action:'env'});
    else if(S.pace && pc.elapsed>3 && lim>0 && sp/lim > pc.share+0.15 && mode==='all'){ const rate=sp/pc.elapsed; const runout=PO.addDays(p.start, Math.floor(lim/Math.max(1,rate))); out.push({id:`pace:${e.id}`, kind:'pace', level:1, envId:e.id, title:`${e.name} runs out around ${PO.fmtDate(runout)}`, body:`Spending ${Math.round((sp/lim-pc.share)*100)} points ahead of the calendar.`, action:'env'}); } });
  const safe=PO.safeToSpend(p); const avg=PO.spentTotal(p)/Math.max(1,pc.elapsed); if(safe < avg*3 && pc.left>3) out.push({id:'safelow', kind:'safe', level:1, title:`${PO.t('safe')} is low: ${PO.fmt(safe)}`, body:`About ${PO.fmt(Math.round(avg))} ${PO.t('per_day')} at the current rate.`, action:'home'});
  if(S.bills) PO.upcomingBills(p).forEach(({o,r})=>{ const d=PO.daysBetween(PO.today(),o.date); if(d<=2 && r.kind!=='income'){ const amt=PO.toBase(o.amt??r.amt,r.cur); out.push({id:`bill:${o.id}`, kind:'bill', level:1, title:`${r.name} due ${d===0?'today':d===1?'tomorrow':'in 2 days'}`, body: safe>=amt?`${PO.fmt(amt)} · covered by safe to spend.`:`${PO.fmt(amt)} · short by ${PO.fmt(amt-safe)}.`, action:'recurring'}); } });
  PO.list('links').forEach(l=>{ const d=PO.daysBetween(PO.today(), l.consentExpiry||'2099-01-01'); if(d<=7) out.push({id:`consent:${l.id}`, kind:'consent', level:1, title:`${l.institution} consent expires in ${d} days`, body:'Re-consent to keep automatic sync.', action:'accounts'}); if(l.status==='error') out.push({id:`sync:${l.id}`, kind:'sync', level:1, title:`${l.institution} sync needs attention`, body:l.error||'Reconnect the bank.', action:'accounts'}); });
  PO.list('watch').forEach(w=>{ const sp=PO.spendRows(p).filter(r=>(w.cat&&PO.catDesc(w.cat).includes(r.cat))||(w.payee&&PO.payeeName(r.tx).toLowerCase().includes(w.payee.toLowerCase()))||(w.tag&&(r.tx.tags||[]).includes(w.tag))).reduce((s,r)=>s+r.amt,0); if(w.limit&&sp>=w.limit*0.8) out.push({id:`watch:${w.id}`, kind:'watch', level:sp>=w.limit?2:1, title:`Watch: ${w.name} at ${Math.round(sp/w.limit*100)}%`, body:`${PO.fmt(sp)} of ${PO.fmt(w.limit)}.`, action:'alerts'}); });
  return out; };
PO.fireAlerts = () => { const today=PO.today(); const fresh=[]; PO.computeAlerts().forEach(a=>{ const logged=PO.list('alertlog').find(l=>l.key===a.id&&l.day===today); if(logged) return; const ignored=PO.list('alertlog').filter(l=>l.key===a.id&&l.ignored).length; const digest = ignored>=2; PO.commit('alertlog',PO.uid('al'),{key:a.id, day:today, kind:a.kind, title:a.title, body:a.body, envId:a.envId, delivered: digest?'digest':(PO.inQuiet()?'queued':'push')},'alert'); if(!digest && !PO.inQuiet()) fresh.push(a); }); return fresh; };

/* ---------- importers */
PO.parseCSV = text => { const rows=[]; let row=[], cell='', q=false; for(let i=0;i<text.length;i++){ const c=text[i]; if(q){ if(c==='"'){ if(text[i+1]==='"'){cell+='"';i++;} else q=false; } else cell+=c; } else if(c==='"') q=true; else if(c===','){ row.push(cell); cell=''; } else if(c==='\n'||c==='\r'){ if(c==='\r'&&text[i+1]==='\n') i++; row.push(cell); rows.push(row); row=[]; cell=''; } else cell+=c; } if(cell||row.length){ row.push(cell); rows.push(row); } return rows.filter(r=>r.some(x=>x.trim())); };
PO.PRESETS = [
  {name:'YNAB', test:h=>h.includes('Outflow')&&h.includes('Inflow'), map:{date:'Date', payee:'Payee', cat:'Category', note:'Memo', out:'Outflow', in:'Inflow', acct:'Account'}},
  {name:'Monarch Money', test:h=>h.includes('Merchant')&&h.includes('Original Statement'), map:{date:'Date', payee:'Merchant', cat:'Category', note:'Notes', amt:'Amount', acct:'Account', tags:'Tags'}},
  {name:'Money Manager', test:h=>h.includes('Income/Expense'), map:{date:'Date', payee:'Note', cat:'Category', note:'Description', amt:'Amount', kind:'Income/Expense', acct:'Account'}},
  {name:'Splitwise', test:h=>h.includes('Cost')&&h.includes('Description'), map:{date:'Date', payee:'Description', cat:'Category', out:'Cost', cur:'Currency'}},
  {name:'Mint archive', test:h=>h.includes('Transaction Type')&&h.includes('Original Description'), map:{date:'Date', payee:'Description', cat:'Category', amt:'Amount', kind:'Transaction Type', acct:'Account Name', note:'Notes'}},
  {name:'Bluecoins', test:h=>h.includes('Item or Payee'), map:{date:'Date', payee:'Item or Payee', cat:'Child Category', amt:'Amount', acct:'Account', note:'Notes'}},
  {name:'Generic', test:()=>true, map:{}}
];
PO.detectPreset = headers => PO.PRESETS.find(p=>p.test(headers));
PO.guessMap = headers => { const m={}; const find=re=>headers.find(h=>re.test(h)); m.date=find(/date|fecha|tarikh/i)||''; m.payee=find(/payee|merchant|description|desc|narration|particular|note|memo/i)||''; m.cat=find(/categ/i)||''; m.amt=find(/^amount$|amt|value|total|cost/i)||''; m.out=find(/debit|outflow|withdraw|expense/i)||''; m.in=find(/credit|inflow|deposit|income/i)||''; m.acct=find(/account/i)||''; m.note=find(/^notes?$|memo|remark/i)||''; m.cur=find(/currency/i)||''; m.kind=find(/type|income\/expense/i)||''; return m; };
PO.parseDateLoose = s => { if(!s) return null; s=String(s).trim(); let m; if((m=s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/))) return PO.mk(+m[1],+m[2],+m[3]); if((m=s.match(/^(\d{1,2})[\/.-](\d{1,2})[\/.-](\d{2,4})/))){ let a=+m[1], b=+m[2], y=+m[3]; if(y<100) y+=2000; const dmy = PO.state?.settings?.dateOrder==='mdy' ? false : true; const [d,mo]= dmy ? [a,b] : [b,a]; if(mo>12) return PO.mk(y,d,mo); return PO.mk(y,mo,d); } const d=new Date(s); return isNaN(d)?null:PO.ymd(d); };
PO.mapRows = (rows, map, headers, acctId) => { const idx=k=>map[k]?headers.indexOf(map[k]):-1; const cur=PO.cur(); const out=[]; rows.forEach((r,i)=>{ const g=k=>{ const j=idx(k); return j>=0?(r[j]||'').trim():''; }; const date=PO.parseDateLoose(g('date')); let amt=null, kind='expense'; const num=s=>{ const v=parseFloat(String(s).replace(/[^0-9.\-]/g,'')); return isNaN(v)?null:v; };
    if(map.out||map.in){ const o=num(g('out')), inn=num(g('in')); if(inn){ amt=inn; kind='income'; } else { amt=o; } } else { const v=num(g('amt')); if(v!=null){ amt=Math.abs(v); const k=g('kind').toLowerCase(); kind = v>0 && !/exp|debit/.test(k) && (/inc|credit/.test(k) || map.kind==='') ? (v>0&&(/inc|credit/.test(k)||!k)?'income':'expense') : 'expense'; if(k.startsWith('exp')||k==='debit') kind='expense'; if(!k && v<0) kind='expense'; if(!k && v>0 && map.kind) kind='income'; } }
    const rowCur = g('cur')||cur; const errors=[]; if(!date) errors.push('bad date'); if(amt==null||amt<=0) errors.push('bad amount'); const t={ kind, date:date||PO.today(), amt:amt!=null?PO.toMinor(amt,rowCur):0, cur:rowCur, acct:acctId, payee:g('payee'), note:g('note'), catName:g('cat'), source:'import', status:'cleared', errors, line:i+2 };
    const cat=PO.list('cats').find(c=>c.name.toLowerCase()===t.catName.toLowerCase()); t.cat=cat?cat.id:(PO.suggestCat(t.payee)||'uncat'); t.dupe = PO.findDupes({...t, id:'new'}).length>0; out.push(t); }); return out; };
PO.parseShareText = text => { const amtM=text.replace(/,/g,'').match(/(?:₹|Rs\.?|INR|₱|PHP|\$|USD|€|£|¥|AED|R\$)\s?(\d+(?:\.\d{1,3})?)|(\d+(?:\.\d{1,3})?)\s?(?:₹|rupees|pesos|dollars|USD|INR|PHP)/i); const amt=amtM?parseFloat(amtM[1]||amtM[2]):null; const toM=text.match(/(?:to|at|@|paid to|sent to|for)\s+([A-Za-z0-9&'. @-]{2,40})/i); const kind=/received|credited|refund/i.test(text)?'income':'expense'; return {amt, payee:toM?toM[1].trim().replace(/\s+(on|via|using|from).*$/i,''):'', kind, raw:text}; };

/* ---------- seed data */
PO.decoyState = () => { const s=PO.newState(); s.settings.onboarded=true; const pid=PO.uid('p'); s.currentProfile=pid; s.profiles[pid]={id:pid,name:'Personal',kind:'personal',region:'XX',cur:'USD',cycle:{type:'monthly',day:1},tz:'UTC',members:[]}; const prev=PO.state; PO.state=s; PO.seedCats(pid); const a=PO.commit('accts',PO.uid('a'),{name:'Cash',type:'cash',cur:'USD',opening:4200}); PO.commit('tx',PO.uid('t'),{kind:'expense',amt:650,cur:'USD',acct:a.id,cat:'eat.cafe',payee:'Coffee',date:PO.today(),status:'cleared'}); PO.commit('envs',PO.uid('e'),{name:'Food',cat:'food',limit:30000,rollover:'none'}); const out=PO.state; PO.state=prev; return out; };
PO.seedCats = pid => { PO.DEFAULT_CATS.forEach(([id,kind,name,parent,flag])=>{ PO.state.ent.cats[`${pid}:${id}`]={id:`${pid}:${id}`, profileId:pid, key:id, kind, name, parent:parent?`${pid}:${parent}`:null, excluded:flag==='x', created_at:new Date().toISOString()}; }); };
PO.cid = (pid,key) => `${pid}:${key}`;
PO.createProfile = (o) => { const id=PO.uid('p'); const pk=PO.PACKS[o.region]||PO.PACKS.XX; PO.state.profiles[id]={id, name:o.name, kind:o.kind||'personal', region:o.region||'XX', cur:o.cur||pk.cur, cycle:o.cycle||pk.cycle, tz:o.tz||pk.tz, weekStart:pk.weekStart, fiscal:pk.fiscal, members:o.members||[], audit:[], defaultAcct:null, supervisorId:o.supervisorId||null, business:o.kind==='business', created_at:new Date().toISOString()}; const prev=PO.state.currentProfile; PO.state.currentProfile=id; PO.seedCats(id); PO.state.currentProfile=prev||id; return PO.state.profiles[id]; };

PO.seed = (region='IN') => {
  const s=PO.newState(); PO.state=s; const pk=PO.PACKS[region]||PO.PACKS.XX; const C=pk.cur; const M=(x)=>PO.toMinor(x,C); const T=PO.today();
  const me=s.login.id; const p=PO.createProfile({name:'Personal', region, kind:'personal'}); s.currentProfile=p.id; const cid=k=>PO.cid(p.id,k);
  const mk=(ent,o)=>PO.commit(ent,PO.uid(ent.slice(0,2)),o,'seed');
  const scale = region==='IN'?1:region==='PH'?0.7:region==='JP'?1.5:region==='ID'?180:region==='NG'?20:region==='US'||region==='GB'||region==='DE'||region==='AU'||region==='SG'?0.05:region==='BR'?0.25:region==='MX'?0.9:region==='AE'?0.18:1;
  const A=x=>M(Math.round(x*scale));
  const cash=mk('accts',{name:'Cash',type:'cash',cur:C,opening:A(3450),includeNW:true});
  const bank=mk('accts',{name:region==='PH'?'BPI Savings':region==='IN'?'HDFC Savings':region==='US'?'Chase Checking':region==='GB'?'Monzo':region==='BR'?'Nubank':'Main bank',type:'bank',cur:C,opening:A(48200)});
  const wallet=mk('accts',{name:pk.apps[0],type:'wallet',cur:C,opening:region==='PH'?A(3200):0, passThrough: region==='IN'?bank.id:null});
  const card=mk('accts',{name:region==='IN'?'ICICI Credit Card':'Credit card',type:'card',cur:C,opening:-A(12400),limit:A(100000),stmtDay:18,dueDay:6,minPct:5,apr:36});
  const loan=mk('accts',{name:'Phone EMI',type:'loan',cur:C,opening:-A(17493),principal:A(29988),apr:14,tenure:12,start:PO.addMonths(T,-5)});
  mk('accts',{name:'Gold (grams)',type:'asset',cur:C,opening:A(185000),unit:'24 g'});
  p.defaultAcct=wallet.id;
  const env=(name,cat,limit,roll,alerts)=>mk('envs',{name,cat:cid(cat),limit:A(limit),rollover:roll||'none',alerts:alerts||'all'});
  env('Food and groceries','food',9000); env('Transport','trans',2500,'positive'); env('Bills and utilities','util',4500); env('Eating out','eat',3000); env('Household','home.maint',5000,'positive'); env('Fun','fun',2000); env('Family','fam',4000); env('Health','health',1500,'positive');
  mk('envs',{name:'Yearly insurance',cat:cid('health.ins'),limit:A(12000),rollover:'positive',periodRule:{type:'yearly',fiscal:pk.fiscal},alerts:'near'});
  const pyIds={}; const py=n=>{ pyIds[n]=pyIds[n]||PO.ensurePayee(n); return pyIds[n]; };
  const merchants = region==='PH'?['Jollibee','SM Supermarket','Aling Nena store','Grab','Globe','Meralco','Lazada','Puregold','Mercury Drug','7-Eleven']:region==='IN'?['Zomato','Big Bazaar','Mahadev store','Uber','Jio','BESCOM','Amazon','DMart','Apollo Pharmacy','Chai point']:['Starbucks','Whole Foods','Corner store','Uber','Verizon','Power co','Amazon','Target','CVS','Local cafe'];
  const catFor=['eat.rest','food.groc','food.market','trans.ride','util.mobile','util.elec','shop.homeg','food.groc','health.pharm','eat.cafe'];
  const amts=[340,1460,180,220,299,1850,1299,980,420,40];
  let seedTs=0; const tx=(o)=>{ const t=mk('tx',{status:'cleared',cur:C,...o}); return t; };
  for(let k=0;k<70;k++){ const i=k%10; const back = Math.floor(k*1.3); const date=PO.addDays(T,-back); const acct = i===2||i===9?cash.id : i===1||i===6?card.id : wallet.id; tx({kind:'expense',amt:A(amts[i]*(0.8+((k*7)%5)/10)),acct,cat:cid(catFor[i]),payeeId:py(merchants[i]),date,source:i===2||i===9?'manual':pk.apps[0], time:`${pad(8+(k%12))}:${pad((k*17)%60)}`}); }
  tx({kind:'income',amt:A(42000),acct:bank.id,cat:cid('inc.salary'),payeeId:py('Employer'),date:PO.periodOf(T).start,note:'Salary'});
  tx({kind:'income',amt:A(42000),acct:bank.id,cat:cid('inc.salary'),payeeId:py('Employer'),date:PO.shiftPeriod(PO.periodOf(T),-1,p.cycle).start});
  tx({kind:'transfer',amt:A(5000),acct:bank.id,toAcct:cash.id,date:PO.addDays(T,-3),note:'ATM'});
  tx({kind:'transfer',amt:A(12400),acct:bank.id,toAcct:card.id,date:PO.addDays(T,-9),note:'Card bill'});
  tx({kind:'refund',amt:A(1299),acct:card.id,cat:cid('shop.homeg'),payeeId:py(merchants[6]),date:PO.addDays(T,-2),note:'Returned kettle'});
  tx({kind:'expense',amt:A(12000),acct:bank.id,cat:cid('home.rent'),payeeId:py('Landlord'),date:PO.periodOf(T).start,status:'reconciled'});
  tx({kind:'expense',amt:A(2499),acct:bank.id,cat:cid('loan.emi'),payeeId:py('Phone EMI'),date:PO.addDays(PO.periodOf(T).start,4)});
  const rc=(o)=>mk('recur',{cur:C,kind:'expense',freq:'monthly',interval:1,autoPost:false,leadDays:2,...o});
  rc({name:'Rent',amt:A(12000),acct:bank.id,cat:cid('home.rent'),start:PO.addDays(PO.periodOf(T).end,1),byDay:1});
  rc({name:'Phone EMI',amt:A(2499),acct:bank.id,cat:cid('loan.emi'),start:PO.addDays(T,3),byDay:5,note:'7 of 12 left'});
  rc({name:region==='PH'?'Meralco':'Electricity',amt:A(1850),acct:wallet.id,cat:cid('util.elec'),start:PO.addDays(T,1),variable:true});
  rc({name:'Streaming',amt:A(199),acct:card.id,cat:cid('fun.stream'),start:PO.addDays(T,2),subscription:true,detected:true});
  rc({name:region==='PH'?'Globe plan':'Mobile plan',amt:A(299),acct:wallet.id,cat:cid('util.mobile'),start:PO.addDays(T,6)});
  rc({name:'Salary',kind:'income',amt:A(42000),acct:bank.id,cat:cid('inc.salary'),start:PO.addDays(PO.periodOf(T).end,1)});
  PO.list('recur').forEach(PO.ensureOcc);
  mk('goals',{name:pk.festivals[0].n+' pot',target:A(15000),saved:A(1800),perPeriod:A(1200),due:`${T.slice(0,4)}-${pad(pk.festivals[0].m)}-01`,status:'active',acct:bank.id});
  mk('goals',{name:'Emergency fund',target:A(100000),saved:A(36000),perPeriod:A(3000),status:'active',acct:bank.id});
  const ramesh=mk('people',{name:region==='PH'?'Jonas':'Ramesh',contact:'+00 000'}); const priya=mk('people',{name:region==='PH'?'Tita Rosa':'Priya (sister)'});
  mk('lend',{personId:ramesh.id,dir:'receive',amt:A(2000),date:PO.addDays(T,-13),due:PO.addDays(T,5),note:'Lunch money',settled:A(500),entries:[{date:PO.addDays(T,-13),amt:A(2000),type:'gave'},{date:PO.addDays(T,-4),amt:A(500),type:'got'}]});
  mk('lend',{personId:priya.id,dir:'give',amt:A(1500),date:PO.addDays(T,-18),note:'Borrowed for groceries',settled:0,entries:[{date:PO.addDays(T,-18),amt:A(1500),type:'got'}]});
  const grp=mk('groups',{name:region==='PH'?'Baguio trip':'Goa trip',cur:C,members:[{id:me,name:'You'},{id:'g2',name:'Meera',external:true},{id:'g3',name:'Ravi',external:true}]});
  mk('gexp',{groupId:grp.id,desc:'Hotel',amt:A(9000),payer:me,shares:[{id:me,amt:A(3000)},{id:'g2',amt:A(3000)},{id:'g3',amt:A(3000)}],date:PO.addDays(T,-20)});
  mk('gexp',{groupId:grp.id,desc:'Dinner',amt:A(2400),payer:'g2',shares:[{id:me,amt:A(800)},{id:'g2',amt:A(800)},{id:'g3',amt:A(800)}],date:PO.addDays(T,-19)});
  mk('remit',{from:region==='PH'?'Marco (husband)':'Arun (brother)',fromPlace:region==='PH'?'Riyadh':'Dubai',sentAmt:region==='PH'?200000:100000,sentCur:region==='PH'?'SAR':'AED',fee:region==='PH'?2000:1500,rate:region==='PH'?15.6:22.65,gotAmt:A(31200),gotCur:C,purpose:'Household',date:PO.addDays(T,-6),status:'confirmed'});
  const circ=mk('circles',{name:pk.circles[0],members:10,contrib:A(2000),every:'monthly',turn:4,myTurn:7,start:PO.addMonths(T,-3),order:['Maria','Ben','Lito','You','Ana','Paulo','Sam','Nina','Jo','Kim']});
  for(let i=0;i<3;i++) mk('contrib',{circleId:circ.id,date:PO.addMonths(T,-i),amt:A(2000),round:3-i});
  mk('review',{rawText:`${pk.apps[1]} · paid ${PO.fmt(A(120),C,{force:true})} to q9871@ybl`,amt:A(120),cur:C,kind:'expense',acct:wallet.id,cat:cid('trans.ride'),payee:'q9871@ybl',source:pk.apps[1],date:T,time:'07:48',confidence:0.72});
  mk('review',{rawText:`${pk.apps[0]} · ${PO.fmt(A(612),C,{force:true})} at Instamart`,amt:A(612),cur:C,kind:'expense',acct:wallet.id,cat:cid('food.deliv'),payee:'Instamart',source:pk.apps[0],date:PO.addDays(T,-1),time:'19:12',confidence:0.91});
  mk('review',{rawText:`${bank.name} · credited ${PO.fmt(A(1500),C,{force:true})} from ${region==='PH'?'JONAS':'RAMESH'}`,amt:A(1500),cur:C,kind:'income',acct:bank.id,cat:cid('loan.repay'),payee:region==='PH'?'Jonas':'Ramesh',source:bank.name,date:T,time:'10:02',confidence:0.6});
  mk('links',{institution:bank.name,provider:region==='IN'?'Account Aggregator':region==='PH'?'BSP Open Finance':region==='US'?'Plaid':region==='GB'||region==='DE'?'TrueLayer':region==='BR'?'Pluggy':region==='AU'?'Basiq':'Aggregator',accts:[bank.id],consentExpiry:PO.addDays(T,5),lastFetch:PO.addDays(T,-1),status:'ok'});
  mk('watch',{name:'Coffee',payee:'cafe',limit:A(600)});
  mk('saved',{name:'Reimbursable this period',q:'',tags:['reimbursable']});
  mk('rules',{name:merchants[0]+' → Eating out',cond:{payeeContains:merchants[0]},act:{cat:cid('eat.rest')},enabled:true,order:0});
  mk('tags',{name:'reimbursable'}); mk('tags',{name:'trip'}); mk('tags',{name:'tax'});
  const sp=PO.createProfile({name:'Our home',kind:'space',region,members:[{id:me,name:'You',email:s.login.email,role:'owner'},{id:'m2',name:region==='PH'?'Marco':'Arun',email:'earner@example.com',role:'editor',overrides:{}},{id:'m3',name:region==='PH'?'Nanay':'Amma',email:'home@example.com',role:'contributor',overrides:{}},{id:'m4',name:'Aarav (14)',email:'',role:'viewer',overrides:{}}]});
  const prevP=s.currentProfile; s.currentProfile=sp.id; const scid=k=>PO.cid(sp.id,k);
  const joint=mk('accts',{name:'Joint account',type:'bank',cur:C,opening:A(60000)}); const scash=mk('accts',{name:'Kitchen cash',type:'cash',cur:C,opening:A(2200)}); sp.defaultAcct=joint.id;
  const e1=mk('envs',{name:'Groceries (shared)',cat:scid('food'),limit:A(14000)}); mk('envs',{name:'Rent and bills',cat:scid('home'),limit:A(20000)}); mk('envs',{name:'Gifts',cat:scid('gift'),limit:A(3000),alerts:'near'});
  sp.members[3].overrides[e1.id]='contributor'; sp.members[2].overrides[e1.id]='editor';
  mk('tx',{kind:'income',amt:A(31200),cur:C,acct:joint.id,cat:scid('inc.remit'),payee:'Remittance',date:PO.addDays(T,-6),status:'cleared',actor:'m2'});
  mk('tx',{kind:'expense',amt:A(3200),cur:C,acct:joint.id,cat:scid('food.groc'),payee:merchants[1],date:PO.addDays(T,-2),status:'cleared',actor:'m3'});
  mk('tx',{kind:'expense',amt:A(900),cur:C,acct:scash.id,cat:scid('gift.gifts'),payee:'Gift for Amma',date:PO.addDays(T,-1),status:'cleared',private:true,actor:me});
  mk('invites',{email:'cousin@example.com',role:'contributor',token:PO.uid('inv'),expires:PO.addDays(T,7),status:'pending'});
  sp.audit=[{ts:new Date(Date.now()-864e5).toISOString(),actor:'You',text:'Created the space and invited 3 members'},{ts:new Date(Date.now()-3600e3).toISOString(),actor:sp.members[2].name,text:`Added ${PO.fmt(A(3200),C,{force:true})} at ${merchants[1]} to Groceries (shared)`}];
  s.currentProfile=prevP;
  PO.createProfile({name:'Side business',kind:'business',region});
  const kid=PO.createProfile({name:'Aarav (allowance)',kind:'supervised',region,supervisorId:me}); s.currentProfile=kid.id; mk('accts',{name:'Allowance jar',type:'cash',cur:C,opening:A(800)}); mk('envs',{name:'Snacks',cat:PO.cid(kid.id,'eat'),limit:A(500)}); s.currentProfile=prevP;
  s.settings.onboarded=false; s.settings.lang=pk.lang; PO.fixOccIds(); return s;
};
})(window.PO);
