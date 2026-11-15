import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { DatabaseSync } from 'node:sqlite';
import { WebSocketServer } from 'ws';
import { Rooms, GAMES } from './games.js';

const __dir = path.dirname(fileURLToPath(import.meta.url));
const PORT = +process.env.PORT || 3000;
const DATA = process.env.DATA_DIR || path.join(__dir, 'data');
const PHOTOS = path.join(DATA, 'photos');
fs.mkdirSync(PHOTOS, { recursive: true });
const AUDIO = path.join(DATA, 'audio');
fs.mkdirSync(AUDIO, { recursive: true });
const AUDIO_TYPES = { mp3: 'audio/mpeg', m4a: 'audio/mp4', aac: 'audio/aac', ogg: 'audio/ogg', opus: 'audio/ogg', wav: 'audio/wav', webm: 'audio/webm' };
const MAX_AUDIO = 150e6;
const TTS_DIR = path.join(DATA, 'tts');
fs.mkdirSync(TTS_DIR, { recursive: true });
const TTS_KEY = (process.env.GOOGLE_TTS_KEY || '').trim();
const TTS_VOICE = process.env.TTS_VOICE || 'ar-XA-Wavenet-A';
const TTS_ENDPOINT = process.env.TTS_ENDPOINT || 'https://texttospeech.googleapis.com/v1/text:synthesize';
const ttsHits = new Map();
const db = new DatabaseSync(path.join(DATA, 'cousin.db'));
db.exec(`
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY, name TEXT UNIQUE COLLATE NOCASE, salt TEXT, hash TEXT, avatar TEXT, color TEXT, role TEXT, created INTEGER);
CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, user INTEGER, created INTEGER);
CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, user INTEGER, text TEXT, created INTEGER);
CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY, title TEXT, body TEXT, by INTEGER, updated INTEGER);
CREATE TABLE IF NOT EXISTS deeds(id INTEGER PRIMARY KEY, title TEXT, emoji TEXT, points INTEGER, repeat TEXT, together INTEGER, by INTEGER);
CREATE TABLE IF NOT EXISTS done(deed INTEGER, user INTEGER, day TEXT, PRIMARY KEY(deed,user,day));
CREATE TABLE IF NOT EXISTS photos(id INTEGER PRIMARY KEY, user INTEGER, album TEXT, caption TEXT, file TEXT, created INTEGER);
CREATE TABLE IF NOT EXISTS likes(photo INTEGER, user INTEGER, PRIMARY KEY(photo,user));
CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, title TEXT, date TEXT, type TEXT, yearly INTEGER, by INTEGER);
CREATE TABLE IF NOT EXISTS lessons(id INTEGER PRIMARY KEY, title TEXT, kind TEXT, body TEXT, created INTEGER);
CREATE TABLE IF NOT EXISTS wazes(id INTEGER PRIMARY KEY, title TEXT, file TEXT, size INTEGER, created INTEGER);
CREATE TABLE IF NOT EXISTS track(user INTEGER, day TEXT, salah INTEGER DEFAULT 0, pages INTEGER DEFAULT 0, PRIMARY KEY(user, day));
CREATE TABLE IF NOT EXISTS wlearn(user INTEGER, word TEXT, day TEXT, PRIMARY KEY(user, word));
CREATE TABLE IF NOT EXISTS settings(k TEXT PRIMARY KEY, v TEXT);
`);
const q = (sql, ...a) => db.prepare(sql).all(...a);
const q1 = (sql, ...a) => db.prepare(sql).get(...a);
const run = (sql, ...a) => db.prepare(sql).run(...a);

