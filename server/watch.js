const el = id => document.getElementById(id);
const video = el('video');
const params = new URLSearchParams(location.search);
const invite = params.get('sala');
const status = value => { el('status').textContent = value; };
const mediaStatus = value => { el('videoStatus').textContent = value; };
const key = () => 'cinema:' + params.get('sala') + ':' + params.has('chat');
let socket, details, state, request, selected, hls, timer;
let ready = false, enabled = false, retry = 0, offset = 0, seekEdited = false;
const send = value => {
  if (socket?.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify(value));
  return true;
};
function entryMode() {
  const joining = params.has('sala');
  el('sourceLabel').hidden = joining;
  el('source').required = !joining;
  el('passwordLabel').hidden = !joining;
  el('password').required = joining;
  el('modeLabel').hidden = !joining;
  el('chatOnly').checked = params.has('chat');
  el('enter').textContent = joining ? 'Entrar na sala' : 'Criar sala';
}
entryMode();

function connect() {
  const endpoint = new URL('/room', location.href);
  endpoint.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(endpoint);
  socket = ws;
  ws.onopen = () => {
    if (socket !== ws) return;
    send({ type: 'clock', sent: Date.now() });
    send(request);
  };
  ws.onmessage = event => {
    if (socket !== ws) return;
    const msg = JSON.parse(event.data);
    if (msg.type === 'clock') offset = msg.now - (msg.sent + Date.now()) / 2;
    if (msg.type === 'error') {
      status(msg.message);
      if (!ready) ws.close(4000);
    }
    if (msg.type === 'joined') {
      ready = true;
      retry = 0;
      details = msg;
      params.set('sala', msg.room);
      if (msg.mode === 'chat') params.set('chat', '1');
      else params.delete('chat');
      history.replaceState(null, '', '?' + params);
      request = { type: 'join', room: msg.room, session: msg.session, name: msg.name };
      sessionStorage.setItem(key(), JSON.stringify(request));
      el('entry').hidden = true;
      el('room').hidden = false;
      el('player').hidden = msg.mode === 'chat';
      el('toggle').hidden = !msg.host;
      el('seekLabel').hidden = !msg.host;
      el('history').replaceChildren();
      msg.history.forEach(appendMessage);
      status('Conectado');
      update(msg.state);
    }
    if (msg.type === 'state') update(msg.state);
    if (msg.type === 'message') appendMessage(msg.item);
    if (msg.type === 'presence') el('count').textContent = msg.count + ' na sala';
  };
  ws.onclose = event => {
    if (socket !== ws) return;
    ready = false;
    video.pause();
    el('enter').disabled = false;
    if (event.code === 4001) {
      request = undefined;
      return status('Sessão aberta em outra aba.');
    }
    if (event.code === 4000) {
      request = undefined;
      sessionStorage.removeItem(key());
      el('room').hidden = true;
      el('entry').hidden = false;
      entryMode();
      return;
    }
    status('Reconectando…');
    timer = setTimeout(connect, Math.min(10000, 500 * 2 ** Math.min(retry++, 5)));
  };
}
function appendMessage(item) {
  const list = el('history');
  const row = document.createElement('li');
  row.textContent = item.name + ': ' + item.text;
  list.append(row);
  if (list.children.length > 100) list.firstChild.remove();
  list.scrollTop = list.scrollHeight;
}
function update(next) {
  state = next;
  if (details?.mode === 'chat') return;
  if (selected !== next.url) {
    selected = next.url;
    enabled = false;
    hls?.destroy();
    hls = undefined;
    mediaStatus('');
    el('activate').hidden = false;
    if (new URL(selected).pathname.toLowerCase().endsWith('.m3u8') && !video.canPlayType('application/vnd.apple.mpegurl')) {
      if (window.Hls?.isSupported()) {
        hls = new Hls({ capLevelToPlayerSize: false });
        hls.on(Hls.Events.ERROR, (_, error) => { if (error.fatal) mediaStatus('Falha ao carregar o vídeo HLS.'); });
        hls.loadSource(selected);
        hls.attachMedia(video);
      } else mediaStatus('Este navegador não conseguiu carregar o player HLS.');
    } else video.src = selected;
  }
  el('toggle').textContent = state.playing ? 'Pausar' : 'Reproduzir';
  align();
}
function position() {
  const elapsed = Math.max(0, Date.now() + offset - state.at) / 1000;
  return state.position + (state.playing ? elapsed : 0);
}
function align() {
  if (!ready || !state || details?.mode !== 'watch') return;
  const duration = video.duration;
  const finite = Number.isFinite(duration) && duration > 0;
  const target = finite ? Math.min(duration, position()) : position();
  if (video.readyState && !video.seeking && Math.abs(video.currentTime - target) > (state.playing ? .65 : .12)) video.currentTime = target;
  if (!state.playing || (finite && target >= duration)) video.pause();
  else if (enabled && video.paused && !video.ended) video.play().catch(showActivation);
  el('seek').disabled = !finite;
  if (finite) {
    el('seek').max = duration;
    if (!seekEdited) el('seek').value = target;
  }
  const seconds = Math.floor(target);
  el('time').textContent = Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
}
function showActivation() {
  enabled = false;
  el('activate').hidden = false;
}
function activate() {
  enabled = true;
  el('activate').hidden = true;
  video.play().then(() => { if (!state.playing) video.pause(); }).catch(showActivation);
}
function command(playing, position) {
  if (ready && details?.host) send({ type: 'playback', playing, position, version: state.version });
}
el('activate').onclick = activate;
el('toggle').onclick = () => {
  if (!ready || !state) return;
  if (!state.playing && !enabled) activate();
  const ended = Number.isFinite(video.duration) && position() >= video.duration;
  command(!state.playing, !state.playing && ended ? 0 : Math.min(video.duration || Infinity, position()));
};
el('seek').oninput = () => { seekEdited = true; };
el('seek').onchange = () => {
  seekEdited = false;
  if (state) command(state.playing, Number(el('seek').value));
};
video.onloadedmetadata = align;
video.oncanplay = align;
video.onended = () => command(false, video.duration);
video.onerror = () => mediaStatus('Este aparelho não conseguiu carregar o vídeo.');
setInterval(align, 500);
setInterval(() => { if (ready) send({ type: 'clock', sent: Date.now() }); }, 15000);
document.addEventListener('visibilitychange', align);
window.addEventListener('online', () => {
  if (request && socket?.readyState === WebSocket.CLOSED) { clearTimeout(timer); connect(); }
});
el('entry').onsubmit = event => {
  event.preventDefault();
  clearTimeout(timer);
  const room = params.get('sala');
  request = { type: room ? 'join' : 'create', room, name: el('name').value, url: el('source').value, password: el('password').value.toUpperCase(), mode: el('chatOnly').checked ? 'chat' : 'watch' };
  el('enter').disabled = true;
  connect();
};
el('chat').onsubmit = event => {
  event.preventDefault();
  if (ready && send({ type: 'chat', text: el('message').value })) el('message').value = '';
  else status('Aguarde a reconexão para enviar.');
};
async function share(chatOnly) {
  const link = new URL('/', location.href);
  link.searchParams.set('sala', details.room);
  if (chatOnly) link.searchParams.set('chat', '1');
  const text = link.href + '\nSenha: ' + details.password;
  try {
    if (navigator.share) await navigator.share({ text });
    else if (navigator.clipboard) { await navigator.clipboard.writeText(text); status('Convite copiado'); }
    else window.prompt('Copie o convite', text);
  } catch (error) {
    if (error.name !== 'AbortError') window.prompt('Copie o convite', text);
  }
}
el('share').onclick = () => share(false);
el('shareChat').onclick = () => share(true);
if (invite) {
  const saved = sessionStorage.getItem(key());
  if (saved) { request = JSON.parse(saved); el('enter').disabled = true; connect(); }
}
