import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { pathToFileURL } from 'node:url';
import { isIP } from 'node:net';

export function createRelay({ ttlMs = 2 * 60 * 60 * 1000, mediaBufferLimitBytes = 4 * 1024 * 1024, talkTtlMs = 30000,
  joinAttemptLimit = 12, joinWindowMs = 60000, joinClock = Date.now,
  trustedProxyHops = process.env.RENDER === 'true' ? 1 : 0 } = {}) {
  const rooms = new Map();
  const failedJoins = new Map();
  const validDevice = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
  const pruneJoinAttempts = now => {
    for (const [ip, entry] of failedJoins) if (now >= entry.until) failedJoins.delete(ip);
  };
  const joinBlocked = ip => {
    const now = joinClock(); pruneJoinAttempts(now);
    const entry = failedJoins.get(ip);
    return entry ? entry.count >= joinAttemptLimit : failedJoins.size >= 1024;
  };
  const countFailedJoin = ip => {
    const now = joinClock(); const entry = failedJoins.get(ip);
    if (entry && now < entry.until) entry.count++;
    else if (failedJoins.size < 1024) failedJoins.set(ip, { count: 1, until: now + joinWindowMs });
  };
  const clientIp = req => {
    const direct = req.socket.remoteAddress || 'unknown';
    const header = req.headers['x-forwarded-for'];
    if (trustedProxyHops > 0 && typeof header === 'string' && header.length <= 2048) {
      const chain = header.split(',').map(ip => ip.trim());
      const candidate = chain[chain.length - trustedProxyHops];
      // Only the configured nearest proxy hops are trusted, never a client-supplied first entry.
      if (candidate && isIP(candidate)) return candidate;
    }
    return direct;
  };
  const send = (target, data, binary = false) => {
    if (target?.readyState !== WebSocket.OPEN || target.bufferedAmount >= (binary ? mediaBufferLimitBytes + 512 * 1024 : 512 * 1024)) return false;
    target.send(data, { binary });
    return true;
  };
  const requestKeyframe = (room, force = false) => {
    const now = Date.now();
    if (!force && now - room.lastKeyframeRequestAt < 1000) return;
    if (send(room.host, JSON.stringify({ type: 'request-keyframe' }))) room.lastKeyframeRequestAt = now;
  };
  const participants = room => [room.host, ...room.viewers.keys()].filter(Boolean);
  const member = (room, ws) => room && (room.host === ws || room.viewers.has(ws));
  // Keep the two-role wire protocol compatible with installed clients. Each recipient
  // sees their own role for an echo and the opposite role for another participant.
  const fromFor = (room, source, recipient) => {
    const role = recipient === room.host ? 'host' : 'viewer';
    return source === recipient ? role : role === 'host' ? 'viewer' : 'host';
  };
  const resetMedia = (room, viewer, state) => {
    state.waitingKeyframe = true; state.needsConfig = true; state.resetPending = true;
    if (send(viewer, JSON.stringify({ type: 'media-reset' }))) state.resetPending = false;
    requestKeyframe(room);
  };
  const broadcastTalk = (room, active, source) => {
    for (const peer of participants(room))
      send(peer, JSON.stringify({ type: 'talk', active, from: fromFor(room, source, peer) }));
  };
  const endTalk = room => {
    if (!room.talker) return;
    const source = room.talker; room.talker = null;
    clearTimeout(room.talkTimer); room.talkTimer = null;
    broadcastTalk(room, false, source);
  };
  const forwardVoice = (room, source, data) => {
    if (room.talker !== source || data.length <= 13 || data.length > 6413 || (data.length - 13) % 2 !== 0 ||
        data.readBigInt64BE(1) < 0n || data.readUInt32BE(9) !== 0) return;
    const now = Date.now();
    room.voiceTokens = Math.min(64000, room.voiceTokens + Math.max(0, now - room.voiceUpdatedAt) * 32);
    room.voiceUpdatedAt = now;
    if (room.voiceTokens < data.length - 13) return;
    room.voiceTokens -= data.length - 13;
    for (const peer of participants(room))
      if (peer !== source && peer.bufferedAmount < Math.min(mediaBufferLimitBytes, 256 * 1024)) send(peer, data, true);
  };
  const forwardMedia = (room, data) => {
    const type = data[0];
    if ([1, 2].includes(type)) {
      for (const viewer of room.viewers.keys())
        if (data.length >= 2 && viewer.bufferedAmount < mediaBufferLimitBytes) send(viewer, data, true);
      return;
    }
    // New media packets have a kind, a monotonic timestamp in microseconds, and codec flags.
    if (![4, 5, 6].includes(type) || data.length <= 13 || data.readBigInt64BE(1) < 0n || data.readUInt32BE(9) > 7) return;
    const payloadSize = data.length - 13;
    if (type === 4 && (payloadSize > 32 * 1024 || payloadSize % 2 !== 0)) return;
    if (type === 5 && payloadSize > 64 * 1024) return;
    let changedConfig = false;
    if (type === 5) {
      changedConfig = !room.config?.subarray(13).equals(data.subarray(13));
      room.config = Buffer.from(data);
      if (changedConfig) for (const state of room.viewers.values()) { state.waitingKeyframe = true; state.needsConfig = true; }
    }
    for (const [viewer, state] of room.viewers) forwardViewer(room, viewer, state, data, type, changedConfig);
  };
  const forwardViewer = (room, viewer, state, data, type, changedConfig) => {
    const wasStreaming = !state.waitingKeyframe;
    if (viewer?.readyState !== WebSocket.OPEN) return;
    if (viewer.bufferedAmount >= mediaBufferLimitBytes) {
      // Once one AVC frame is lost, dependent frames cannot be decoded safely.
      // Audio is also discarded until a new keyframe starts the shared timeline.
      if (wasStreaming || !state.needsConfig) resetMedia(room, viewer, state);
      else requestKeyframe(room);
      return;
    }
    if (state.resetPending) {
      if (!send(viewer, JSON.stringify({ type: 'media-reset' }))) return;
      state.resetPending = false;
    }
    if (type === 5) {
      if ((changedConfig || state.needsConfig) && send(viewer, data, true)) state.needsConfig = false;
      return;
    }
    if (type === 4) {
      if (!state.waitingKeyframe && data.readBigInt64BE(1) >= state.resumeTimestamp) send(viewer, data, true);
      return;
    }
    if (state.waitingKeyframe) {
      if (!(data.readUInt32BE(9) & 1) || !room.config) { requestKeyframe(room); return; }
      if (state.needsConfig) {
        if (!send(viewer, room.config, true)) return;
        state.needsConfig = false;
      }
      if (send(viewer, data, true)) {
        state.waitingKeyframe = false;
        state.resumeTimestamp = data.readBigInt64BE(1);
      }
      return;
    }
    send(viewer, data, true);
  };
  const server = http.createServer((req, res) => {
    if (req.url === '/health') { res.writeHead(200); res.end('ok'); return; }
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Cinema Junto: servidor ativo. Use o aplicativo Android.');
  });
  const wss = new WebSocketServer({ noServer: true, maxPayload: 512 * 1024, perMessageDeflate: false });
  server.on('upgrade', (req, socket, head) => {
    if (req.url !== '/relay' || wss.clients.size >= 100) { socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, req));
  });
  wss.on('connection', (ws, req) => {
    let joined = false, rejected = false, role, key, lastChatAt = 0, lastTalkStartAt = 0;
    const ip = clientIp(req);
    const rejectJoin = (code, reason, count = true) => {
      rejected = true; clearTimeout(deadline);
      if (count) countFailedJoin(ip);
      if (ws.readyState === WebSocket.OPEN && ws.bufferedAmount < 512 * 1024) {
        ws.send(JSON.stringify({ type: 'error', message: reason }), { binary: false }, () => ws.close(code, reason));
      } else ws.close(code, reason);
    };
    ws.alive = true;
    ws.on('pong', () => { ws.alive = true; });
    const deadline = setTimeout(() => rejectJoin(1008, 'Identifique a sala', false), 10000);
    deadline.unref();
    ws.on('message', (data, binary) => {
      if (rejected || ws.readyState !== WebSocket.OPEN) return;
      if (!joined) {
        if (joinBlocked(ip)) { rejectJoin(1013, 'Muitas tentativas. Aguarde um minuto e tente novamente.', false); return; }
        if (binary || data.length > 2048) { rejectJoin(1008, 'Entrada inválida'); return; }
        let msg;
        try { msg = JSON.parse(data.toString()); } catch { rejectJoin(1008, 'Entrada inválida'); return; }
        if (!msg || typeof msg !== 'object' || Array.isArray(msg) || typeof msg.room !== 'string' || !/^[a-f0-9]{64}$/.test(msg.room) || !['host','viewer'].includes(msg.role)) { rejectJoin(1008, 'Senha inválida.'); return; }
        if (Object.hasOwn(msg, 'device') && !validDevice(msg.device)) { rejectJoin(1008, 'Identificação do aparelho inválida.'); return; }
        const device = msg.device?.toLowerCase();
        role = msg.role; key = msg.room;
        let room = rooms.get(key);
        if (role === 'host' && !room) {
          if (rooms.size >= 50) { rejectJoin(1013, 'Servidor ocupado'); return; }
          room = { host: null, viewers: new Map(), timer: null, config: null, lastKeyframeRequestAt: -Infinity,
            talker: null, talkTimer: null, voiceTokens: 64000, voiceUpdatedAt: 0,
            deviceMode: Boolean(device), hostDevice: device || null };
          room.timer = setTimeout(() => {
            endTalk(room);
            for (const peer of participants(room)) peer.close(1000, 'Sala expirada'); rooms.delete(key);
          }, ttlMs);
          room.timer.unref(); rooms.set(key, room);
        }
        if (!room) { rejectJoin(1008, 'Senha não encontrada. Confira a palavra com quem transmite.'); return; }
        if (role === 'host' && room.host) { rejectJoin(1008, 'Senha já está em uso. Escolha outra.'); return; }
        if (role === 'viewer' && room.deviceMode) {
          if (!device) { rejectJoin(1008, 'Atualize o aplicativo para entrar nesta sala.'); return; }
          if (device === room.hostDevice) { rejectJoin(1008, 'Use outro aparelho para assistir nesta sala.'); return; }
        }
        if (role === 'viewer') {
          const oldViewer = device && [...room.viewers.keys()].find(peer => peer.device === device);
          if (oldViewer) {
            if (room.talker === oldViewer) endTalk(room);
            room.viewers.delete(oldViewer);
            oldViewer.close(1000, 'Conexão reaberta no seu aparelho.');
          }
          if (room.viewers.size >= 4) { rejectJoin(1008, 'Sala cheia. O limite é de cinco aparelhos.', false); return; }
          ws.device = device;
          room.viewers.set(ws, { waitingKeyframe: true, needsConfig: true, resetPending: false, resumeTimestamp: 0n });
        } else room.host = ws;
        joined = true; clearTimeout(deadline);
        send(ws, JSON.stringify({ joined: true, status: role === 'host' ? 'Sala criada. Compartilhe a senha com seu amigo.' : 'Conectado. Aguardando imagem e som.' }));
        if (role === 'viewer') {
          send(room.host, JSON.stringify({ status: `Seu amigo entrou na sala. Participantes: ${room.viewers.size + 1}/5.` }));
          requestKeyframe(room, true);
          if (room.talker) send(ws, JSON.stringify({ type: 'talk', active: true, from: fromFor(room, room.talker, ws) }));
        }
        return;
      }
      const room = rooms.get(key);
      if (binary && data[0] === 7 && member(room, ws)) { forwardVoice(room, ws, data); return; }
      if (role === 'host' && binary && room?.host === ws && data.length >= 2) forwardMedia(room, data);
      if (!binary && data.length <= 2048 && member(room, ws)) {
        let msg;
        try { msg = JSON.parse(data.toString()); } catch { return; }
        if (!msg || typeof msg !== 'object' || Array.isArray(msg)) return;
        if (msg.type === 'talk') {
          if (typeof msg.active !== 'boolean') return;
          if (!msg.active) { if (room.talker === ws) endTalk(room); return; }
          if (room.talker) { send(ws, JSON.stringify({ type: 'talk', active: true, from: fromFor(room, room.talker, ws) })); return; }
          if (Date.now() - lastTalkStartAt < 250) { send(ws, JSON.stringify({ type: 'talk', active: false, from: role })); return; }
          lastTalkStartAt = Date.now(); room.talker = ws; room.voiceTokens = 64000; room.voiceUpdatedAt = lastTalkStartAt;
          room.talkTimer = setTimeout(() => endTalk(room), talkTtlMs); room.talkTimer.unref();
          broadcastTalk(room, true, ws); return;
        }
        if (msg.type === 'request-keyframe') {
          if (role === 'viewer') requestKeyframe(room);
          return;
        }
        if (msg.type !== 'chat' || typeof msg.text !== 'string') return;
        const text = msg.text.trim();
        if (!text || Array.from(text).length > 280 || Date.now() - lastChatAt < 250) return;
        lastChatAt = Date.now();
        for (const peer of participants(room))
          send(peer, JSON.stringify({ type: 'chat', from: fromFor(room, ws, peer), text }));
      }
    });
    ws.on('error', () => {});
    ws.on('close', () => {
      clearTimeout(deadline);
      if (!joined) return;
      const room = rooms.get(key);
      if (!member(room, ws)) return;
      if (role === 'host' || room.talker === ws || room.viewers.size === 1) endTalk(room);
      if (role === 'viewer') room.viewers.delete(ws);
      if (role === 'host') {
        clearTimeout(room.timer); rooms.delete(key);
        for (const viewer of room.viewers.keys()) viewer.close(1000, 'Transmissão encerrada');
      } else send(room.host, JSON.stringify({ status: `Participantes: ${room.viewers.size + 1}/5.` }));
    });
  });
  const heartbeat = setInterval(() => {
    pruneJoinAttempts(joinClock());
    for (const ws of wss.clients) { if (!ws.alive) ws.terminate(); else { ws.alive = false; ws.ping(); } }
  }, 30000);
  heartbeat.unref();
  return {
    server, rooms,
    async close() {
      clearInterval(heartbeat);
      for (const room of rooms.values()) { clearTimeout(room.timer); clearTimeout(room.talkTimer); }
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
