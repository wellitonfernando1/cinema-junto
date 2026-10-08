import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { createRelay } from './server.mjs';

const inboxes = new WeakMap();
function inbox(ws) {
  const messages = [], waiters = [];
  ws.on('message', (data, binary) => {
    const entry = { data, binary, json: binary ? null : JSON.parse(data.toString()) };
    const index = waiters.findIndex(waiter => waiter.match(entry));
    if (index < 0) messages.push(entry);
    else { const waiter = waiters.splice(index, 1)[0]; clearTimeout(waiter.timer); waiter.resolve(entry); }
  });
  const state = {
    messages,
    next(match = () => true) {
      const index = messages.findIndex(match);
      if (index >= 0) return Promise.resolve(messages.splice(index, 1)[0]);
      return new Promise((resolve, reject) => {
        const waiter = { match, resolve, timer: null };
        waiter.timer = setTimeout(() => {
          const i = waiters.indexOf(waiter); if (i >= 0) waiters.splice(i, 1);
          reject(new Error('Mensagem esperada não chegou'));
        }, 2000);
        waiters.push(waiter);
      });
    }
  };
  inboxes.set(ws, state); return state;
}
const nextBinary = ws => inboxes.get(ws).next(entry => entry.binary);
const nextJson = (ws, type) => inboxes.get(ws).next(entry => entry.json?.type === type);
async function fixture(t, options) {
  const relay = createRelay(options); relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  t.after(() => relay.close());
  return { url: `ws://127.0.0.1:${relay.server.address().port}/relay`, relay };
}
async function join(url, role, room) {
  const ws = new WebSocket(url); const incoming = inbox(ws); await once(ws, 'open');
  ws.send(JSON.stringify({ role, room }));
  await incoming.next(entry => typeof entry.json?.status === 'string'); return ws;
}
function packet(type, timestamp = 1000n, flags = 0, payload = Buffer.from([42, 43])) {
  const data = Buffer.alloc(13 + payload.length); data[0] = type;
  data.writeBigInt64BE(timestamp, 1); data.writeUInt32BE(flags, 9); payload.copy(data, 13); return data;
}
async function sendAndReceive(host, viewer, data) {
  host.send(data); const got = await nextBinary(viewer); assert.deepEqual(got.data, data);
}
async function barrier(host, viewer) {
  host.send(JSON.stringify({ type: 'chat', text: 'Verificação' })); await nextJson(viewer, 'chat'); await nextJson(host, 'chat');
}
async function expectTalk(ws, active, from) {
  assert.deepEqual((await nextJson(ws, 'talk')).json, { type: 'talk', active, from });
}

test('transmite imagem e áudio antigos apenas para o convidado da mesma sala', async t => {
  const { url } = await fixture(t);
  const host = await join(url, 'host', 'a'.repeat(64));
  const viewer = await join(url, 'viewer', 'a'.repeat(64));
  const otherHost = await join(url, 'host', 'b'.repeat(64));
  const otherViewer = await join(url, 'viewer', 'b'.repeat(64));
  for (const type of [1, 2]) await sendAndReceive(host, viewer, Buffer.from([type, 42, 43]));
  const closed = once(viewer, 'close'); host.close(); await closed;
  assert.equal(inboxes.get(otherViewer).messages.filter(entry => entry.binary).length, 0); otherHost.close();
});

test('rejeita convidado sem anfitrião, convite inválido, JSON nulo e terceiro participante', async t => {
  const { url } = await fixture(t);
  async function rejected(message) {
    const ws = new WebSocket(url); await once(ws, 'open'); const closed = once(ws, 'close');
    ws.send(JSON.stringify(message)); const [code] = await closed; assert.equal(code, 1008);
  }
  await rejected({ role: 'viewer', room: 'c'.repeat(64) }); await rejected({ role: 'host', room: '1234' }); await rejected(null);
  const host = await join(url, 'host', 'd'.repeat(64));
  await join(url, 'viewer', 'd'.repeat(64));
  await rejected({ role: 'viewer', room: 'd'.repeat(64) }); await rejected({ role: 'host', room: 'd'.repeat(64) }); host.close();
});

test('mensagens e emojis aparecem para os dois participantes sem vazar para outra sala', async t => {
  const { url } = await fixture(t);
  const host = await join(url, 'host', 'e'.repeat(64));
  const viewer = await join(url, 'viewer', 'e'.repeat(64));
  const otherHost = await join(url, 'host', 'f'.repeat(64));
  const otherViewer = await join(url, 'viewer', 'f'.repeat(64));
  for (const [sender, from, text] of [[viewer, 'viewer', 'Oi 😀'], [host, 'host', 'Olá ❤️']]) {
    sender.send(JSON.stringify({ type: 'chat', text }));
    for (const participant of [host, viewer]) assert.deepEqual((await nextJson(participant, 'chat')).json, { type: 'chat', from, text });
  }
  assert.equal(inboxes.get(otherViewer).messages.length, 0); otherHost.close(); host.close();
});

