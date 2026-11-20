// Masjid Finder server: masjids (from OpenStreetMap + community), jamat times kept by volunteers.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';

const __dir = path.dirname(fileURLToPath(import.meta.url));
const PORT = +process.env.PORT || 3000;
const DATA = process.env.DATA_DIR || path.join(__dir, 'data');
fs.mkdirSync(DATA, { recursive: true });
const OVERPASS_URLS = (process.env.OVERPASS_URL || 'https://overpass-api.de/api/interpreter,https://overpass.kumi.systems/api/interpreter').split(',').map(x => x.trim()).filter(Boolean);
const NOMINATIM_URL = process.env.NOMINATIM_URL || 'https://nominatim.openstreetmap.org/search';
const COUNTRY = (process.env.COUNTRY_CODES || '').trim();
// Map provider is swappable: today 'osm'. A 'google' provider can be added later (see README).
const MAP = {
  provider: (process.env.MAP_PROVIDER || 'osm').trim(),
  tiles: process.env.TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
  attribution: process.env.TILE_ATTRIBUTION || '© OpenStreetMap contributors',
  googleKey: (process.env.GOOGLE_MAPS_KEY || '').trim() ? true : false,
};
const PRAYERS = ['fajr', 'dhuhr', 'asr', 'maghrib', 'isha', 'jumuah'];

const db = new DatabaseSync(path.join(DATA, 'masjid.db'));
db.exec(`
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, name TEXT, phone TEXT UNIQUE, salt TEXT, hash TEXT, role TEXT, created INTEGER);
CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, user INTEGER, created INTEGER);
CREATE TABLE IF NOT EXISTS masjids(id INTEGER PRIMARY KEY, name TEXT, name_en TEXT, name_bn TEXT, lat REAL, lng REAL, address TEXT,
  source TEXT, ext_id TEXT, status TEXT, added_by INTEGER, created INTEGER, t_updated INTEGER, t_by INTEGER, UNIQUE(source, ext_id));
CREATE INDEX IF NOT EXISTS masjids_geo ON masjids(lat, lng);
CREATE TABLE IF NOT EXISTS timings(masjid INTEGER, prayer TEXT, t TEXT, PRIMARY KEY(masjid, prayer));
CREATE TABLE IF NOT EXISTS tlog(id INTEGER PRIMARY KEY, masjid INTEGER, user INTEGER, data TEXT, created INTEGER);
CREATE TABLE IF NOT EXISTS volunteers(id INTEGER PRIMARY KEY, masjid INTEGER, user INTEGER, status TEXT, note TEXT, created INTEGER, decided INTEGER, UNIQUE(masjid, user));
CREATE TABLE IF NOT EXISTS reports(id INTEGER PRIMARY KEY, masjid INTEGER, text TEXT, created INTEGER, open INTEGER DEFAULT 1);
CREATE TABLE IF NOT EXISTS synced(cell TEXT PRIMARY KEY, at INTEGER);
`);
const q = (s, ...a) => db.prepare(s).all(...a);
const q1 = (s, ...a) => db.prepare(s).get(...a);
const run = (s, ...a) => db.prepare(s).run(...a);

const now = () => Date.now();
const DAY = 864e5;
const clean = (s, n) => String(s ?? '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').trim().slice(0, n);
const hashPin = (pin, salt) => crypto.scryptSync(String(pin), salt, 32).toString('hex');
const normPhone = p => String(p ?? '').replace(/[^\d]/g, '');
const okPhone = p => p.length >= 7 && p.length <= 15;
const okPin = p => /^\d{4,6}$/.test(String(p ?? ''));
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

function addUser(name, phone, pin, role) {
  const salt = crypto.randomBytes(8).toString('hex');
  const r = run('INSERT INTO users(name,phone,salt,hash,role,created) VALUES(?,?,?,?,?,?)', name, phone, salt, hashPin(pin, salt), role, now());
  return Number(r.lastInsertRowid);
}
if (process.env.ADMIN_PHONE && process.env.ADMIN_PIN) {
  const ph = normPhone(process.env.ADMIN_PHONE);
  if (!q1('SELECT id FROM users WHERE phone=?', ph)) addUser(clean(process.env.ADMIN_NAME || 'Admin', 40), ph, process.env.ADMIN_PIN, 'admin');
}

