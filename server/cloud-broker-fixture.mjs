// Local protocol fixture for the public PeerServer signaling API. No media transport.
import { WebSocketServer, WebSocket } from 'ws';
const server = new WebSocketServer({ port: 10001, host: '0.0.0.0', maxPayload: 96 * 1024 });
const clients = new Map();
server.on('connection', (ws, req) => {
  const params = new URL(req.url, 'http://localhost').searchParams;
  const id = params.get('id');
  if (!id || params.get('key') !== 'peerjs' || !params.get('token')) { ws.close(); return; }
  if (clients.has(id)) { ws.send(JSON.stringify({ type: 'ID-TAKEN' })); ws.close(); return; }
  clients.set(id, ws); ws.send(JSON.stringify({ type: 'OPEN' }));
  ws.on('message', (bytes, binary) => {
    if (binary) { ws.close(); return; }
    const msg = JSON.parse(bytes.toString()); if (msg.type === 'HEARTBEAT') return;
    if (!['OFFER', 'ANSWER', 'LEAVE', 'CANDIDATE'].includes(msg.type)) return;
    // Match the hosted service: validate the data-offer envelope and preserve application metadata.
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
  ws.on('error', () => {}); ws.on('close', () => { if (clients.get(id) === ws) clients.delete(id); });
});
process.on('SIGTERM', () => { for (const ws of server.clients) ws.terminate(); server.close(() => process.exit(0)); });