// ---------- settings / seed ----------
const getSet = k => (q1('SELECT v FROM settings WHERE k=?', k) || {}).v;
const setSet = (k, v) => run('INSERT INTO settings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v', k, v);
if (!getSet('code')) setSet('code', (process.env.FAMILY_CODE || 'cousins').trim());
const hashPin = (pin, salt) => crypto.scryptSync(String(pin), salt, 32).toString('hex');
const AVATARS = ['\u{1F338}', '\u{1F31F}', '\u{1F319}', '\u{1F54C}', '\u{1F33A}', '\u{1F340}', '\u{1F308}', '\u{1F680}', '\u26BD', '\u{1F388}', '\u{1F34E}', '\u{1F33B}'];
const COLORS = ['#ff7aa2', '#ffb347', '#4ecdc4', '#6c8cff', '#a78bfa', '#7bd88f', '#ff6b6b', '#f7c948'];
function addUser(name, pin, role) {
  const salt = crypto.randomBytes(8).toString('hex'), n = q1('SELECT COUNT(*) c FROM users').c;
  const r = run('INSERT INTO users(name,salt,hash,avatar,color,role,created) VALUES(?,?,?,?,?,?,?)', name, salt, hashPin(pin, salt), AVATARS[n % AVATARS.length], COLORS[n % COLORS.length], role, Date.now());
  return Number(r.lastInsertRowid);
}
if (process.env.ADMIN_NAME && process.env.ADMIN_PIN && !q1('SELECT id FROM users WHERE name=?', process.env.ADMIN_NAME)) addUser(process.env.ADMIN_NAME.slice(0, 20), process.env.ADMIN_PIN, 'admin');
if (!q1('SELECT id FROM deeds')) {
  [['Pray a prayer on time', '\u{1F54C}', 10, 'daily', 0], ['Read a page of Quran', '\u{1F4D6}', 10, 'daily', 0], ['Say salam to everyone', '\u{1F44B}', 5, 'daily', 0], ['Help at home without being asked', '\u{1F9F9}', 15, 'daily', 0],
   ['Give sadaqah together', '\u{1F49B}', 20, 'once', 1], ['Share a toy or snack', '\u{1F36A}', 5, 'daily', 0], ['Call or visit Nani/Dadu', '\u{1F4DE}', 15, 'once', 0], ['Make dua for the family', '\u{1F932}', 5, 'daily', 0]]
    .forEach(d => run('INSERT INTO deeds(title,emoji,points,repeat,together,by) VALUES(?,?,?,?,?,0)', ...d));
}