// ---------- geo ----------
const rad = d => d * Math.PI / 180;
function dist(aLat, aLng, bLat, bLng) {
  const dLat = rad(bLat - aLat), dLng = rad(bLng - aLng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLng / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.sqrt(h));
}
function nearRows(lat, lng, km, where = '', ...args) {
  const dLat = km / 111, dLng = km / (111 * Math.max(0.2, Math.cos(rad(lat))));
  const rows = q(`SELECT * FROM masjids WHERE lat BETWEEN ? AND ? AND lng BETWEEN ? AND ? ${where}`, lat - dLat, lat + dLat, lng - dLng, lng + dLng, ...args);
  return rows.map(r => ({ ...r, dist: dist(lat, lng, r.lat, r.lng) })).filter(r => r.dist <= km).sort((a, b) => a.dist - b.dist);
}

// ---------- OpenStreetMap import (cached in our own database) ----------
const CELL = 0.05, inflight = new Map(), failedAt = new Map();
const cellKey = (lat, lng) => Math.floor(lat / CELL) + ':' + Math.floor(lng / CELL);
async function syncAround(lat, lng) {
  const ci = Math.floor(lat / CELL), cj = Math.floor(lng / CELL), cells = [];
  for (let i = -1; i <= 1; i++) for (let j = -1; j <= 1; j++) cells.push((ci + i) + ':' + (cj + j));
  const stale = cells.filter(c => { const r = q1('SELECT at FROM synced WHERE cell=?', c); return !r || now() - r.at > 30 * DAY; });
  if (!stale.length) return;
  const key = ci + ':' + cj;
  if (inflight.has(key)) return inflight.get(key);
  if (failedAt.get(key) && now() - failedAt.get(key) < 10 * 60e3) return;
  const p = (async () => {
    const s = (ci - 1) * CELL, w = (cj - 1) * CELL, n = (ci + 2) * CELL, e = (cj + 2) * CELL;
    const bb = `(${s},${w},${n},${e})`;
    const ql = `[out:json][timeout:40];(node["amenity"="place_of_worship"]["religion"="muslim"]${bb};way["amenity"="place_of_worship"]["religion"="muslim"]${bb};relation["amenity"="place_of_worship"]["religion"="muslim"]${bb};);out center tags;`;
    try {
      let j = null, lastErr = 'no server';
      for (const u of OVERPASS_URLS) {
        try {
          const r = await fetch(u, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', 'user-agent': 'MasjidFinder/1.0' }, body: 'data=' + encodeURIComponent(ql), signal: AbortSignal.timeout(60000) });
          if (!r.ok) throw new Error('overpass ' + r.status);
          j = await r.json();
          break;
        } catch (e3) { lastErr = u + ': ' + e3.message; console.log('Overpass try failed', lastErr); }
      }
      if (!j) throw new Error(lastErr);
      db.exec('BEGIN');
      for (const el of j.elements || []) {
        const la = el.lat ?? el.center?.lat, lo = el.lon ?? el.center?.lon;
        if (la == null || lo == null) continue;
        const t = el.tags || {};
        const addr = [t['addr:housenumber'], t['addr:street'], t['addr:suburb'] || t['addr:city']].filter(Boolean).join(', ');
        run(`INSERT INTO masjids(name,name_en,name_bn,lat,lng,address,source,ext_id,status,created) VALUES(?,?,?,?,?,?,?,?,?,?)
             ON CONFLICT(source,ext_id) DO UPDATE SET name=excluded.name,name_en=excluded.name_en,name_bn=excluded.name_bn,lat=excluded.lat,lng=excluded.lng,address=COALESCE(NULLIF(excluded.address,''),address)
             WHERE masjids.added_by IS NULL`,
          clean(t.name || '', 120), clean(t['name:en'] || '', 120), clean(t['name:bn'] || '', 120), la, lo, clean(addr, 160), 'osm', el.type + '/' + el.id, 'active', now());
      }
      for (const c of cells) run('INSERT INTO synced(cell,at) VALUES(?,?) ON CONFLICT(cell) DO UPDATE SET at=excluded.at', c, now());
      db.exec('COMMIT');
    } catch (err) {
      try { db.exec('ROLLBACK'); } catch (e2) { }
      failedAt.set(key, now());
      console.log('OSM import failed:', err.message);
    } finally { inflight.delete(key); }
  })();
  inflight.set(key, p);
  return p;
}

// ---------- shaping ----------
function fresh(ts) {
  if (!ts) return 'none';
  const d = (now() - ts) / DAY;
  return d <= 7 ? 'fresh' : d <= 14 ? 'aging' : 'stale';
}
function shape(r, withTimes = true) {
  const o = {
    id: r.id, name: r.name, name_en: r.name_en, name_bn: r.name_bn, lat: r.lat, lng: r.lng, address: r.address,
    source: r.source, status: r.status, dist: r.dist == null ? undefined : Math.round(r.dist * 1000) / 1000,
    updated: r.t_updated || null, fresh: fresh(r.t_updated), times: null,
  };
  if (r.t_by) o.by = (q1('SELECT name FROM users WHERE id=?', r.t_by) || {}).name || null;
  if (withTimes) {
    const t = q('SELECT prayer,t FROM timings WHERE masjid=?', r.id);
    if (t.length) { o.times = {}; t.forEach(x => o.times[x.prayer] = x.t); }
  }
  o.volunteers = q1("SELECT COUNT(*) c FROM volunteers WHERE masjid=? AND status='approved'", r.id).c;
  return o;
}

// ---------- http helpers ----------
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json', '.ico': 'image/x-icon' };
const send = (res, code, obj) => { const b = JSON.stringify(obj); res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }); res.end(b); };
const readBody = req => new Promise((ok, no) => {
  let n = 0; const ch = [];
  req.on('data', c => { n += c.length; if (n > 40000) { no(new Error('big')); req.destroy(); } else ch.push(c); });
  req.on('end', () => { try { ok(ch.length ? JSON.parse(Buffer.concat(ch).toString()) : {}); } catch (e) { no(e); } });
  req.on('error', no);
});
const ipOf = req => String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
const hits = new Map();
function limited(key, max, ms) {
  const t = now(), a = (hits.get(key) || []).filter(x => t - x < ms);
  if (a.length >= max) { hits.set(key, a); return true; }
  a.push(t); hits.set(key, a); return false;
}
setInterval(() => { const t = now(); for (const [k, a] of hits) if (!a.some(x => t - x < 864e5)) hits.delete(k); }, 36e5).unref();
const userOf = req => {
  const t = req.headers['x-token'];
  if (!t) return null;
  return q1('SELECT u.id,u.name,u.phone,u.role FROM sessions s JOIN users u ON u.id=s.user WHERE s.token=?', t) || null;
};
const newSession = uid => { const t = crypto.randomBytes(24).toString('hex'); run('INSERT INTO sessions(token,user,created) VALUES(?,?,?)', t, uid, now()); return t; };
const canEdit = (u, mid) => u && (u.role === 'admin' || q1("SELECT id FROM volunteers WHERE masjid=? AND user=? AND status='approved'", mid, u.id));
const myRows = u => q(`SELECT v.id,v.masjid,v.status,v.note,m.name,m.name_en,m.name_bn FROM volunteers v JOIN masjids m ON m.id=v.masjid WHERE v.user=? ORDER BY v.id DESC`, u.id);