test('configuração AVC, quadros e PCM mantêm seus timestamps e o primeiro quadro é independente', async t => {
  const { url } = await fixture(t);
  const host = await join(url, 'host', '1'.repeat(64));
  const viewer = await join(url, 'viewer', '1'.repeat(64));
  assert.deepEqual((await nextJson(host, 'request-keyframe')).json, { type: 'request-keyframe' });
  const config = packet(5, 0n, 2, Buffer.from([0, 0, 0, 1, 103, 4]));
  await sendAndReceive(host, viewer, config);
  host.send(packet(6, 900n, 0)); host.send(packet(4, 900n));
  await sendAndReceive(host, viewer, packet(6, 1000n, 1));
  await sendAndReceive(host, viewer, packet(6, 2000n));
  await sendAndReceive(host, viewer, packet(4, 2000n));
  host.send(packet(5, 3000n, 2, config.subarray(13)));
  await sendAndReceive(host, viewer, packet(6, 3000n, 1));
  assert.equal(inboxes.get(viewer).messages.filter(entry => entry.binary).length, 0, 'Não entrega quadros dependentes nem repete configuração idêntica');
});

test('pedido de quadro independente é limitado a um por segundo e isolado por sala', async t => {
  const { url, relay } = await fixture(t);
  const roomA = '2'.repeat(64), roomB = '3'.repeat(64);
  const host = await join(url, 'host', roomA); const viewer = await join(url, 'viewer', roomA);
  const otherHost = await join(url, 'host', roomB); const otherViewer = await join(url, 'viewer', roomB);
  await nextJson(host, 'request-keyframe'); await nextJson(otherHost, 'request-keyframe');
  relay.rooms.get(roomA).lastKeyframeRequestAt = Date.now() - 1001;
  viewer.send(JSON.stringify({ type: 'request-keyframe' })); viewer.send(JSON.stringify({ type: 'request-keyframe' }));
  await nextJson(host, 'request-keyframe');
  host.send(JSON.stringify({ type: 'request-keyframe' }));
  await barrier(host, viewer);
  assert.equal(inboxes.get(host).messages.filter(entry => entry.json?.type === 'request-keyframe').length, 0);
  assert.equal(inboxes.get(otherHost).messages.filter(entry => entry.json?.type === 'request-keyframe').length, 0);
  assert.equal(inboxes.get(viewer).messages.filter(entry => entry.json?.type === 'request-keyframe').length, 0);
  assert.equal(inboxes.get(otherViewer).messages.length, 0);
});

test('um convidado que chega depois recebe a configuração guardada antes do quadro independente', async t => {
  const { url, relay } = await fixture(t); const key = '7'.repeat(64);
  const host = await join(url, 'host', key); const config = packet(5, 0n, 2);
  const configured = once(relay.rooms.get(key).host, 'message'); host.send(config); await configured;
  const viewer = await join(url, 'viewer', key); await nextJson(host, 'request-keyframe');
  host.send(packet(6, 1000n)); host.send(packet(6, 2000n, 1));
  assert.deepEqual((await nextBinary(viewer)).data, config);
  assert.deepEqual((await nextBinary(viewer)).data, packet(6, 2000n, 1));
  assert.equal(inboxes.get(viewer).messages.filter(entry => entry.binary).length, 0);
});

test('congestionamento limpa a reprodução e só retoma com configuração e quadro independente', async t => {
  const { url, relay } = await fixture(t, { mediaBufferLimitBytes: 1024 });
  const key = '4'.repeat(64); const host = await join(url, 'host', key); const viewer = await join(url, 'viewer', key);
  await nextJson(host, 'request-keyframe');
  const config = packet(5, 0n, 2); await sendAndReceive(host, viewer, config);
  await sendAndReceive(host, viewer, packet(6, 1000n, 1));
  const room = relay.rooms.get(key); let simulatedBacklog = 2048;
  Object.defineProperty(room.viewer, 'bufferedAmount', { configurable: true, get: () => simulatedBacklog });
  room.lastKeyframeRequestAt = Date.now() - 1001;
  host.send(packet(6, 2000n));
  assert.deepEqual((await nextJson(viewer, 'media-reset')).json, { type: 'media-reset' });
  await nextJson(host, 'request-keyframe');
  simulatedBacklog = 0;
  host.send(packet(6, 3000n)); host.send(packet(4, 3000n)); host.send(packet(6, 4000n, 1));
  assert.deepEqual((await nextBinary(viewer)).data, config);
  assert.deepEqual((await nextBinary(viewer)).data, packet(6, 4000n, 1));
  host.send(packet(4, 3000n));
  await sendAndReceive(host, viewer, packet(4, 5000n));
  await sendAndReceive(host, viewer, packet(6, 6000n));
  assert.equal(inboxes.get(viewer).messages.filter(entry => entry.binary).length, 0, 'Áudio anterior ao quadro de retomada e quadros dependentes são descartados');
});

