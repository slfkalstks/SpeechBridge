import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import http from 'node:http';
import https from 'node:https';
import { X509Certificate } from 'node:crypto';
import { prepareLocalTls, privateAddress } from './local-tls.mjs';
import { createTlsProxy } from './tls-proxy.mjs';

const parent=fileURLToPath(new URL('../.runtime/tls-tests/',import.meta.url));
async function fixture(t) {
  await mkdir(parent,{recursive:true,mode:0o700});const root=await mkdtemp(join(parent,'case-'));
  t.after(()=>rm(root,{recursive:true,force:true}));
  const config=await prepareLocalTls({root,host:'127.0.0.1'});
  return {root,config,ca:await readFile(join(root,'ca.pem'))};
}
function request(port,{ca,path='/health',method='GET',body,headers={},servername}={}) {
  return new Promise((resolve,reject)=>{
    const req=https.request({host:'127.0.0.1',port,path,method,ca,servername,headers},res=>{
      const chunks=[];res.on('data',c=>chunks.push(c));res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(chunks)}));
    });req.on('connect',(res,socket)=>{socket.destroy();resolve({status:res.statusCode,headers:res.headers,body:Buffer.alloc(0)});});req.on('error',reject);req.end(body);
  });
}
test('project certificates are reusable, private, host-bound and have an isolated CA',async t=>{
  const f=await fixture(t),before=await readFile(join(f.root,'server.key'));
  const again=await prepareLocalTls({root:f.root,host:'127.0.0.1'});
  assert.deepEqual(again,f.config);assert.deepEqual(await readFile(join(f.root,'server.key')),before);
  assert.equal(new X509Certificate(f.ca).ca,true);
  const leaf=new X509Certificate(await readFile(join(f.root,'server.pem')));
  assert.equal(leaf.checkIP('127.0.0.1'),'127.0.0.1');assert.equal(leaf.checkIP('192.168.200.1'),undefined);
  assert.equal(leaf.verify(new X509Certificate(f.ca).publicKey),true);
  for(const name of ['ca.key','server.key'])assert.equal((await stat(join(f.root,name))).mode & 0o777,0o600);
  assert.equal((await stat(f.root)).mode & 0o777,0o700);
  assert.equal(f.config.origin,'https://127.0.0.1:8443');
  assert.ok((await readFile(join(f.root,'client.properties'),'utf8')).includes('serverUrl=https://127.0.0.1:8443'));
  const renewed=await prepareLocalTls({root:f.root,host:'192.168.50.2'});
  assert.equal(renewed.caFingerprint,f.config.caFingerprint);assert.equal(renewed.host,'192.168.50.2');
  assert.equal(new X509Certificate(await readFile(join(f.root,'server.pem'))).checkIP('192.168.50.2'),'192.168.50.2');
});
test('setup rejects public/unspecified addresses, privileged ports and paths outside this project',async()=>{
  for(const host of ['0.0.0.0','8.8.8.8','example.com','192.168.1.999','::','::1','127.0.0.2']) assert.equal(privateAddress(host),false);
  for(const host of ['10.0.0.2','172.16.0.1','172.31.255.1','192.168.1.2','127.0.0.1'])assert.equal(privateAddress(host),true);
  await assert.rejects(prepareLocalTls({host:'8.8.8.8'}),/private IPv4/);
  await assert.rejects(prepareLocalTls({host:'127.0.0.1',port:443}),/port/);
  await assert.rejects(prepareLocalTls({root:'/tmp/foreign-tls',host:'127.0.0.1'}),/project/);
});
test('TLS proxy validates certificates and preserves authenticated JSON/audio without forwarding unsafe headers',async t=>{
  const f=await fixture(t);let captured;
  const backend=http.createServer(async(req,res)=>{
    const chunks=[];for await(const c of req)chunks.push(c);
    captured={url:req.url,headers:req.headers,body:Buffer.concat(chunks).toString()};
    res.writeHead(req.url==='/audio'?200:201,{'Content-Type':req.url==='/audio'?'audio/wav':'application/json','X-Unsafe-Upstream':'omit'});
    res.end(req.url==='/audio'?Buffer.from([0,1,2,255]):JSON.stringify({ok:true}));
  });
  await new Promise(resolve=>backend.listen(0,'127.0.0.1',resolve));
  const proxy=await createTlsProxy({root:f.root,upstreamPort:backend.address().port});
  await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{proxy.closeAllConnections();backend.closeAllConnections();await Promise.all([new Promise(r=>proxy.close(r)),new Promise(r=>backend.close(r))]);});
  const port=proxy.address().port;
  await assert.rejects(request(port));
  await assert.rejects(request(port,{ca:f.ca,servername:'unrelated.invalid'}));
  const response=await request(port,{ca:f.ca,path:'/data/snapshot',method:'POST',body:'{"deviceId":"test"}',headers:{Authorization:'Bearer synthetic-token','Content-Type':'application/json','X-Forwarded-For':'spoofed','Cookie':'private-cookie'}});
  assert.equal(response.status,201);assert.equal(response.headers['cache-control'],'no-store');
  assert.equal(response.headers['x-unsafe-upstream'],undefined);
  assert.equal(captured.headers.authorization,'Bearer synthetic-token');assert.equal(captured.headers['x-forwarded-for'],undefined);
  assert.equal(captured.headers.cookie,undefined);assert.equal(captured.body,'{"deviceId":"test"}');
  const audio=await request(port,{ca:f.ca,path:'/audio'});assert.deepEqual(audio.body,Buffer.from([0,1,2,255]));
});
test('proxy rejects cross-origin request targets, oversized requests and fails safely when backend is unavailable',async t=>{
  const f=await fixture(t);const backend=http.createServer((req,res)=>res.end('{}'));
  await new Promise(resolve=>backend.listen(0,'127.0.0.1',resolve));const port=backend.address().port;
  await new Promise(resolve=>backend.close(resolve));
  const proxy=await createTlsProxy({root:f.root,upstreamPort:port});
  await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{proxy.closeAllConnections();await new Promise(r=>proxy.close(r));});
  assert.equal((await request(proxy.address().port,{ca:f.ca,path:'http://other.invalid/'})).status,400);
  assert.equal((await request(proxy.address().port,{ca:f.ca,method:'CONNECT',path:'127.0.0.1:8787'})).status,405);
  assert.equal((await request(proxy.address().port,{ca:f.ca,method:'POST',headers:{'Content-Length':2*1024*1024+1}})).status,413);
  const unavailable=await request(proxy.address().port,{ca:f.ca});assert.equal(unavailable.status,503);
  assert.equal(JSON.parse(unavailable.body).error.code,'server_unavailable');
});
