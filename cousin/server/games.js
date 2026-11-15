// Halal, no-chance-device games for 2-4 cousins. Server-authoritative.
export const QUIZ = [
  ['How many daily prayers are obligatory?', '5', ['3', '4', '6']],
  ['How many pillars of Islam are there?', '5', ['4', '6', '7']],
  ['Which is the first month of the Hijri calendar?', 'Muharram', ['Ramadan', 'Rajab', 'Shawwal']],
  ['In which month do Muslims fast?', 'Ramadan', ['Muharram', 'Safar', 'Dhul Hijjah']],
  ['What is the holy book of Muslims called?', 'The Quran', ['The Zabur', 'The Torah', 'The Hadith']],
  ['Which city do Muslims face when they pray?', 'Makkah', ['Madinah', 'Jerusalem', 'Cairo']],
  ['Who is the last prophet of Allah?', 'Muhammad ﷺ', ['Musa (Moses)', 'Isa (Jesus)', 'Ibrahim']],
  ['Which prophet built the great Ark?', 'Nuh (Noah)', ['Musa', 'Yusuf', 'Ibrahim']],
  ['Which angel brought revelation to the prophets?', 'Jibril', ['Mikail', 'Israfil', 'Malik']],
  ['Which prayer is prayed just after sunset?', 'Maghrib', ['Fajr', 'Asr', 'Isha']],
  ['How many fard rakats are in Fajr?', '2', ['3', '4', '5']],
  ['What do we say before we start eating?', 'Bismillah', ['Alhamdulillah', 'Subhanallah', 'Allahu Akbar']],
  ['What is the festival after Ramadan called?', 'Eid al-Fitr', ['Eid al-Adha', 'Ashura', 'Mawlid']],
  ['Which prophet was saved from the fire by Allah?', 'Ibrahim', ['Nuh', 'Dawud', 'Yunus']],
  ['Which is the shortest surah of the Quran?', 'Al-Kawthar', ['Al-Ikhlas', 'Al-Fatiha', 'An-Nas']],
  ['What is the first surah of the Quran?', 'Al-Fatiha', ['Al-Baqarah', 'Al-Ikhlas', 'Yasin']],
  ['Who was the mother of Prophet Isa (Jesus)?', 'Maryam', ['Aasiya', 'Khadijah', 'Hajar']],
  ['In which city is the Prophet’s Mosque (Masjid an-Nabawi)?', 'Madinah', ['Makkah', 'Taif', 'Jeddah']],
  ['Giving a part of your wealth to the poor each year is called?', 'Zakat', ['Hajj', 'Sawm', 'Salah']],
  ['The pilgrimage to Makkah is called?', 'Hajj', ['Umrah only', 'Zakat', 'Sawm']],
  ['Which prophet was swallowed by a big fish?', 'Yunus (Jonah)', ['Musa', 'Zakariya', 'Ayyub']],
  ['Which prophet was given the Zabur (Psalms)?', 'Dawud (David)', ['Musa', 'Isa', 'Sulaiman']],
  ['Who was the first mu’adhin (caller to prayer) in Islam?', 'Bilal', ['Abu Bakr', 'Umar', 'Ali']],
  ['In which cave did the first revelation come?', 'Cave Hira', ['Cave Thawr', 'Cave Kahf', 'Cave Uhud']],
  ['How many surahs are in the Quran?', '114', ['99', '100', '110']],
  ['What is the greeting of peace in Islam?', 'Assalamu Alaikum', ['Marhaba', 'Hello', 'Ahlan only']],
  ['Which month is Hajj performed in?', 'Dhul Hijjah', ['Ramadan', 'Muharram', 'Rabi al-Awwal']],
  ['What is washing before prayer called?', 'Wudu', ['Ghusl only', 'Tayammum', 'Adhan']],
  ['What do we say when we sneeze and finish?', 'Alhamdulillah', ['Astaghfirullah', 'Inshallah', 'Bismillah']],
];
export const WORDS = [
  ['SALAH', 'We do this five times a day'], ['WUDU', 'Washing before prayer'], ['QURAN', 'The holy book'],
  ['RAMADAN', 'The month of fasting'], ['ZAKAT', 'Charity pillar of Islam'], ['MASJID', 'Where Muslims pray together'],
  ['HAJJ', 'Pilgrimage to Makkah'], ['SADAQAH', 'Giving for the sake of Allah'], ['MADINAH', 'City of the Prophet’s Mosque'],
  ['IMAN', 'Faith in your heart'], ['DUA', 'Calling upon Allah'], ['KAABA', 'The house of Allah in Makkah'],
  ['SABR', 'Patience'], ['SHUKR', 'Gratitude'], ['HALAL', 'Allowed in Islam'], ['ISLAM', 'Our religion of peace'],
  ['FAJR', 'Dawn prayer'], ['MAGHRIB', 'Sunset prayer'], ['SALAM', 'Peace'], ['JANNAH', 'Paradise'],
];
const EMO = ['\u{1F54C}', '\u{1F319}', '⭐', '\u{1F4D6}', '\u{1F42A}', '\u{1F334}', '\u{1F54B}', '\u{1F932}'];
const shuffle = a => { a = a.slice(); for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
export const GAMES = {
  quiz: { name: 'Quiz Race', min: 2, max: 6 },
  memory: { name: 'Memory Match', min: 2, max: 4 },
  dots: { name: 'Dots & Boxes', min: 2, max: 4 },
  scramble: { name: 'Word Scramble', min: 2, max: 6 },
  chase: { name: 'Mama Gonni Chase', min: 2, max: 4 },
};

export class Rooms {
  constructor(send, nameOf) { this.send = send; this.nameOf = nameOf; this.rooms = new Map(); this.byUser = new Map(); this.n = 0; }
  list() { return [...this.rooms.values()].map(r => ({ id: r.id, game: r.game, host: r.host, count: r.players.length, max: GAMES[r.game].max, state: r.state })); }
  roomOf(u) { const id = this.byUser.get(u); return id && this.rooms.get(id); }
  create(u, game) {
    if (!GAMES[game] || this.byUser.has(u)) return;
    const id = 'R' + (++this.n);
    const r = { id, game, host: u, players: [u], state: 'lobby', d: {}, scores: {}, timers: [] };
    this.rooms.set(id, r); this.byUser.set(u, id); this.push(r);
  }
  join(u, id) {
    const r = this.rooms.get(id);
    if (!r || this.byUser.has(u) || r.state !== 'lobby' || r.players.length >= GAMES[r.game].max) return;
    r.players.push(u); this.byUser.set(u, id); this.push(r);
  }
  leave(u) {
    const r = this.roomOf(u); if (!r) return;
    r.players = r.players.filter(p => p !== u); this.byUser.delete(u);
    this.send(u, { t: 'room', room: null });
    if (!r.players.length) { this.destroy(r); return; }
    if (r.host === u) r.host = r.players[0];
    if (r.state === 'playing') {
      if (r.players.length < 2) { this.finish(r, 'Not enough players left'); return; }
      if ((r.game === 'memory' || r.game === 'dots') && r.d.turnUser === u) { r.d.turn = r.d.turn % r.players.length; r.d.turnUser = r.players[r.d.turn]; r.d.flipped = []; r.d.busy = false; }
      if (r.game === 'chase') delete r.d.p[u];
    }
    this.push(r);
  }
  destroy(r) { r.timers.forEach(clearTimeout); clearInterval(r.iv); r.players.forEach(p => this.byUser.delete(p)); this.rooms.delete(r.id); }
  push(r) { r.players.forEach(p => this.send(p, { t: 'room', room: this.view(r, p) })); }
  view(r, u) {
    const v = { id: r.id, game: r.game, host: r.host, state: r.state, players: r.players, scores: r.scores, max: GAMES[r.game].max, min: GAMES[r.game].min, msg: r.msg || '' };
    const d = r.d;
    if (r.state === 'lobby') return v;
    if (r.game === 'quiz') v.q = { i: d.i, n: d.qs.length, text: d.cur && d.cur.text, opts: d.cur && d.cur.opts, left: Math.max(0, Math.ceil((d.deadline - Date.now()) / 1000)), reveal: d.reveal, correct: d.reveal ? d.cur.correct : -1, answered: Object.keys(d.ans), mine: d.ans[u] ?? -1, picks: d.reveal ? d.ans : null };
    if (r.game === 'memory') v.m = { cards: d.cards.map((c, i) => (d.matched[i] || d.flipped.includes(i)) ? c : null), matched: d.matched, flipped: d.flipped, turn: d.turnUser };
    if (r.game === 'dots') v.dt = { h: d.h, v: d.v, box: d.box, turn: d.turnUser };
    if (r.game === 'scramble') v.s = { i: d.i, n: d.words.length, letters: d.letters, clue: d.cur[1], hint: d.hint ? d.cur[0][0] + ' (' + d.cur[0].length + ' letters)' : d.cur[0].length + ' letters', reveal: d.reveal, answer: d.reveal ? d.cur[0] : '', solver: d.solver, left: Math.max(0, Math.ceil((d.deadline - Date.now()) / 1000)) };
    return v;
  }
  start(u) {
    const r = this.roomOf(u); if (!r || r.host !== u || r.state !== 'lobby') return;
    if (r.players.length < GAMES[r.game].min) { this.send(u, { t: 'toast', m: 'Need at least ' + GAMES[r.game].min + ' players' }); return; }
    r.state = 'playing'; r.scores = {}; r.players.forEach(p => r.scores[p] = 0);
    ({ quiz: this.qStart, memory: this.mStart, dots: this.dStart, scramble: this.sStart, chase: this.cStart })[r.game].call(this, r);
  }
  finish(r, msg) {
    r.timers.forEach(clearTimeout); r.timers = []; clearInterval(r.iv);
    r.state = 'over'; r.msg = msg || '';
    this.push(r);
    // back to lobby after a while if still there
  }
  rematch(u) { const r = this.roomOf(u); if (r && r.host === u && r.state === 'over') { r.state = 'lobby'; r.d = {}; r.msg = ''; this.push(r); } }
  later(r, ms, fn) { r.timers.push(setTimeout(() => { if (this.rooms.get(r.id) === r) fn(); }, ms)); }
  act(u, m) {
    const r = this.roomOf(u); if (!r || r.state !== 'playing') return;
    ({ quiz: this.qAct, memory: this.mAct, dots: this.dAct, scramble: this.sAct, chase: this.cAct })[r.game].call(this, r, u, m);
  }
  // ---------- quiz ----------
  qStart(r) { r.d = { qs: shuffle(QUIZ.map((_, i) => i)).slice(0, 10), i: -1, ans: {}, reveal: false }; this.qNext(r); }
  qNext(r) {
    const d = r.d; d.i++;
    if (d.i >= d.qs.length) return this.finish(r, this.winner(r));
    const [text, ok, bad] = QUIZ[d.qs[d.i]];
    const opts = shuffle([ok, ...bad]); d.cur = { text, opts, correct: opts.indexOf(ok) };
    d.ans = {}; d.reveal = false; d.deadline = Date.now() + 15000; d.t0 = Date.now();
    this.push(r); this.later(r, 15200, () => this.qReveal(r));
    d.tick = true;
  }
  qAct(r, u, m) {
    const d = r.d; if (m.a !== 'ans' || d.reveal || d.ans[u] !== undefined) return;
    const c = +m.c; if (!(c >= 0 && c < 4)) return;
    d.ans[u] = c;
    if (Object.keys(d.ans).length >= r.players.length) this.qReveal(r); else this.push(r);
  }
  qReveal(r) {
    const d = r.d; if (d.reveal) return; d.reveal = true;
    for (const p of r.players) if (d.ans[p] === d.cur.correct) { const left = Math.max(0, d.deadline - Date.now()) / 15000; r.scores[p] += 50 + Math.round(left * 50); }
    this.push(r); this.later(r, 3500, () => this.qNext(r));
  }
  winner(r) {
    const best = Math.max(...r.players.map(p => r.scores[p] || 0)), w = r.players.filter(p => (r.scores[p] || 0) === best);
    return w.length === r.players.length && r.players.length > 1 && best === 0 ? 'Nobody scored — try again!' : 'Winner: ' + w.map(this.nameOf).join(' & ') + ' \u{1F3C6}';
  }
  // ---------- memory ----------
  mStart(r) { r.d = { cards: shuffle([...EMO, ...EMO]), matched: Array(16).fill(false), flipped: [], busy: false, turn: 0, turnUser: r.players[0] }; this.push(r); }
  nextTurn(r) { const d = r.d; d.turn = (d.turn + 1) % r.players.length; d.turnUser = r.players[d.turn]; }
  mAct(r, u, m) {
    const d = r.d; if (m.a !== 'flip' || d.busy || d.turnUser !== u) return;
    const i = +m.i; if (!(i >= 0 && i < 16) || d.matched[i] || d.flipped.includes(i)) return;
    d.flipped.push(i); this.push(r);
    if (d.flipped.length < 2) return;
    d.busy = true;
    const [a, b] = d.flipped;
    if (d.cards[a] === d.cards[b]) {
      this.later(r, 700, () => {
        d.matched[a] = d.matched[b] = true; r.scores[u]++; d.flipped = []; d.busy = false;
        if (d.matched.every(Boolean)) return this.finish(r, this.winner(r));
        this.push(r);
      });
    } else {
      this.later(r, 1200, () => { d.flipped = []; d.busy = false; this.nextTurn(r); this.push(r); });
    }
  }
  // ---------- dots & boxes (5x5 boxes) ----------
  dStart(r) { const z = (a, b) => Array.from({ length: a }, () => Array(b).fill(-1)); r.d = { h: z(6, 5), v: z(5, 6), box: z(5, 5), turn: 0, turnUser: r.players[0] }; this.push(r); }
  dAct(r, u, m) {
    const d = r.d; if (m.a !== 'edge' || d.turnUser !== u) return;
    const { k, r: row, c } = m, me = u;
    const arr = k === 'h' ? d.h : k === 'v' ? d.v : null; if (!arr || !arr[row] || arr[row][c] !== -1) return;
    arr[row][c] = me;
    let got = 0;
    for (let br = 0; br < 5; br++) for (let bc = 0; bc < 5; bc++) {
      if (d.box[br][bc] === -1 && d.h[br][bc] !== -1 && d.h[br + 1][bc] !== -1 && d.v[br][bc] !== -1 && d.v[br][bc + 1] !== -1) { d.box[br][bc] = me; got++; }
    }
    r.scores[u] += got;
    if (d.box.every(row => row.every(x => x !== -1))) return this.finish(r, this.winner(r));
    if (!got) this.nextTurn(r);
    this.push(r);
  }
  // ---------- word scramble ----------
  sStart(r) { r.d = { words: shuffle(WORDS).slice(0, 8), i: -1 }; this.sNext(r); }
  sNext(r) {
    const d = r.d; d.i++;
    if (d.i >= d.words.length) return this.finish(r, this.winner(r));
    d.cur = d.words[d.i]; let l; do { l = shuffle(d.cur[0].split('')); } while (l.join('') === d.cur[0]);
    d.letters = l; d.reveal = false; d.solver = null; d.hint = false; d.deadline = Date.now() + 25000;
    this.push(r);
    this.later(r, 10000, () => { if (!d.reveal && d.i === r.d.i) { d.hint = true; this.push(r); } });
    const myI = d.i; this.later(r, 25200, () => { if (!d.reveal && r.d.i === myI) this.sReveal(r, null); });
  }
  sAct(r, u, m) {
    const d = r.d; if (m.a !== 'guess' || d.reveal) return;
    if (String(m.w || '').trim().toUpperCase() === d.cur[0]) { r.scores[u] += d.hint ? 60 : 100; this.sReveal(r, u); }
    else this.send(u, { t: 'wrong' });
  }
  sReveal(r, u) { const d = r.d; d.reveal = true; d.solver = u; this.push(r); this.later(r, 2500, () => this.sNext(r)); }
  // ---------- chase (real-time) ----------
  cStart(r) {
    const W = 800, H = 480, p = {};
    r.players.forEach((id, i) => { p[id] = { x: 120 + i * 140, y: H - 60, tx: 120 + i * 140, ty: H - 60, stun: 0, safe: 0, s: 0 }; });
    r.d = { W, H, p, g: { x: W - 80, y: 70 }, stars: Array.from({ length: 6 }, () => this.cStar(W, H)), t: 60 };
    r.iv = setInterval(() => this.cTick(r), 66); this.push(r);
  }
  cStar(W, H) { return { x: 40 + Math.random() * (W - 80), y: 40 + Math.random() * (H - 80) }; }
  cAct(r, u, m) { const p = r.d.p[u]; if (m.a === 'move' && p) { p.tx = Math.max(16, Math.min(r.d.W - 16, +m.x || 0)); p.ty = Math.max(16, Math.min(r.d.H - 16, +m.y || 0)); } }
  cTick(r) {
    const d = r.d, dt = 0.066; d.t -= dt;
    for (const id of Object.keys(d.p)) {
      const p = d.p[id]; p.stun = Math.max(0, p.stun - dt); p.safe = Math.max(0, p.safe - dt);
      if (p.stun > 0) continue;
      const dx = p.tx - p.x, dy = p.ty - p.y, L = Math.hypot(dx, dy);
      if (L > 4) { const s = Math.min(L, 250 * dt); p.x += dx / L * s; p.y += dy / L * s; }
      d.stars = d.stars.filter(st => { if (Math.hypot(st.x - p.x, st.y - p.y) < 28) { p.s++; r.scores[id] = p.s; return false; } return true; });
    }
    while (d.stars.length < 6) d.stars.push(this.cStar(d.W, d.H));
    // Mama Gonni chases the nearest cousin that isn't dizzy or protected
    let best = null, bd = 1e9;
    for (const id of Object.keys(d.p)) { const p = d.p[id]; if (p.stun > 0 || p.safe > 0) continue; const L = Math.hypot(p.x - d.g.x, p.y - d.g.y); if (L < bd) { bd = L; best = p; } }
    if (best) {
      const sp = (95 + (60 - d.t) * 1.2) * dt, dx = best.x - d.g.x, dy = best.y - d.g.y, L = Math.hypot(dx, dy) || 1;
      d.g.x += dx / L * sp; d.g.y += dy / L * sp;
      if (L < 34) { best.stun = 2; best.safe = 3.5; best.s = Math.max(0, best.s - 1); const id = Object.keys(d.p).find(k => d.p[k] === best); r.scores[id] = best.s; d.g.x = Math.max(30, Math.min(d.W - 30, d.g.x + (Math.random() - .5) * 240)); d.g.y = Math.max(30, Math.min(d.H - 30, d.g.y + (Math.random() - .5) * 240)); d.hit = { id, at: Date.now() }; }
    }
    const out = { t: 'cs', p: Object.fromEntries(Object.entries(d.p).map(([k, p]) => [k, [Math.round(p.x), Math.round(p.y), p.stun > 0 ? 1 : 0, p.s]])), g: [Math.round(d.g.x), Math.round(d.g.y)], st: d.stars.map(s => [Math.round(s.x), Math.round(s.y)]), left: Math.max(0, Math.ceil(d.t)), hit: d.hit && Date.now() - d.hit.at < 300 ? d.hit.id : null };
    r.players.forEach(pl => this.send(pl, out));
    if (d.t <= 0) this.finish(r, this.winner(r));
  }
}
