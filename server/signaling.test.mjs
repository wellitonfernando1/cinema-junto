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
  return async (role, room = key) => {
    const ws = new WebSocket(`ws://127.0.0.1:${relay.server.address().port}/signal`); const next = inbox(ws); await once(ws, 'open');
    ws.send(JSON.stringify({ role, room })); return { ws, next };
  };
}
test('três pessoas entram pela mesma sala, cada resposta volta somente ao anfitrião e saída libera vaga', async t => {
  const connect = await setup(t); const h = await connect('host'); await h.next('joined');
  const a = await connect('viewer'); await a.next('joined'); const aid = (await h.next('peer-joined')).id;
  const b = await connect('viewer'); await b.next('joined'); const bid = (await h.next('peer-joined')).id;
  const full = await connect('viewer'); assert.match((await full.next('error')).message, /Sala cheia/);
  h.ws.send(JSON.stringify({ type: 'signal', id: aid, code: 'CJ19:offerA' })); assert.equal((await a.next('signal')).code, 'CJ19:offerA');
  h.ws.send(JSON.stringify({ type: 'signal', id: bid, code: 'CJ19:offerB' })); assert.equal((await b.next('signal')).code, 'CJ19:offerB');
  a.ws.send(JSON.stringify({ type: 'signal', id: bid, code: 'CJ19:answerA' })); const answer = await h.next('signal'); assert.equal(answer.id, aid);
  const closed = once(a.ws, 'close'); a.ws.close(); await closed; assert.equal((await h.next('peer-left')).id, aid);
  const c = await connect('viewer'); await c.next('joined');
  const bclosed = once(b.ws, 'close'), cclosed = once(c.ws, 'close'); h.ws.close(); await Promise.all([bclosed, cclosed]);
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
