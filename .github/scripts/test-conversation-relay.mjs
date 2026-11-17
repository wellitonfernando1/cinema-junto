import { randomUUID, createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const key=createHash('sha256').update(randomUUID()).digest('hex');
const peers=[];
function next(ws, predicate, timeout=15000) {
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{cleanup();reject(new Error('Timeout waiting for relay response'));},timeout);
    function cleanup(){clearTimeout(timer);ws.removeEventListener('message',onMessage);}
    function onMessage(event){
      if(typeof event.data!=='string')return;
      const message=JSON.parse(event.data);
      if(message.type==='error'){cleanup();reject(new Error(message.message));return;}
      if(predicate(message)){cleanup();resolve(message);}
    }
    ws.addEventListener('message',onMessage);
  });
}
async function connect(role){
  const ws=new WebSocket('wss://cinema-junto-welliton.onrender.com/relay');peers.push(ws);
  await new Promise((resolve,reject)=>{
    const timeout=setTimeout(()=>reject(new Error('Relay did not open in 90 seconds')),90000);
    ws.addEventListener('open',()=>{clearTimeout(timeout);resolve();},{once:true});
    ws.addEventListener('error',()=>{clearTimeout(timeout);reject(new Error('Relay connection failed'));},{once:true});
  });
  const joined=next(ws,m=>m.joined===true);
  ws.send(JSON.stringify({role,room:key,device:randomUUID()}));
  await joined;return ws;
}
try{
  const host=await connect('host'), viewer=await connect('viewer');
  const first=next(host,m=>m.type==='chat'&&m.text==='teste-conversa');
  const second=next(viewer,m=>m.type==='chat'&&m.text==='teste-conversa');
  viewer.send(JSON.stringify({type:'chat',text:'teste-conversa'}));
  await Promise.all([first,second]);
  const grant=next(host,m=>m.type==='talk'&&m.active&&m.from==='host');
  const remote=next(viewer,m=>m.type==='talk'&&m.active);
  host.send(JSON.stringify({type:'talk',active:true}));await Promise.all([grant,remote]);
  viewer.binaryType='arraybuffer';
  const audio=new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(new Error('No voice packet relayed')),10000);
    viewer.addEventListener('message',e=>{
      if(typeof e.data==='string')return;
      clearTimeout(timer);
      try{assert.equal(new Uint8Array(e.data).length,653);assert.equal(new Uint8Array(e.data)[0],7);resolve();}
      catch(error){reject(error);}
    },{once:true});
  });
  const packet=Buffer.alloc(653);packet[0]=7;packet.writeBigInt64BE(1n,1);
  host.send(packet);await audio;
  const stop=next(viewer,m=>m.type==='talk'&&!m.active);
  host.send(JSON.stringify({type:'talk',active:false}));await stop;
  console.log('PASS: room creation, join, bidirectional chat echoes, voice grant, PCM relay and microphone stop.');
}finally{for(const ws of peers)ws.close();}