test('recuperação adia o aviso enquanto a fila está totalmente bloqueada e protege o áudio', async t => {
  const { url, relay } = await fixture(t, { mediaBufferLimitBytes: 1024 });
  const key = '5'.repeat(64); const host = await join(url, 'host', key); const viewer = await join(url, 'viewer', key);
  await nextJson(host, 'request-keyframe'); const config = packet(5, 0n, 2);
  await sendAndReceive(host, viewer, config); await sendAndReceive(host, viewer, packet(6, 1000n, 1));
  const room = relay.rooms.get(key); let simulatedBacklog = 1024 * 1024;
  Object.defineProperty(room.viewer, 'bufferedAmount', { configurable: true, get: () => simulatedBacklog });
  room.lastKeyframeRequestAt = Date.now() - 1001;
  host.send(packet(4, 2000n)); await nextJson(host, 'request-keyframe');
  assert.equal(room.resetPending, true);
  simulatedBacklog = 0; host.send(packet(6, 3000n, 1));
  await nextJson(viewer, 'media-reset'); assert.deepEqual((await nextBinary(viewer)).data, config);
  assert.deepEqual((await nextBinary(viewer)).data, packet(6, 3000n, 1));
});

test('ignora mídia malformada e proíbe enviar mídia a partir do convidado', async t => {
  const { url } = await fixture(t); const key = '6'.repeat(64);
  const host = await join(url, 'host', key); const viewer = await join(url, 'viewer', key);
  await nextJson(host, 'request-keyframe');
  const config = packet(5, 0n, 2); await sendAndReceive(host, viewer, config);
  await sendAndReceive(host, viewer, packet(6, 1000n, 1));
  for (const data of [Buffer.from([6, 1]), packet(6, -1n), packet(6, 2000n, 8), packet(5, 0n, 2, Buffer.alloc(65537)), packet(4, 2000n, 0, Buffer.from([1])), packet(3)]) host.send(data);
  viewer.send(packet(6, 2000n, 1)); viewer.send('null'); host.send('null');
  await sendAndReceive(host, viewer, packet(6, 3000n));
  await barrier(host, viewer);
  assert.equal(inboxes.get(viewer).messages.filter(entry => entry.binary).length, 0);
  assert.equal(inboxes.get(host).messages.filter(entry => entry.binary).length, 0);
});

test('os dois participantes podem falar por vez e recebem os controles para silenciar o filme', async t => {
  const { url } = await fixture(t); const key = '8'.repeat(64);
  const host = await join(url, 'host', key); const viewer = await join(url, 'viewer', key);
  await nextJson(host, 'request-keyframe');
  await sendAndReceive(host, viewer, packet(5, 0n, 2)); await sendAndReceive(host, viewer, packet(6, 1000n, 1));
  host.send(JSON.stringify({ type: 'talk', active: true }));
  await expectTalk(host, true, 'host'); await expectTalk(viewer, true, 'host');
  await sendAndReceive(host, viewer, packet(7, 2000n));
  await sendAndReceive(host, viewer, packet(4, 2000n));
  host.send(JSON.stringify({ type: 'talk', active: false }));
  await expectTalk(host, false, 'host'); await expectTalk(viewer, false, 'host');
  host.send(packet(7, 3000n));
  viewer.send(JSON.stringify({ type: 'talk', active: true }));
  await expectTalk(host, true, 'viewer'); await expectTalk(viewer, true, 'viewer');
  await sendAndReceive(viewer, host, packet(7, 4000n));
  viewer.send(JSON.stringify({ type: 'talk', active: false }));
  await expectTalk(host, false, 'viewer'); await expectTalk(viewer, false, 'viewer');
  await barrier(host, viewer);
  assert.equal(inboxes.get(host).messages.filter(entry => entry.binary).length, 0, 'Sem eco do próprio microfone');
  assert.equal(inboxes.get(viewer).messages.filter(entry => entry.binary).length, 0, 'Sem eco e sem gravação após soltar');
});

