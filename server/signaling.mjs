import { WebSocketServer, WebSocket } from 'ws';
import { randomUUID } from 'node:crypto';

// Text signaling only: SDP travels here; movie, chat and voice use WebRTC.
export function attachSignaling(server) {
  const rooms = new Map(), attempts = new Map();
  const wss = new WebSocketServer({ noServer: true, maxPayload: 96 * 1024, perMessageDeflate: false });
  const send = (ws, msg) => {
    if (ws?.readyState === WebSocket.OPEN && ws.bufferedAmount < 256 * 1024) ws.send(JSON.stringify(msg));
  };
  const reject = (ws, message) => { send(ws, { type: 'error', message }); ws.close(1008, message); };
  const count = room => {
    const msg = { type: 'participants', count: room.viewers.size + 1, limit: 3 };
    send(room.host, msg); for (const ws of room.viewers.values()) send(ws, msg);
  };
  server.on('upgrade', (req, socket, head) => {
    if (req.url !== '/signal') return;
    if (wss.clients.size >= 100) { socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, req));
  });
  wss.on('connection', (ws, req) => {
    let room, id, role, joined = false, lastSignal = 0, messages = 0;
    const ip = req.socket.remoteAddress;
    const deadline = setTimeout(() => reject(ws, 'Digite a palavra da sala.'), 10000); deadline.unref();
    ws.alive = true; ws.on('pong', () => { ws.alive = true; }); ws.on('error', () => {});
    ws.on('message', (bytes, binary) => {
      if (ws.readyState !== WebSocket.OPEN) return;
      if (binary) { reject(ws, 'Este canal aceita somente sinalização.'); return; }
      let msg; try { msg = JSON.parse(bytes.toString()); } catch { reject(ws, 'Entrada inválida.'); return; }
      if (!msg || typeof msg !== 'object' || Array.isArray(msg)) { reject(ws, 'Entrada inválida.'); return; }
      if (!joined) {
        const now = Date.now();
        let entry = attempts.get(ip);
        if (!entry || now >= entry.until) { entry = { count: 0, until: now + 60000 }; attempts.set(ip, entry); }
        if (++entry.count > 20) { reject(ws, 'Muitas tentativas. Aguarde um minuto.'); return; }
        if (!['host', 'viewer'].includes(msg.role) || typeof msg.room !== 'string' || !/^[a-f0-9]{64}$/.test(msg.room)) { reject(ws, 'Palavra inválida.'); return; }
        role = msg.role;
        room = rooms.get(msg.room);
        if (role === 'host') {
          if (room) { reject(ws, 'Palavra em uso. Escolha outra.'); return; }
          if (rooms.size >= 50) { reject(ws, 'Servidor ocupado. Tente novamente.'); return; }
          room = { key: msg.room, host: ws, viewers: new Map() };
          room.timer = setTimeout(() => { for (const peer of [ws, ...room.viewers.values()]) peer.close(1000, 'Sala expirada.'); }, 2 * 60 * 60 * 1000); room.timer.unref();
          rooms.set(msg.room, room);
        } else {
          if (!room) { reject(ws, 'Palavra não encontrada. Confira com quem transmite.'); return; }
          if (room.viewers.size >= 2) { reject(ws, 'Sala cheia: três pessoas já estão conectadas ou entrando.'); return; }
          id = randomUUID(); room.viewers.set(id, ws);
          // A stalled handshake must not reserve a place indefinitely.
          ws.pending = setTimeout(() => ws.close(1000, 'A conexão demorou demais. Entre novamente.'), 90000); ws.pending.unref();
        }
        clearTimeout(deadline); joined = true;
        send(ws, { type: 'joined', role, id });
        if (role === 'viewer') send(room.host, { type: 'peer-joined', id });
        count(room); return;
      }
      if (Date.now() - lastSignal >= 60000) { lastSignal = Date.now(); messages = 0; }
      if (++messages > 30) { reject(ws, 'Excesso de sinalização.'); return; }
      if (msg.type === 'connected' && role === 'viewer') { clearTimeout(ws.pending); return; }
      if (msg.type === 'drop-peer' && role === 'host') { room.viewers.get(msg.id)?.close(1000, 'A conexão direta falhou. Entre novamente.'); return; }
      if (msg.type !== 'signal' || typeof msg.code !== 'string' || !msg.code.startsWith('CJ19:') || msg.code.length > 65536) return;
      if (role === 'host') send(room.viewers.get(msg.id), { type: 'signal', code: msg.code, id: msg.id });
      else send(room.host, { type: 'signal', id, code: msg.code });
    });
    ws.on('close', () => {
      clearTimeout(deadline); clearTimeout(ws.pending);
      if (!joined) return;
      if (role === 'host') {
        if (rooms.get(room.key) !== room) return;
        rooms.delete(room.key); clearTimeout(room.timer);
        for (const peer of room.viewers.values()) peer.close(1000, 'Quem transmitia encerrou a sala.');
      } else if (room.viewers.get(id) === ws) {
        room.viewers.delete(id); send(room.host, { type: 'peer-left', id }); count(room);
      }
    });
  });
  const heartbeat = setInterval(() => {
    for (const [ip, entry] of attempts) if (Date.now() >= entry.until) attempts.delete(ip);
    for (const ws of wss.clients) { if (!ws.alive) ws.terminate(); else { ws.alive = false; ws.ping(); } }
  }, 30000); heartbeat.unref();
  return { rooms, async close() {
    clearInterval(heartbeat); for (const room of rooms.values()) clearTimeout(room.timer);
    for (const ws of wss.clients) ws.terminate();
    await new Promise(resolve => wss.close(resolve));
  } };
}
