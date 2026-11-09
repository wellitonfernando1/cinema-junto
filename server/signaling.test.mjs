import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { createRelay } from './server.mjs';
const key = 'a'.repeat(64);
function inbox(ws) {
  const messages = [], waiters = [];
  ws.on('message', bytes => {
    const msg = JSON.parse(bytes.toString()); const i = waiters.findIndex(w => w.type === msg.type);
    if (i >= 0) { const w = waiters.splice(i, 1)[0]; clearTimeout(w.timer); w.resolve(msg); }
    else messages.push(msg);
  });
  return type => {
    const i = messages.findIndex(msg => msg.type === type);
    if (i >= 0) return Promise.resolve(messages.splice(i, 1)[0]);
    return new Promise((resolve, reject) => { const waiter = { type, resolve }; waiter.timer = setTimeout(() => reject(new Error('Missing ' + type)), 2000); waiters.push(waiter); });
  };
}
async function setup(t) {
  const relay = createRelay(); relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening'); t.after(() => relay.close());
  return async (role, room = key, options = {}) => {
    const ws = new WebSocket(`ws://127.0.0.1:${relay.server.address().port}/signal`); const next = inbox(ws); await once(ws, 'open');
    ws.send(JSON.stringify({ role, room, ...options })); return { ws, next };
  };
}
test('convidados entram pela mesma palavra e a sinalização é roteada para o aparelho certo', async t => {
  const connect = await setup(t); const h = await connect('host'); await h.next('joined');
  const a = await connect('viewer'); await a.next('joined'); const aid = (await h.next('peer-joined')).id;
  const b = await connect('viewer'); await b.next('joined'); const bid = (await h.next('peer-joined')).id;
  const extra = await connect('viewer', key, { chatOnly: true, name: 'Welliton' });
  assert.equal((await extra.next('joined')).role, 'viewer');
  const extraJoin = await h.next('peer-joined');
  assert.equal(extraJoin.chatOnly, true); assert.equal(extraJoin.name, 'Welliton');
  const d = await connect('viewer'); await d.next('joined'); await h.next('peer-joined');
  const e = await connect('viewer'); await e.next('joined'); await h.next('peer-joined');
  h.ws.send(JSON.stringify({ type: 'signal', id: aid, code: 'CJ19:offerA' })); assert.equal((await a.next('signal')).code, 'CJ19:offerA');
  h.ws.send(JSON.stringify({ type: 'signal', id: bid, code: 'CJ19:offerB' })); assert.equal((await b.next('signal')).code, 'CJ19:offerB');
  a.ws.send(JSON.stringify({ type: 'signal', id: bid, code: 'CJ19:answerA' })); const answer = await h.next('signal'); assert.equal(answer.id, aid);
  // ICE must follow the same isolated route as SDP; a viewer cannot spoof another id.
  for (let i = 0; i < 40; i++) {
    a.ws.send(JSON.stringify({ type: 'candidate', id: bid, code: 'CJ19:ice' + i }));
    const ice = await h.next('candidate'); assert.equal(ice.id, aid); assert.equal(ice.code, 'CJ19:ice' + i);
  }
  h.ws.send(JSON.stringify({ type: 'candidate', id: bid, code: 'CJ19:hostIceB' }));
  assert.equal((await b.next('candidate')).code, 'CJ19:hostIceB');
  const closed = once(a.ws, 'close'); a.ws.close(); await closed; assert.equal((await h.next('peer-left')).id, aid);
  const c = await connect('viewer'); await c.next('joined');
  const closedWithHost = [b, c, d, e, extra].map(peer => once(peer.ws, 'close'));
  h.ws.close(); await Promise.all(closedWithHost);
});
test('senha errada, colisão, salas diferentes e mídia binária são recusados', async t => {
  const connect = await setup(t);
  const missing = await connect('viewer'); assert.match((await missing.next('error')).message, /não encontrada/);
  const h = await connect('host'); await h.next('joined');
  const collision = await connect('host'); assert.match((await collision.next('error')).message, /em uso/);
  const other = await connect('host', 'b'.repeat(64)); await other.next('joined');
  const viewer = await connect('viewer'); await viewer.next('joined'); await h.next('peer-joined');
  viewer.ws.send(Buffer.from([6, 1, 2])); assert.match((await viewer.next('error')).message, /somente sinalização/);
});

test('reentrada substitui a conexão anterior do mesmo aparelho e mantém os outros convidados', async t => {
  const connect = await setup(t);
  const h = await connect('host'); await h.next('joined');
  const device = '00000000-0000-4000-8000-000000000001';
  const old = await connect('viewer', key, { device }); await old.next('joined');
  const oldId = (await h.next('peer-joined')).id;
  const other = await connect('viewer'); await other.next('joined');
  const otherId = (await h.next('peer-joined')).id;
  const returning = await connect('viewer', key, { device }); await returning.next('joined');
  assert.equal((await h.next('peer-left')).id, oldId);
  assert.equal((await h.next('peer-joined')).device, device);
  assert.match((await old.next('error')).message, /entrou novamente/);
  h.ws.send(JSON.stringify({ type: 'signal', id: otherId, code: 'CJ19:stillHere' }));
  assert.equal((await other.next('signal')).code, 'CJ19:stillHere');
});