test('microfone exige autorização da sala, bloqueia colisões e rejeita PCM inválido sem vazar', async t => {
  const { url, relay } = await fixture(t); const key = '9'.repeat(64), otherKey = '0'.repeat(64);
  const host = await join(url, 'host', key); const viewer = await join(url, 'viewer', key);
  const otherHost = await join(url, 'host', otherKey); const otherViewer = await join(url, 'viewer', otherKey);
  await nextJson(host, 'request-keyframe'); await nextJson(otherHost, 'request-keyframe');
  host.send(packet(7)); viewer.send(packet(7));
  host.send(JSON.stringify({ type: 'talk', active: 'true' }));
  host.send(JSON.stringify({ type: 'talk', active: true }));
  await expectTalk(host, true, 'host'); await expectTalk(viewer, true, 'host');
  viewer.send(JSON.stringify({ type: 'talk', active: true }));
  await expectTalk(viewer, true, 'host');
  viewer.send(packet(7));
  const cannotStop = once(relay.rooms.get(key).viewer, 'message');
  viewer.send(JSON.stringify({ type: 'talk', active: false })); await cannotStop;
  assert.equal(relay.rooms.get(key).talker, 'host');
  for (const data of [packet(7, -1n), packet(7, 2000n, 1), packet(7, 2000n, 0, Buffer.alloc(6402)), packet(7, 2000n, 0, Buffer.from([1])), Buffer.from([7, 1])]) host.send(data);
  await sendAndReceive(host, viewer, packet(7, 3000n));
  await barrier(host, viewer);
  assert.equal(inboxes.get(host).messages.filter(entry => entry.binary || entry.json?.type === 'talk').length, 0);
  assert.equal(inboxes.get(viewer).messages.filter(entry => entry.binary || entry.json?.type === 'talk').length, 0);
  assert.equal(inboxes.get(otherViewer).messages.length, 0);
  assert.equal(inboxes.get(otherHost).messages.filter(entry => entry.binary || entry.json?.type === 'talk').length, 0);
});

test('microfone para sozinho após o limite de tempo e não aceita áudio posterior', async t => {
  const { url, relay } = await fixture(t, { talkTtlMs: 40 }); const key = 'a'.repeat(64);
  const host = await join(url, 'host', key); const viewer = await join(url, 'viewer', key);
  await nextJson(host, 'request-keyframe');
  viewer.send(JSON.stringify({ type: 'talk', active: true }));
  await expectTalk(host, true, 'viewer'); await expectTalk(viewer, true, 'viewer');
  await expectTalk(host, false, 'viewer'); await expectTalk(viewer, false, 'viewer');
  assert.equal(relay.rooms.get(key).talker, null);
  const received = once(relay.rooms.get(key).viewer, 'message'); viewer.send(packet(7)); await received;
  await barrier(host, viewer);
  assert.equal(inboxes.get(host).messages.filter(entry => entry.binary).length, 0);
});

test('sair da sala interrompe o microfone mesmo quando sai quem estava ouvindo', async t => {
  const { url, relay } = await fixture(t); const key = 'b'.repeat(64);
  const host = await join(url, 'host', key); const viewer = await join(url, 'viewer', key);
  await nextJson(host, 'request-keyframe');
  host.send(JSON.stringify({ type: 'talk', active: true }));
  await expectTalk(host, true, 'host'); await expectTalk(viewer, true, 'host');
  const closed = once(viewer, 'close'); viewer.close(); await closed;
  await expectTalk(host, false, 'host'); assert.equal(relay.rooms.get(key).talker, null);
  const replacement = await join(url, 'viewer', key); await nextJson(host, 'request-keyframe');
  replacement.send(JSON.stringify({ type: 'talk', active: true }));
  await expectTalk(host, true, 'viewer'); await expectTalk(replacement, true, 'viewer');
  const hostClosed = once(host, 'close'); host.close(); await hostClosed;
  await expectTalk(replacement, false, 'viewer');
});

test('fila de voz congestionada descarta áudio sem reiniciar a transmissão do filme', async t => {
  const { url, relay } = await fixture(t, { mediaBufferLimitBytes: 1024 }); const key = 'c'.repeat(64);
  const host = await join(url, 'host', key); const viewer = await join(url, 'viewer', key);
  await nextJson(host, 'request-keyframe');
  await sendAndReceive(host, viewer, packet(5, 0n, 2)); await sendAndReceive(host, viewer, packet(6, 1000n, 1));
  host.send(JSON.stringify({ type: 'talk', active: true }));
  await expectTalk(host, true, 'host'); await expectTalk(viewer, true, 'host');
  const room = relay.rooms.get(key); let simulatedBacklog = 2048;
  Object.defineProperty(room.viewer, 'bufferedAmount', { configurable: true, get: () => simulatedBacklog });
  const dropped = once(room.host, 'message'); host.send(packet(7, 2000n)); await dropped;
  assert.equal(room.waitingKeyframe, false); simulatedBacklog = 0;
  await sendAndReceive(host, viewer, packet(7, 3000n));
  await sendAndReceive(host, viewer, packet(6, 3000n));
  assert.equal(inboxes.get(viewer).messages.filter(entry => entry.binary || entry.json?.type === 'media-reset').length, 0);
});