// ---------- helpers ----------
const clean = (s, n) => String(s ?? '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').trim().slice(0, n);
const today = () => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); };
const publicUser = u => ({ id: u.id, name: u.name, avatar: u.avatar, color: u.color, role: u.role });
const users = () => q('SELECT id,name,avatar,color,role FROM users ORDER BY id');
const userById = id => q1('SELECT id,name,avatar,color,role FROM users WHERE id=?', id);
const BAD = ['stupid', 'idiot', 'dumb', 'shut up', 'hate you', 'ugly', 'loser', 'fat', 'damn', 'crap', 'hell', 'sucks', 'kill', 'moron', ...(process.env.EXTRA_BAD_WORDS || '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean)];
const isRude = t => { const x = ' ' + t.toLowerCase().replace(/[^a-zঀ-৿ ]/g, ' ') + ' '; return BAD.some(w => x.includes(' ' + w + ' ') || x.includes(' ' + w + 's ')); };
const fails = new Map();
const tooMany = ip => { const f = (fails.get(ip) || []).filter(t => Date.now() - t < 600000); fails.set(ip, f); return f.length >= 12; };
const fail = ip => { const f = fails.get(ip) || []; f.push(Date.now()); fails.set(ip, f); };
const json = (res, code, o) => { res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(JSON.stringify(o)); };
const userForToken = t => t && q1('SELECT u.id,u.name,u.avatar,u.color,u.role FROM sessions s JOIN users u ON u.id=s.user WHERE s.token=?', String(t));
const tokenFor = id => { const t = crypto.randomBytes(24).toString('hex'); run('INSERT INTO sessions VALUES(?,?,?)', t, id, Date.now()); return t; };

// ---------- state slices ----------
const clients = new Set();
const send = (ws, m) => { if (ws.readyState === 1) ws.send(JSON.stringify(m)); };
const toUser = (id, m) => clients.forEach(c => c.user.id === id && send(c.ws, m));
const all = m => clients.forEach(c => send(c.ws, m));
const online = () => [...new Set([...clients].map(c => c.user.id))];
const slices = {
  users: () => ({ t: 'users', users: users(), online: online() }),
  deeds: () => {
    const d = today();
    return { t: 'deeds', deeds: q('SELECT * FROM deeds ORDER BY id'), tdone: q('SELECT deed d, user u FROM done WHERE day=?', d), once: q("SELECT x.deed d, x.user u FROM done x JOIN deeds t ON t.id=x.deed WHERE t.repeat='once'"),
      board: q('SELECT x.user u, SUM(t.points) pts, COUNT(*) n FROM done x JOIN deeds t ON t.id=x.deed GROUP BY x.user'), week: q("SELECT x.user u, SUM(t.points) pts FROM done x JOIN deeds t ON t.id=x.deed WHERE x.day>=date('now','-6 day') GROUP BY x.user") };
  },
  lessons: () => ({ t: 'lessons', lessons: q('SELECT * FROM lessons ORDER BY id DESC LIMIT 200') }),
  wazes: () => ({ t: 'wazes', wazes: q('SELECT * FROM wazes ORDER BY id DESC LIMIT 300') }),
  events: () => ({ t: 'events', events: q('SELECT * FROM events ORDER BY date') }),
  photos: () => ({ t: 'photos', photos: q('SELECT * FROM photos ORDER BY id DESC LIMIT 400').map(p => ({ ...p, likes: q('SELECT user FROM likes WHERE photo=?', p.id).map(l => l.user) })) }),
  notes: () => ({ t: 'notes', notes: q('SELECT * FROM notes ORDER BY updated DESC') }),
};
const bcast = k => all(slices[k]());
const chatRows = () => q('SELECT * FROM (SELECT * FROM messages ORDER BY id DESC LIMIT 150) ORDER BY id');

// ---------- games ----------
const rooms = new Rooms((id, m) => toUser(id, m), id => (userById(id) || {}).name || 'Someone');
const pushRooms = () => all({ t: 'rooms', rooms: rooms.list() });
const _push = rooms.push.bind(rooms);
for (const fn of ['create', 'join', 'leave', 'start', 'rematch', 'act']) { const o = rooms[fn].bind(rooms); rooms[fn] = (...a) => { const r = o(...a); pushRooms(); return r; }; }

// ---------- notes locks ----------
const locks = new Map();
const lockOf = id => { const l = locks.get(id); return l && Date.now() - l.ts < 12000 ? l : null; };

// ---------- salah & quran tracker (private per member) ----------
const dayStr = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
const dayAgo = n => { const d = new Date(); d.setDate(d.getDate() - n); return dayStr(d); };
const trackMsg = uid => ({ t: 'track', trk: q('SELECT day, salah, pages, mins FROM track WHERE user=? AND day>=? ORDER BY day DESC', uid, dayAgo(60)), trkTotal: (q1('SELECT COALESCE(SUM(pages),0) p FROM track WHERE user=?', uid) || {}).p || 0 });
try { db.exec('ALTER TABLE track ADD COLUMN mins INTEGER DEFAULT 0'); } catch (e) { }
const bits = n => { let c = 0; for (let i = 0; i < 5; i++) if (n & (1 << i)) c++; return c; };

const wlMsg = uid => ({ t: 'wl', wl: q('SELECT word FROM wlearn WHERE user=?', uid).map(r => r.word) });
// weekly winners (week runs Saturday to Friday; parents are not in the contest)
const addDays = (str, n) => { const d = new Date(str + 'T00:00:00'); d.setDate(d.getDate() + n); return dayStr(d); };
function winners() {
  const sinceSat = (new Date().getDay() + 1) % 7, curStart = dayAgo(sinceSat), lastStart = addDays(curStart, -7), lastEnd = addDays(curStart, -1);
  const top = (col, from, to) => { const rows = q(`SELECT u.id, u.name, u.avatar, u.color, SUM(t.${col}) v FROM track t JOIN users u ON u.id=t.user WHERE t.day>=? AND t.day<=? AND u.role!='admin' GROUP BY u.id HAVING v>0 ORDER BY v DESC`, from, to); return rows.filter(r => r.v === (rows[0] || {}).v); };
  const wrows = (from, to) => q("SELECT u.id, u.name, u.avatar, u.color, COUNT(*) v FROM wlearn w JOIN users u ON u.id=w.user WHERE w.day>=? AND w.day<=? AND u.role!='admin' GROUP BY u.id ORDER BY v DESC", from, to);
  const topW = rows => rows.filter(r => r.v === (rows[0] || {}).v), target = +getSet('wtarget') || 15, lw = wrows(lastStart, lastEnd), cw = wrows(curStart, today());
  return { wk: lastStart, target, last: { quran: top('pages', lastStart, lastEnd), study: top('mins', lastStart, lastEnd), words: topW(lw), hit: lw.filter(r => r.v >= target) }, cur: { quran: top('pages', curStart, today()), study: top('mins', curStart, today()), words: topW(cw), board: cw } };
}

// ---------- websocket handling ----------
function handle(c, m) {
  const me = c.user, ws = c.ws;
  const admin = me.role === 'admin';
  switch (m.t) {
    case 'chat': {
      const text = clean(m.text, 400); if (!text || Date.now() - (c.lastChat || 0) < 400) return; c.lastChat = Date.now();
      if (isRude(text)) return send(ws, { t: 'toast', m: 'Kind words only, please' });
      const r = run('INSERT INTO messages(user,text,created) VALUES(?,?,?)', me.id, text, Date.now());
      const row = q1('SELECT * FROM messages WHERE id=?', Number(r.lastInsertRowid));
      return all({ t: 'chat', m: row });
    }
    case 'chat:del': { const row = q1('SELECT * FROM messages WHERE id=?', +m.id); if (row && (admin || row.user === me.id)) { run('DELETE FROM messages WHERE id=?', row.id); all({ t: 'chat:del', id: row.id }); } return; }
    case 'chat:clear': if (admin) { run('DELETE FROM messages'); all({ t: 'chat:clear' }); } return;
    case 'typing': return clients.forEach(o => o !== c && send(o.ws, { t: 'typing', u: me.id }));
    case 'note:new': { const r = run('INSERT INTO notes(title,body,by,updated) VALUES(?,?,?,?)', clean(m.title, 60) || 'New note', '', me.id, Date.now()); bcast('notes'); return send(ws, { t: 'note:opened', id: Number(r.lastInsertRowid) }); }
    case 'note:lock': { const id = +m.id, l = lockOf(id); if (!l || l.u === me.id) { locks.set(id, { u: me.id, ts: Date.now() }); return all({ t: 'locks', locks: Object.fromEntries([...locks].filter(([k]) => lockOf(k)).map(([k, v]) => [k, v.u])) }); } return send(ws, { t: 'toast', m: (userById(l.u) || {}).name + ' is editing this note' }); }
    case 'note:unlock': { const l = locks.get(+m.id); if (l && l.u === me.id) { locks.delete(+m.id); all({ t: 'locks', locks: Object.fromEntries([...locks].filter(([k]) => lockOf(k)).map(([k, v]) => [k, v.u])) }); } return; }
    case 'note:save': { const id = +m.id, l = lockOf(id); if (l && l.u !== me.id) return; run('UPDATE notes SET title=?, body=?, by=?, updated=? WHERE id=?', clean(m.title, 60) || 'Note', String(m.body ?? '').slice(0, 20000), me.id, Date.now(), id); return bcast('notes'); }
    case 'note:del': { const n = q1('SELECT * FROM notes WHERE id=?', +m.id); if (n && (admin || n.by === me.id)) { run('DELETE FROM notes WHERE id=?', n.id); bcast('notes'); } return; }
    case 'deed:add': { const t = clean(m.title, 60); if (!t) return; run('INSERT INTO deeds(title,emoji,points,repeat,together,by) VALUES(?,?,?,?,?,?)', t, clean(m.emoji, 4) || '⭐', Math.max(1, Math.min(50, +m.points || 5)), m.repeat === 'once' ? 'once' : 'daily', m.together ? 1 : 0, me.id); return bcast('deeds'); }
    case 'deed:del': { const d = q1('SELECT * FROM deeds WHERE id=?', +m.id); if (d && (admin || d.by === me.id)) { run('DELETE FROM deeds WHERE id=?', d.id); run('DELETE FROM done WHERE deed=?', d.id); bcast('deeds'); } return; }
    case 'deed:done': { const d = q1('SELECT * FROM deeds WHERE id=?', +m.id); if (!d) return; if (d.repeat === 'once' && q1('SELECT 1 x FROM done WHERE deed=? AND user=?', d.id, me.id)) return; run('INSERT OR IGNORE INTO done VALUES(?,?,?)', d.id, me.id, today()); bcast('deeds'); return all({ t: 'cheer', u: me.id, deed: d.title, emoji: d.emoji, points: d.points }); }
    case 'deed:undo': { const d = q1('SELECT * FROM deeds WHERE id=?', +m.id); if (!d) return; if (d.repeat === 'once') run('DELETE FROM done WHERE deed=? AND user=?', d.id, me.id); else run('DELETE FROM done WHERE deed=? AND user=? AND day=?', d.id, me.id, today()); return bcast('deeds'); }
    case 'lesson:add': { if (!admin) return; const t = clean(m.title, 80), b = String(m.body ?? '').slice(0, 6000).trim(); if (!t || !b) return; run('INSERT INTO lessons(title,kind,body,created) VALUES(?,?,?,?)', t, ['story', 'writing', 'lesson'].includes(m.kind) ? m.kind : 'lesson', b, Date.now()); return bcast('lessons'); }
    case 'lesson:del': if (admin) { run('DELETE FROM lessons WHERE id=?', +m.id); bcast('lessons'); } return;
    case 'waz:del': { if (!admin) return; const w = q1('SELECT * FROM wazes WHERE id=?', +m.id); if (w) { run('DELETE FROM wazes WHERE id=?', w.id); fs.rm(path.join(AUDIO, path.basename(w.file)), () => {}); bcast('wazes'); } return; }
    case 'track:set': {
      const day = String(m.day || ''); if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || day > today() || day < dayAgo(7)) return;
      const salah = (+m.salah || 0) & 31, pages = Math.max(0, Math.min(300, Math.round(+m.pages || 0)));
      run('INSERT INTO track(user,day,salah,pages) VALUES(?,?,?,?) ON CONFLICT(user,day) DO UPDATE SET salah=excluded.salah, pages=excluded.pages', me.id, day, salah, pages);
      for (const o of clients) if (o.user.id === me.id) send(o.ws, trackMsg(me.id));
      all({ t: 'win', win: winners() });
      return;
    }
    case 'word:learn': {
      const id = String(m.id || ''); if (!/^[a-z]{1,6}:\d{1,3}$/.test(id)) return;
      run('INSERT OR IGNORE INTO wlearn(user,word,day) VALUES(?,?,?)', me.id, id, today());
      for (const o of clients) if (o.user.id === me.id) send(o.ws, wlMsg(me.id));
      all({ t: 'win', win: winners() });
      return;
    }
    case 'journey:target': { if (!admin) return; const n = Math.max(3, Math.min(100, Math.round(+m.n || 0))); setSet('wtarget', String(n)); all({ t: 'win', win: winners() }); return; }
    case 'study:add': {
      const mins = Math.max(1, Math.min(180, Math.round(+m.mins || 0)));
      run('INSERT INTO track(user,day,salah,pages,mins) VALUES(?,?,0,0,?) ON CONFLICT(user,day) DO UPDATE SET mins=MIN(600, COALESCE(mins,0)+excluded.mins)', me.id, today(), mins);
      for (const o of clients) if (o.user.id === me.id) send(o.ws, trackMsg(me.id));
      all({ t: 'win', win: winners() });
      return;
    }
    case 'track:all': {
      if (!admin) return;
      const from = dayAgo(6), members = q('SELECT id, name, avatar, color, role FROM users ORDER BY id').map(u => {
        const week = q('SELECT day, salah, pages FROM track WHERE user=? AND day>=? ORDER BY day', u.id, from);
        return { id: u.id, name: u.name, avatar: u.avatar, color: u.color, role: u.role, week, weekSalah: week.reduce((a, r) => a + bits(r.salah), 0), weekPages: week.reduce((a, r) => a + r.pages, 0), total: (q1('SELECT COALESCE(SUM(pages),0) p FROM track WHERE user=?', u.id) || {}).p || 0 };
      });
      return send(ws, { t: 'track:all', members, from });
    }
    case 'event:add': { const t = clean(m.title, 60), dt = clean(m.date, 10); if (!t || !/^\d{4}-\d{2}-\d{2}$/.test(dt)) return; run('INSERT INTO events(title,date,type,yearly,by) VALUES(?,?,?,?,?)', t, dt, ['birthday', 'eid', 'gathering', 'other'].includes(m.type) ? m.type : 'other', m.yearly ? 1 : 0, me.id); return bcast('events'); }
    case 'event:del': { const e = q1('SELECT * FROM events WHERE id=?', +m.id); if (e && (admin || e.by === me.id)) { run('DELETE FROM events WHERE id=?', e.id); bcast('events'); } return; }
    case 'photo:like': { const id = +m.id; if (q1('SELECT 1 x FROM likes WHERE photo=? AND user=?', id, me.id)) run('DELETE FROM likes WHERE photo=? AND user=?', id, me.id); else run('INSERT INTO likes VALUES(?,?)', id, me.id); return bcast('photos'); }
    case 'photo:del': { const p = q1('SELECT * FROM photos WHERE id=?', +m.id); if (p && (admin || p.user === me.id)) { run('DELETE FROM photos WHERE id=?', p.id); run('DELETE FROM likes WHERE photo=?', p.id); fs.rm(path.join(PHOTOS, path.basename(p.file)), () => {}); bcast('photos'); } return; }
    case 'profile': { const av = clean(m.avatar, 8), col = COLORS.includes(m.color) ? m.color : me.color; run('UPDATE users SET avatar=?, color=? WHERE id=?', AVATARS.includes(av) ? av : me.avatar, col, me.id); const u = userById(me.id); c.user = u; return all(slices.users()); }
    case 'admin:remove': if (admin && +m.id !== me.id) { run('DELETE FROM users WHERE id=?', +m.id); run('DELETE FROM sessions WHERE user=?', +m.id); clients.forEach(o => { if (o.user.id === +m.id) { send(o.ws, { t: 'kicked' }); o.ws.close(); } }); all(slices.users()); } return;
    case 'admin:pin': if (admin && /^\d{4}$/.test(String(m.pin))) { const salt = crypto.randomBytes(8).toString('hex'); run('UPDATE users SET salt=?, hash=? WHERE id=?', salt, hashPin(m.pin, salt), +m.id); send(ws, { t: 'toast', m: 'PIN changed' }); } return;
    case 'admin:code': if (admin && clean(m.code, 30).length >= 4) { setSet('code', clean(m.code, 30)); send(ws, { t: 'toast', m: 'Family code changed' }); send(ws, { t: 'code', code: getSet('code') }); } return;
    case 'admin:role': if (admin && +m.id !== me.id) { run('UPDATE users SET role=? WHERE id=?', m.role === 'admin' ? 'admin' : 'kid', +m.id); all(slices.users()); } return;
    case 'g:create': return rooms.create(me.id, m.game);
    case 'g:join': return rooms.join(me.id, m.id);
    case 'g:leave': return rooms.leave(me.id);
    case 'g:start': return rooms.start(me.id);
    case 'g:again': return rooms.rematch(me.id);
    case 'g:act': return rooms.act(me.id, m);
    case 'ping': return send(ws, { t: 'pong' });
  }
}

// ---------- http ----------
const INDEX = () => fs.readFileSync(path.join(__dir, 'public', 'index.html'));
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x'), ip = req.headers['x-forwarded-for']?.split(',')[0] || req.socket.remoteAddress;
  try {
    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) { res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-cache' }); return res.end(INDEX()); }
    if (req.method === 'GET' && url.pathname === '/health') return json(res, 200, { ok: true });
    if (req.method === 'GET' && url.pathname === '/manifest.json') return json(res, 200, { name: 'Cousin Heaven', short_name: 'Cousins', start_url: '/', display: 'standalone', background_color: '#fff4e0', theme_color: '#ff7aa2' });
    if (req.method === 'POST' && url.pathname === '/api/join') {
      if (tooMany(ip)) return json(res, 429, { error: 'slow' });
      let body = ''; for await (const ch of req) { body += ch; if (body.length > 2000) break; }
      let b = {}; try { b = JSON.parse(body); } catch { }
      const name = clean(b.name, 20), pin = String(b.pin || ''), code = clean(b.code, 30);
      if (!/^\d{4}$/.test(pin) || name.length < 2) return json(res, 400, { error: 'input' });
      const u = q1('SELECT * FROM users WHERE name=?', name);
      if (u) { // returning member: name + PIN is enough (code optional)
        const h = hashPin(pin, u.salt); if (!crypto.timingSafeEqual(Buffer.from(h), Buffer.from(u.hash))) { fail(ip); return json(res, 401, { error: 'pin' }); }
        return json(res, 200, { token: tokenFor(u.id), user: publicUser(u) });
      }
      if (code.toLowerCase() !== String(getSet('code')).toLowerCase()) { fail(ip); return json(res, 403, { error: 'code' }); }
      const id = addUser(name, pin, 'kid'); all(slices.users());
      return json(res, 200, { token: tokenFor(id), user: publicUser(userById(id)) });
    }
    if (req.method === 'POST' && url.pathname === '/api/photos') {
      const u = userForToken(req.headers['x-token']); if (!u) return json(res, 401, { error: 'auth' });
      const type = req.headers['content-type'] || '', ext = type.includes('png') ? 'png' : type.includes('webp') ? 'webp' : type.includes('jpeg') ? 'jpg' : null;
      if (!ext) return json(res, 400, { error: 'type' });
      const chunks = []; let n = 0; for await (const ch of req) { n += ch.length; if (n > 6e6) return json(res, 413, { error: 'big' }); chunks.push(ch); }
      const file = crypto.randomBytes(12).toString('hex') + '.' + ext; fs.writeFileSync(path.join(PHOTOS, file), Buffer.concat(chunks));
      run('INSERT INTO photos(user,album,caption,file,created) VALUES(?,?,?,?,?)', u.id, clean(url.searchParams.get('album'), 30) || 'Everyday', clean(url.searchParams.get('caption'), 120), file, Date.now());
      bcast('photos'); return json(res, 200, { ok: true });
    }
    if (req.method === 'GET' && url.pathname === '/api/tts') {
      if (!TTS_KEY) return json(res, 503, { error: 'no_key' });
      if (String(url.searchParams.get('c') || '').toLowerCase() !== String(getSet('code')).toLowerCase()) { fail(ip); return json(res, 403, { error: 'code' }); }
      const text = String(url.searchParams.get('t') || '').trim();
      if (!text || text.length > 300 || !/[\u0600-\u06FF]/.test(text)) return json(res, 400, { error: 'text' });
      const f = path.join(TTS_DIR, crypto.createHash('sha1').update(TTS_VOICE + '|' + text).digest('hex') + '.mp3');
      if (!fs.existsSync(f)) {
        const now = Date.now(), h = (ttsHits.get(ip) || []).filter(x => now - x < 60000);
        if (h.length >= 60) return json(res, 429, { error: 'slow' });
        h.push(now); ttsHits.set(ip, h);
        try {
          const r = await fetch(TTS_ENDPOINT + '?key=' + encodeURIComponent(TTS_KEY), { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ input: { text }, voice: { languageCode: 'ar-XA', name: TTS_VOICE }, audioConfig: { audioEncoding: 'MP3', speakingRate: 0.85 } }), signal: AbortSignal.timeout(15000) });
          if (!r.ok) { console.log('TTS error', r.status, (await r.text()).slice(0, 200)); return json(res, 502, { error: 'google', status: r.status }); }
          const j = await r.json(); fs.writeFileSync(f, Buffer.from(j.audioContent, 'base64'));
        } catch (e) { console.log('TTS fail', e.message); return json(res, 502, { error: 'google' }); }
      }
      const st = fs.statSync(f); res.writeHead(200, { 'content-type': 'audio/mpeg', 'content-length': st.size, 'cache-control': 'public, max-age=31536000' }); return fs.createReadStream(f).pipe(res);
    }
    if (req.method === 'POST' && url.pathname === '/api/waz') {
      const u = userForToken(req.headers['x-token']); if (!u) return json(res, 401, { error: 'auth' });
      if (u.role !== 'admin') return json(res, 403, { error: 'admin' });
      const ext = String(url.searchParams.get('ext') || '').toLowerCase();
      if (!AUDIO_TYPES[ext]) return json(res, 400, { error: 'type' });
      const file = crypto.randomBytes(12).toString('hex') + '.' + ext, fp = path.join(AUDIO, file), out = fs.createWriteStream(fp);
      let n = 0, bad = false;
      try { for await (const ch of req) { n += ch.length; if (n > MAX_AUDIO) { bad = true; break; } if (!out.write(ch)) await new Promise(r => out.once('drain', r)); } } catch (e) { bad = true; }
      await new Promise(r => out.end(r));
      if (bad || n === 0) { fs.rm(fp, () => {}); return json(res, bad ? 413 : 400, { error: 'upload' }); }
      run('INSERT INTO wazes(title,file,size,created) VALUES(?,?,?,?)', clean(url.searchParams.get('title'), 80) || 'Waz', file, n, Date.now());
      bcast('wazes'); return json(res, 200, { ok: true });
    }
    if (req.method === 'GET' && url.pathname.startsWith('/audio/')) {
      if (!userForToken(url.searchParams.get('t'))) { res.writeHead(401); return res.end(); }
      const f = path.join(AUDIO, path.basename(url.pathname)); let st; try { st = fs.statSync(f); } catch (e) { res.writeHead(404); return res.end(); }
      const size = st.size, type = AUDIO_TYPES[f.split('.').pop()] || 'audio/mpeg', rg = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
      if (rg) {
        let a = rg[1] ? +rg[1] : 0, b = rg[2] ? +rg[2] : size - 1; if (!rg[1] && rg[2]) { a = Math.max(0, size - +rg[2]); b = size - 1; } if (b >= size) b = size - 1;
        if (a > b) { res.writeHead(416, { 'content-range': 'bytes */' + size }); return res.end(); }
        res.writeHead(206, { 'content-type': type, 'accept-ranges': 'bytes', 'content-range': `bytes ${a}-${b}/${size}`, 'content-length': b - a + 1 });
        return fs.createReadStream(f, { start: a, end: b }).pipe(res);
      }
      res.writeHead(200, { 'content-type': type, 'accept-ranges': 'bytes', 'content-length': size }); return fs.createReadStream(f).pipe(res);
    }
    if (req.method === 'GET' && url.pathname.startsWith('/photos/')) {
      if (!userForToken(url.searchParams.get('t'))) { res.writeHead(401); return res.end(); }
      const f = path.join(PHOTOS, path.basename(url.pathname)); if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
      res.writeHead(200, { 'content-type': f.endsWith('.png') ? 'image/png' : f.endsWith('.webp') ? 'image/webp' : 'image/jpeg', 'cache-control': 'private, max-age=86400' }); return fs.createReadStream(f).pipe(res);
    }
    res.writeHead(404); res.end('not found');
  } catch (e) { console.error(e); try { json(res, 500, { error: 'server' }); } catch { } }
});
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 100000 });
wss.on('connection', (ws, req) => {
  const url = new URL(req.url, 'http://x'), u = userForToken(url.searchParams.get('token'));
  if (!u) { send(ws, { t: 'kicked' }); return ws.close(); }
  const c = { ws, user: u }; clients.add(c);
  const init = { t: 'init', me: u, code: u.role === 'admin' ? getSet('code') : undefined, chat: chatRows(), rooms: rooms.list(), room: (() => { const r = rooms.roomOf(u.id); return r ? rooms.view(r, u.id) : null; })(), locks: Object.fromEntries([...locks].filter(([k]) => lockOf(k)).map(([k, v]) => [k, v.u])), day: today(), games: GAMES };
  for (const k of ['users', 'deeds', 'events', 'photos', 'notes', 'lessons', 'wazes']) { const s = slices[k](); delete s.t; Object.assign(init, s); }
  { const tm = trackMsg(u.id); delete tm.t; Object.assign(init, tm); init.win = winners(); const wm = wlMsg(u.id); init.wl = wm.wl; }
  send(ws, init);
  all(slices.users());
  ws.on('message', d => { let m; try { m = JSON.parse(d); } catch { return; } try { handle(c, m); } catch (e) { console.error('msg error', e); } });
  ws.on('close', () => { clients.delete(c); if (![...clients].some(o => o.user.id === c.user.id)) rooms.leave(c.user.id); all(slices.users()); });
  ws.on('error', () => {});
});
setInterval(() => wss.clients.forEach(ws => { if (ws.isAlive === false) return ws.terminate(); ws.isAlive = false; ws.ping(); }), 25000);
wss.on('connection', ws => { ws.isAlive = true; ws.on('pong', () => ws.isAlive = true); });
server.requestTimeout = 0; server.headersTimeout = 60000;
server.listen(PORT, () => console.log('Cousin Heaven server on :' + PORT));
