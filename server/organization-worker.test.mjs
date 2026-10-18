import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as wait } from 'node:timers/promises';
import { createOrganizationWorker } from './organization-worker.mjs';
import { createServer } from './server.mjs';

function fixture(options={}) {
  const calls=[],job={id:'j',user_id:'a',input:{utterances:[]}};
  let available=true,busy=false;
  const jobs={
    async usersAfter(after){calls.push(['users',after]);return after===''?['a','b']:[];},
    async claim(user){calls.push(['claim',user]);if(user==='a' && available){available=false;return job;}return null;},
    async complete(j,result){calls.push(['complete',j,result]);},async fail(j,error){calls.push(['fail',j,error.code]);},
    async release(j){calls.push(['release',j]);available=true;},
  };
  const worker=createOrganizationWorker({jobs,organize:async()=>({title:'done'}),acquire:()=>{
    if(busy)return null;busy=true;return()=>{busy=false;};},...options});
  return {worker,jobs,calls,setBusy:value=>{busy=value;},isBusy:()=>busy};
}
test('worker serializes runs, uses persisted input and releases compute admission',async()=>{
  let finish;const f=fixture({organize:()=>new Promise(resolve=>{finish=resolve;})});
  const a=f.worker.runOnce(),b=f.worker.runOnce();
  await wait(0);assert.equal(f.calls.filter(c=>c[0]==='claim').length,1);assert.equal(f.isBusy(),true);
  finish({title:'done'});await Promise.all([a,b]);
  assert.equal(f.calls.filter(c=>c[0]==='complete').length,1);assert.equal(f.isBusy(),false);
  await f.worker.runOnce();assert.ok(f.calls.some(c=>c[0]==='users' && c[1]==='a'));
  await f.worker.stop();
});
test('busy admission does not claim or consume job attempts',async()=>{
  const f=fixture();f.setBusy(true);await f.worker.runOnce();assert.deepEqual(f.calls,[]);
  f.setBusy(false);await f.worker.runOnce();assert.ok(f.calls.some(c=>c[0]==='complete'));await f.worker.stop();
});
test('shutdown aborts inference then returns the same job to pending',async()=>{
  const f=fixture({organize:(_,context)=>new Promise((_,reject)=>context.signal.addEventListener('abort',()=>reject(context.signal.reason),{once:true}))});
  const work=f.worker.runOnce();await wait(0);await f.worker.stop();await work;
  assert.equal(f.calls.filter(c=>c[0]==='release').length,1);assert.equal(f.calls.filter(c=>c[0]==='fail').length,0);
  assert.equal(f.isBusy(),false);
});
test('deadline and provider errors are persisted; scheduler recovers after a database outage',async()=>{
  const f=fixture({deadlineMs:5,organize:(_,context)=>new Promise((_,reject)=>context.signal.addEventListener('abort',()=>reject(context.signal.reason),{once:true}))});
  await f.worker.runOnce();assert.ok(f.calls.some(c=>c[0]==='fail' && c[2]==='model_timeout'));await f.worker.stop();
  const g=fixture({organize:async()=>{throw {code:'runtime_offline'};}});
  await g.worker.runOnce();assert.ok(g.calls.some(c=>c[0]==='fail' && c[2]==='runtime_offline'));await g.worker.stop();
  const h=fixture({intervalMs:5}); const users=h.jobs.usersAfter;let attempts=0;
  h.jobs.usersAfter=async(...args)=>{if(++attempts===1)throw Error('private database error');return users(...args);};
  h.worker.start();h.worker.start();
  for(let i=0;i<100 && !h.calls.some(c=>c[0]==='complete');i++)await wait(5);
  await h.worker.stop();assert.ok(h.calls.some(c=>c[0]==='complete'));assert.equal(h.isBusy(),false);
});
test('server worker shares admission with voice and graceful shutdown preserves its job',async t=>{
  const f=fixture();let started;
  const began=new Promise(resolve=>{started=resolve;});
  const providers={configured:{},organize:(_,context)=>new Promise((_,reject)=>{
    started();context.signal.addEventListener('abort',()=>reject(context.signal.reason),{once:true});}),
    transcribe:async()=>({text:'ok',language:'ko'})};
  const server=createServer({env:{AUTH_MODE:'users'},accounts:{authenticate:async()=>({id:'a'}),authorize(){}},organizationJobs:f.jobs,providers});
  await server.ready();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{server.cancelActiveRequests();await new Promise(resolve=>server.close(resolve));await server.stopOrganizationWorker();});
  await began;
  const response=await fetch(`http://127.0.0.1:${server.address().port}/speech/transcribe`,{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer synthetic'},body:'{}'});
  assert.equal(response.status,429);
  server.cancelActiveRequests();await server.stopOrganizationWorker();
  assert.ok(f.calls.some(c=>c[0]==='release'));
});
