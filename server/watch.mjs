import http from 'node:http';
import { randomBytes, randomInt } from 'node:crypto';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { WebSocketServer } from 'ws';

const token = () => randomBytes(18).toString('base64url');
const storage = process.env.ROOM_STORE;
const rooms = new Map();
if (storage) {
  try {
    for (const room of JSON.parse(readFileSync(storage, 'utf8'))) {
      rooms.set(room.id, { ...room, connections: new Map() });
    }
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
}
function save() {
  if (!storage) return;
  const data = [...rooms.values()].map(({ connections, ...room }) => room);
  writeFileSync(storage + '.tmp', JSON.stringify(data), { mode: 0o600 });
  renameSync(storage + '.tmp', storage);
}
const html = readFileSync(new URL('./watch.html', import.meta.url));
const app = readFileSync(new URL('./watch.js', import.meta.url));
const server = http.createServer((req, res) => {
  const path = req.url.split('?')[0];
  if (req.method !== 'GET') return res.writeHead(405).end();
  if (path === '/health') return res.writeHead(200).end('ok');
  if (path !== '/' && path !== '/watch.js') return res.writeHead(404).end();
  res.writeHead(200, {
    'Cache-Control': 'no-cache',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; style-src 'unsafe-inline'; media-src https: blob:; connect-src 'self' https:; worker-src 'self' blob:; frame-ancestors 'none'; object-src 'none'",
    'Content-Type': (path === '/' ? 'text/html' : 'text/javascript') + '; charset=utf-8',
  }).end(path === '/' ? html : app);
});
const sockets = new WebSocketServer({ noServer: true, maxPayload: 8192 });
server.on('upgrade', (req, socket, head) => {
  let host;
  try { host = new URL(req.headers.origin).host; } catch {}
  if (req.url !== '/room' || (req.headers.origin && host !== req.headers.host)) return socket.destroy();
  sockets.handleUpgrade(req, socket, head, ws => sockets.emit('connection', ws, req));
});

function send(ws, message) {
  if (ws.readyState !== 1) return;
  if (ws.bufferedAmount > 512 * 1024) return ws.terminate();
  ws.send(JSON.stringify(message));
}
function broadcast(room, message) {
  for (const ws of room.connections.values()) send(ws, message);
}
function presence(room) {
  broadcast(room, { type: 'presence', count: room.connections.size });
}
function videoUrl(value) {
  let url;
  try { url = new URL(value); } catch {}
  if (!url || url.protocol !== 'https:' || url.username || url.password || !/\.(mp4|webm|m3u8)$/i.test(url.pathname)) {
    throw Error('Use um link HTTPS direto para MP4, WebM ou HLS (.m3u8).');
  }
  return url.href;
}
const failed = new Map();
sockets.on('connection', (ws, req) => {
  let room, session, member, budget = 30, last = Date.now();
  const ip = req.socket.remoteAddress;
  ws.alive = true;
  ws.on('pong', () => { ws.alive = true; });
  ws.on('error', () => ws.terminate());
  ws.on('close', () => {
    if (room?.connections.get(session) !== ws) return;
    room.connections.delete(session);
    presence(room);
  });
  ws.on('message', (payload, binary) => {
    if (binary) return ws.close(1003);
    const now = Date.now();
    budget = Math.min(30, budget + (now - last) / 200);
    last = now;
    if (budget-- < 1) return ws.close(1008);
    try {
      const msg = JSON.parse(payload);
      if (msg.type === 'clock') return send(ws, { type: 'clock', now, sent: msg.sent });
      if (!room) {
        const name = typeof msg.name === 'string' && msg.name.trim().slice(0, 30);
        if (!name) throw Error('Digite seu nome.');
        let target = rooms.get(msg.room);
        if (msg.type === 'create') {
          if (rooms.size >= 1000) throw Error('Servidor ocupado.');
          const url = videoUrl(msg.url);
          session = token();
          target = { id: token(), host: session, password: Array.from({ length: 4 }, () => String.fromCharCode(65 + randomInt(26))).join(''), state: { url, playing: false, position: 0, at: now, version: 0 }, members: {}, messages: [], connections: new Map(), updated: now };
          rooms.set(target.id, target);
        } else if (msg.type === 'join') {
          if (target && typeof msg.session === 'string' && Object.hasOwn(target.members, msg.session)) {
            session = msg.session;
          } else {
            let attempts = failed.get(ip);
            if (attempts?.until <= now) attempts = undefined;
            if (attempts?.count >= 12) throw Error('Muitas tentativas. Aguarde um minuto.');
            if (!target || target.password !== msg.password) {
              failed.set(ip, { count: (attempts?.count ?? 0) + 1, until: attempts?.until ?? now + 60000 });
              throw Error('Convite ou senha inválida.');
            }
            if (Object.keys(target.members).length >= 500) throw Error('A sala atingiu o limite de sessões.');
            session = token();
          }
        } else throw Error('Entre em uma sala.');
        member = target.members[session] ?? { name, mode: msg.type === 'join' && msg.mode === 'chat' ? 'chat' : 'watch' };
        target.members[session] = member;
        target.updated = now;
        save();
        room = target;
        const old = room.connections.get(session);
        room.connections.set(session, ws);
        old?.close(4001);
        send(ws, { type: 'joined', room: room.id, session, name: member.name, mode: member.mode, password: room.password, host: session === room.host, history: room.messages, state: room.state });
        return presence(room);
      }
      if (room.connections.get(session) !== ws) return;
      if (msg.type === 'chat' && typeof msg.text === 'string' && msg.text.trim()) {
        const item = { name: member.name, text: msg.text.trim().slice(0, 500) };
        room.messages.push(item);
        if (room.messages.length > 100) room.messages.shift();
        room.updated = now;
        save();
        return broadcast(room, { type: 'message', item });
      }
      if (session !== room.host || msg.type !== 'playback') return;
      if (msg.version !== room.state.version) return send(ws, { type: 'state', state: room.state });
      if (!Number.isFinite(msg.position) || msg.position < 0 || msg.position > 86400 || typeof msg.playing !== 'boolean') return;
      room.state = { ...room.state, position: msg.position, playing: msg.playing, at: now, version: msg.version + 1 };
      room.updated = now;
      save();
      broadcast(room, { type: 'state', state: room.state });
    } catch (error) {
      send(ws, { type: 'error', message: error instanceof SyntaxError || error instanceof TypeError ? 'Comando inválido.' : error.code ? 'Falha ao salvar a sala.' : error.message });
    }
  });
});
setInterval(() => {
  for (const ws of sockets.clients) {
    if (!ws.alive) ws.terminate();
    else { ws.alive = false; ws.ping(); }
  }
  const now = Date.now();
  for (const [ip, attempts] of failed) if (attempts.until <= now) failed.delete(ip);
  let expired = false;
  for (const [id, room] of rooms) {
    if (room.connections.size === 0 && now - room.updated > 86400000) {
      rooms.delete(id);
      expired = true;
    }
  }
  if (expired) {
    try { save(); } catch (error) { console.error('Falha ao persistir expiração das salas:', error.code); }
  }
}, 15000).unref();
server.listen(process.env.PORT || 3000, '0.0.0.0');
