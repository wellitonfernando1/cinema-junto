// Test-only PeerServer fixture and fault control. Neither endpoint transports media.
import { WebSocketServer, WebSocket } from 'ws';
import { createServer } from 'node:http';
const server = new WebSocketServer({ port: 10001, host: '0.0.0.0', maxPayload: 96 * 1024 });
const clients = new Map(); let blockedUntil = 0;
const control = createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/disconnect') { res.writeHead(404).end(); return; }
  blockedUntil = Date.now() + 35000;
  for (const ws of clients.values()) ws.close(1012, 'Simulated broker outage');
  res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ blockedMs: 35000 }));
});
control.listen(10002, '0.0.0.0');
server.on('connection', (ws, req) => {
  if (Date.now() < blockedUntil) { ws.close(1013, 'Simulated broker outage'); return; }
  const params = new URL(req.url, 'http://localhost').searchParams;
  const id = params.get('id');
  if (!id || params.get('key') !== 'peerjs' || !params.get('token')) { ws.close(); return; }
  if (clients.has(id)) { ws.send(JSON.stringify({ type: 'ID-TAKEN' })); ws.close(); return; }
  clients.set(id, ws); ws.send(JSON.stringify({ type: 'OPEN' }));
  ws.on('message', (bytes, binary) => {
    if (binary) { ws.close(); return; }
    let msg; try { msg = JSON.parse(bytes.toString()); } catch { ws.close(); return; }
    if (msg.type === 'HEARTBEAT') return;
    if (!['OFFER', 'ANSWER', 'LEAVE', 'CANDIDATE'].includes(msg.type)) return;
    if (['OFFER', 'ANSWER'].includes(msg.type)) {
      const p = msg.payload;
      if (!p || p.type !== 'data' || typeof p.connectionId !== 'string' || typeof p.label !== 'string' ||
          typeof p.serialization !== 'string' || typeof p.reliable !== 'boolean' || !p.sdp) return;
      msg.payload = { type: p.type, connectionId: p.connectionId, label: p.label, serialization: p.serialization,
        reliable: p.reliable, sdp: p.sdp, metadata: p.metadata };
    }
    const target = clients.get(msg.dst);
    if (target?.readyState === WebSocket.OPEN) target.send(JSON.stringify({ ...msg, src: id }));
    else ws.send(JSON.stringify({ type: 'EXPIRE', src: msg.dst }));
  });
  ws.on('error', () => {});
  ws.on('close', () => { if (clients.get(id) === ws) clients.delete(id); });
});
process.on('SIGTERM', () => { for (const ws of server.clients) ws.terminate(); control.close(); server.close(() => process.exit(0)); });
