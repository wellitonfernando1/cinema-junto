import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { pathToFileURL } from 'node:url';

export function createRelay({ ttlMs = 2 * 60 * 60 * 1000 } = {}) {
  const rooms = new Map();
  const server = http.createServer((req, res) => {
    if (req.url === '/health') { res.writeHead(200); res.end('ok'); return; }
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Cinema Junto: servidor ativo. Use o aplicativo Android.');
  });
  const wss = new WebSocketServer({ noServer: true, maxPayload: 512 * 1024, perMessageDeflate: false });
  server.on('upgrade', (req, socket, head) => {
    if (req.url !== '/relay' || wss.clients.size >= 100) { socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws));
  });
  wss.on('connection', ws => {
    let joined = false, role, key, lastChatAt = 0;
    ws.alive = true;
    ws.on('pong', () => { ws.alive = true; });
    const deadline = setTimeout(() => ws.close(1008, 'Identifique a sala'), 10000);
    deadline.unref();
    const send = (target, data, binary = false) => {
      if (target?.readyState === WebSocket.OPEN && target.bufferedAmount < 512 * 1024) target.send(data, { binary });
    };
    ws.on('message', (data, binary) => {
      if (!joined) {
        if (binary || data.length > 2048) { ws.close(1008, 'Entrada inválida'); return; }
        let msg;
        try { msg = JSON.parse(data.toString()); } catch { ws.close(1008, 'Entrada inválida'); return; }
        if (!/^[a-f0-9]{64}$/.test(msg.room) || !['host','viewer'].includes(msg.role)) { ws.close(1008, 'Convite inválido'); return; }
        role = msg.role; key = msg.room;
        let room = rooms.get(key);
        if (role === 'host' && !room) {
          if (rooms.size >= 50) { ws.close(1013, 'Servidor ocupado'); return; }
          room = { host: null, viewer: null, timer: null };
          room.timer = setTimeout(() => {
            room.host?.close(1000, 'Sala expirada'); room.viewer?.close(1000, 'Sala expirada'); rooms.delete(key);
          }, ttlMs);
          room.timer.unref(); rooms.set(key, room);
        }
        if (!room || room[role]) { ws.close(1008, room ? 'Sala ocupada' : 'Sala não está transmitindo'); return; }
        joined = true; clearTimeout(deadline); room[role] = ws;
        send(ws, JSON.stringify({ status: role === 'host' ? 'Pronto. Envie o convite ao seu amigo.' : 'Conectado. Aguardando imagem e som.' }));
        if (room.viewer) send(room.host, JSON.stringify({ status: 'Seu amigo entrou na sala.' }));
        return;
      }
      const room = rooms.get(key);
      if (role === 'host' && binary && data.length >= 2 && [1,2].includes(data[0])) send(room?.viewer, data, true);
      if (!binary && data.length <= 2048 && room?.[role] === ws) {
        let msg;
        try { msg = JSON.parse(data.toString()); } catch { return; }
        if (msg.type !== 'chat' || typeof msg.text !== 'string') return;
        const text = msg.text.trim();
        if (!text || Array.from(text).length > 280 || Date.now() - lastChatAt < 250) return;
        lastChatAt = Date.now();
        const peer = role === 'host' ? room.viewer : room.host;
        if (!peer || peer.readyState !== WebSocket.OPEN) {
          send(ws, JSON.stringify({ status: 'Seu amigo ainda não está conectado.' }));
          return;
        }
        const packet = JSON.stringify({ type: 'chat', from: role, text });
        send(peer, packet);
        send(ws, packet);
      }
    });
    ws.on('error', () => {});
    ws.on('close', () => {
      clearTimeout(deadline);
      if (!joined) return;
      const room = rooms.get(key);
      if (!room || room[role] !== ws) return;
      room[role] = null;
      if (role === 'host') {
        clearTimeout(room.timer); rooms.delete(key); room.viewer?.close(1000, 'Transmissão encerrada');
      } else send(room.host, JSON.stringify({ status: 'Seu amigo saiu. Pode enviar o convite novamente.' }));
    });
  });
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) { if (!ws.alive) ws.terminate(); else { ws.alive = false; ws.ping(); } }
  }, 30000);
  heartbeat.unref();
  return {
    server, rooms,
    async close() {
      clearInterval(heartbeat);
      for (const room of rooms.values()) clearTimeout(room.timer);
      for (const ws of wss.clients) ws.terminate();
      await new Promise(resolve => wss.close(resolve));
      await new Promise(resolve => server.close(resolve));
    }
  };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const relay = createRelay();
  relay.server.listen(Number(process.env.PORT || 10000), '0.0.0.0', () => console.log('Cinema Junto ativo'));
}