let lastGeo = 0;
const geoCache = new Map();
async function geocode(qs, lang) {
  const key = lang + '|' + qs.toLowerCase();
  if (geoCache.has(key)) return geoCache.get(key);
  const wait = Math.max(0, lastGeo + 1100 - now());
  lastGeo = now() + wait;
  if (wait) await new Promise(r => setTimeout(r, wait));
  const url = NOMINATIM_URL + '?format=json&limit=6&accept-language=' + encodeURIComponent(lang) + (COUNTRY ? '&countrycodes=' + encodeURIComponent(COUNTRY) : '') + '&q=' + encodeURIComponent(qs);
  const r = await fetch(url, { headers: { 'user-agent': 'MasjidFinder/1.0' }, signal: AbortSignal.timeout(10000) });
  if (!r.ok) throw new Error('geocode ' + r.status);
  const out = (await r.json()).map(x => ({ name: x.display_name, lat: +x.lat, lng: +x.lon }));
  geoCache.set(key, out);
  if (geoCache.size > 500) geoCache.delete(geoCache.keys().next().value);
  return out;
}

// ---------- API ----------
async function api(req, res, url) {
  const p = url.pathname, m = req.method, S = url.searchParams, ip = ipOf(req);
  const me = userOf(req);
  const body = m === 'POST' ? await readBody(req) : {};
  let g;

  if (p === '/api/config') return send(res, 200, { map: MAP, countryCodes: COUNTRY });

  if (p === '/api/masjids' && m === 'GET') {
    const lat = +S.get('lat'), lng = +S.get('lng'), km = Math.min(25, Math.max(0.5, +S.get('r') || 4));
    if (!isFinite(lat) || !isFinite(lng)) return send(res, 400, { error: 'lat/lng' });
    const sp = syncAround(lat, lng);
    if (sp) await Promise.race([sp, new Promise(r => setTimeout(r, 6000))]);
    const key = cellKey(lat, lng), syncing = inflight.has(key), syncFailed = !syncing && !!failedAt.get(key) && now() - failedAt.get(key) < 10 * 60e3;
    let rows = nearRows(lat, lng, km, "AND (status='active' OR (status='pending' AND added_by=?))", me ? me.id : -1).slice(0, 80);
    return send(res, 200, { masjids: rows.map(r => shape(r)), syncing, syncFailed });
  }
  if ((g = p.match(/^\/api\/masjid\/(\d+)$/)) && m === 'GET') {
    const r = q1('SELECT * FROM masjids WHERE id=?', +g[1]);
    if (!r || (r.status !== 'active' && !(me && (me.role === 'admin' || r.added_by === me.id)))) return send(res, 404, { error: 'not found' });
    const o = shape(r);
    o.log = q('SELECT l.created,u.name FROM tlog l LEFT JOIN users u ON u.id=l.user WHERE l.masjid=? ORDER BY l.id DESC LIMIT 5', r.id);
    o.canEdit = !!canEdit(me, r.id);
    o.myStatus = me ? (q1('SELECT status FROM volunteers WHERE masjid=? AND user=?', r.id, me.id) || {}).status || null : null;
    return send(res, 200, o);
  }
  if (p === '/api/geocode' && m === 'GET') {
    const s = clean(S.get('q'), 100);
    if (s.length < 2) return send(res, 200, { results: [] });
    if (limited('geo:' + ip, 30, 60e3)) return send(res, 429, { error: 'slow down' });
    try { return send(res, 200, { results: await geocode(s, S.get('lang') === 'bn' ? 'bn' : 'en') }); }
    catch (e) { return send(res, 502, { error: 'search unavailable' }); }
  }

  // add a missing masjid (anyone); admin approves
  if (p === '/api/masjids' && m === 'POST') {
    const lat = +body.lat, lng = +body.lng, name = clean(body.name, 120);
    if (!isFinite(lat) || !isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return send(res, 400, { error: 'Pick a location on the map.' });
    if (name.length < 2) return send(res, 400, { error: 'Enter the masjid name.' });
    if (limited('add:' + ip, 6, 864e5)) return send(res, 429, { error: 'Too many additions today.' });
    if (!body.force) {
      const d = nearRows(lat, lng, 0.1, "AND status='active'").slice(0, 5);
      if (d.length) return send(res, 409, { dupes: d.map(r => shape(r, false)) });
    }
    const r = run('INSERT INTO masjids(name,lat,lng,address,source,ext_id,status,added_by,created) VALUES(?,?,?,?,?,?,?,?,?)',
      name, lat, lng, clean(body.address, 160), 'community', crypto.randomBytes(6).toString('hex'), 'pending', me ? me.id : null, now());
    const id = Number(r.lastInsertRowid);
    if (me && body.volunteer) run("INSERT OR IGNORE INTO volunteers(masjid,user,status,note,created) VALUES(?,?,?,?,?)", id, me.id, 'pending', clean(body.note, 200), now());
    return send(res, 200, { id });
  }

  // accounts
  if (p === '/api/register' && m === 'POST') {
    const name = clean(body.name, 40), phone = normPhone(body.phone);
    if (name.length < 2) return send(res, 400, { error: 'Enter your name.' });
    if (!okPhone(phone)) return send(res, 400, { error: 'Enter a valid phone number.' });
    if (!okPin(body.pin)) return send(res, 400, { error: 'PIN must be 4 to 6 digits.' });
    if (limited('reg:' + ip, 8, 36e5)) return send(res, 429, { error: 'Too many attempts.' });
    if (q1('SELECT id FROM users WHERE phone=?', phone)) return send(res, 409, { error: 'This phone number already has an account. Sign in.' });
    const id = addUser(name, phone, body.pin, 'user');
    return send(res, 200, { token: newSession(id), user: { id, name, role: 'user' } });
  }
  if (p === '/api/login' && m === 'POST') {
    const phone = normPhone(body.phone);
    if (limited('login:' + ip + phone, 10, 600e3)) return send(res, 429, { error: 'Too many attempts. Wait a few minutes.' });
    const u = q1('SELECT * FROM users WHERE phone=?', phone);
    if (!u || hashPin(body.pin, u.salt) !== u.hash) return send(res, 401, { error: 'Wrong phone number or PIN.' });
    return send(res, 200, { token: newSession(u.id), user: { id: u.id, name: u.name, role: u.role } });
  }
  if (p === '/api/me' && m === 'GET') {
    if (!me) return send(res, 401, { error: 'sign in' });
    return send(res, 200, { user: { id: me.id, name: me.name, role: me.role }, volunteering: myRows(me) });
  }
  if (p === '/api/logout' && m === 'POST') { if (me) run('DELETE FROM sessions WHERE token=?', req.headers['x-token']); return send(res, 200, {}); }

  // volunteering
  if (p === '/api/apply' && m === 'POST') {
    if (!me) return send(res, 401, { error: 'Sign in first.' });
    const mid = +body.masjid, r = q1("SELECT id FROM masjids WHERE id=? AND status='active'", mid);
    if (!r) return send(res, 404, { error: 'Masjid not found.' });
    const old = q1('SELECT status FROM volunteers WHERE masjid=? AND user=?', mid, me.id);
    if (old && old.status !== 'rejected') return send(res, 409, { error: old.status === 'approved' ? 'You are already a volunteer here.' : 'Your application is already waiting for approval.' });
    run("INSERT INTO volunteers(masjid,user,status,note,created) VALUES(?,?,?,?,?) ON CONFLICT(masjid,user) DO UPDATE SET status='pending',note=excluded.note,created=excluded.created",
      mid, me.id, 'pending', clean(body.note, 300), now());
    return send(res, 200, {});
  }
  if ((g = p.match(/^\/api\/timings\/(\d+)$/)) && m === 'POST') {
    const mid = +g[1];
    if (!canEdit(me, mid)) return send(res, 403, { error: 'Only the masjid volunteer can update times.' });
    if (limited('tm:' + me.id, 60, 36e5)) return send(res, 429, { error: 'Too many updates.' });
    db.exec('BEGIN');
    try {
      if (!body.same) {
        const t = body.times || {};
        for (const k of PRAYERS) {
          const v = t[k];
          if (v === '' || v == null) { if (k === 'jumuah') run('DELETE FROM timings WHERE masjid=? AND prayer=?', mid, k); continue; }
          if (!TIME_RE.test(v)) throw new Error('Bad time for ' + k);
          run('INSERT INTO timings(masjid,prayer,t) VALUES(?,?,?) ON CONFLICT(masjid,prayer) DO UPDATE SET t=excluded.t', mid, k, v);
        }
        const have = q1("SELECT COUNT(*) c FROM timings WHERE masjid=? AND prayer!='jumuah'", mid).c;
        if (have < 5) throw new Error('Enter all five daily prayer times.');
      } else if (!q1('SELECT COUNT(*) c FROM timings WHERE masjid=?', mid).c) throw new Error('No times saved yet.');
      const nm = clean(body.name, 120);
      if (nm.length >= 2) run('UPDATE masjids SET name=? WHERE id=?', nm, mid);
      run('UPDATE masjids SET t_updated=?, t_by=? WHERE id=?', now(), me.id, mid);
      const snap = {}; q('SELECT prayer,t FROM timings WHERE masjid=?', mid).forEach(x => snap[x.prayer] = x.t);
      run('INSERT INTO tlog(masjid,user,data,created) VALUES(?,?,?,?)', mid, me.id, JSON.stringify(snap), now());
      db.exec('COMMIT');
    } catch (e) { try { db.exec('ROLLBACK'); } catch (e2) { } return send(res, 400, { error: e.message }); }
    return send(res, 200, { masjid: shape(q1('SELECT * FROM masjids WHERE id=?', mid)) });
  }
  if (p === '/api/report' && m === 'POST') {
    if (limited('rep:' + ip, 8, 36e5)) return send(res, 429, { error: 'Too many reports.' });
    if (!q1("SELECT id FROM masjids WHERE id=? AND status='active'", +body.masjid)) return send(res, 404, { error: 'Masjid not found.' });
    run('INSERT INTO reports(masjid,text,created) VALUES(?,?,?)', +body.masjid, clean(body.text, 300), now());
    return send(res, 200, {});
  }

  // admin
  if (p.startsWith('/api/admin/')) {
    if (!me || me.role !== 'admin') return send(res, 403, { error: 'Admin only.' });
    if (p === '/api/admin/pending') {
      const apps = q(`SELECT v.id,v.note,v.created,u.name uname,u.phone,m.id mid,m.name mname,m.status mstatus FROM volunteers v JOIN users u ON u.id=v.user JOIN masjids m ON m.id=v.masjid WHERE v.status='pending' ORDER BY v.id`);
      const ms = q("SELECT m.*,u.name uname,u.phone FROM masjids m LEFT JOIN users u ON u.id=m.added_by WHERE m.status='pending' ORDER BY m.id").map(r => ({
        ...shape(r, false), uname: r.uname, phone: r.phone,
        near: nearRows(r.lat, r.lng, 0.3, "AND status='active'").slice(0, 4).map(x => ({ id: x.id, name: x.name || x.name_en || x.name_bn, dist: Math.round(x.dist * 1000) }))
      }));
      const reps = q(`SELECT r.id,r.text,r.created,m.id mid,m.name FROM reports r JOIN masjids m ON m.id=r.masjid WHERE r.open=1 ORDER BY r.id DESC LIMIT 50`);
      const vols = q(`SELECT v.id,u.name uname,u.phone,m.id mid,m.name mname FROM volunteers v JOIN users u ON u.id=v.user JOIN masjids m ON m.id=v.masjid WHERE v.status='approved' ORDER BY m.id`);
      return send(res, 200, { apps, masjids: ms, reports: reps, volunteers: vols });
    }
    if (p === '/api/admin/application' && m === 'POST') {
      run('UPDATE volunteers SET status=?, decided=? WHERE id=?', body.approve ? 'approved' : 'rejected', now(), +body.id);
      return send(res, 200, {});
    }
    if (p === '/api/admin/masjid' && m === 'POST') {
      const id = +body.id, r = q1('SELECT * FROM masjids WHERE id=?', id);
      if (!r) return send(res, 404, { error: 'not found' });
      if (body.action === 'approve') {
        run("UPDATE masjids SET status='active' WHERE id=?", id);
        if (r.added_by) run("UPDATE volunteers SET status='approved',decided=? WHERE masjid=? AND user=? AND status='pending'", now(), id, r.added_by);
      } else if (body.action === 'reject') {
        run("UPDATE masjids SET status='rejected' WHERE id=?", id);
        run("UPDATE volunteers SET status='rejected',decided=? WHERE masjid=? AND status='pending'", now(), id);
      } else if (body.action === 'merge') {
        const into = +body.into;
        if (!q1('SELECT id FROM masjids WHERE id=?', into) || into === id) return send(res, 400, { error: 'bad target' });
        run('UPDATE OR IGNORE volunteers SET masjid=? WHERE masjid=?', into, id);
        run('DELETE FROM volunteers WHERE masjid=?', id);
        run("UPDATE masjids SET status='rejected' WHERE id=?", id);
      } else if (body.action === 'rename') {
        run('UPDATE masjids SET name=? WHERE id=?', clean(body.name, 120), id);
      }
      return send(res, 200, {});
    }
    if (p === '/api/admin/volunteer/remove' && m === 'POST') { run("UPDATE volunteers SET status='removed',decided=? WHERE id=?", now(), +body.id); return send(res, 200, {}); }
    if (p === '/api/admin/report/clear' && m === 'POST') { run('UPDATE reports SET open=0 WHERE id=?', +body.id); return send(res, 200, {}); }
  }
  return send(res, 404, { error: 'not found' });
}

const PUB = path.join(__dir, 'public');
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    if (url.pathname.startsWith('/api/')) return await api(req, res, url);
    let f = path.normalize(path.join(PUB, url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname)));
    if (!f.startsWith(PUB) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'content-type': MIME[path.extname(f)] || 'application/octet-stream', 'cache-control': f.includes('vendor') ? 'public, max-age=604800' : 'no-cache' });
    fs.createReadStream(f).pipe(res);
  } catch (e) {
    if (!res.headersSent) send(res, 500, { error: 'server error' }); else res.end();
  }
});
server.listen(PORT, () => console.log('Masjid Finder on :' + PORT));
