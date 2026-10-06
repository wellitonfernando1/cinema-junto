import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { createRelay } from './server.mjs';

async function fixture(t) {
  const relay = createRelay(); relay.server.listen(0, '127.0.0.1'); await once(relay.server, 'listening');
  t.after(() => relay.close());
  return `ws://127.0.0.1:${relay.server.address().port}/relay`;
}
async function join(url, role, room) {
  const ws = new WebSocket(url); await once(ws, 'open');
  const response = once(ws, 'message'); ws.send(JSON.stringify({ role, room }));
  await response; return ws;
}
test('transmite imagem e áudio apenas para o convidado da mesma sala', async t => {
  const url = await fixture(t);
  const host = await join(url, 'host', 'a'.repeat(64));
  const viewer = await join(url, 'viewer', 'a'.repeat(64));
  const otherHost = await join(url, 'host', 'b'.repeat(64));
  const otherViewer = await join(url, 'viewer', 'b'.repeat(64));
  let leaked = false; otherViewer.on('message', () => { leaked = true; });
  for (const type of [1, 2]) {
    const recv = once(viewer, 'message'); const packet = Buffer.from([type, 42, 43]); host.send(packet);
    const [got, binary] = await recv; assert.equal(binary, true); assert.deepEqual(got, packet);
  }
  const closed = once(viewer, 'close'); host.close(); await closed;
  assert.equal(leaked, false); otherHost.close();
});
test('rejeita convidado sem anfitrião, convite inválido e terceiro participante', async t => {
  const url = await fixture(t);
  async function rejected(role, room) {
    const ws = new WebSocket(url); await once(ws, 'open'); const closed = once(ws, 'close');
    ws.send(JSON.stringify({ role, room })); const [code] = await closed; assert.equal(code, 1008);
  }
  await rejected('viewer', 'c'.repeat(64)); await rejected('host', '1234');
  const host = await join(url, 'host', 'd'.repeat(64));
  await join(url, 'viewer', 'd'.repeat(64));
  await rejected('viewer', 'd'.repeat(64)); await rejected('host', 'd'.repeat(64)); host.close();
});
