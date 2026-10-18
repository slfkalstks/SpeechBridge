import http from 'node:http';
import https from 'node:https';
import { readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkedTlsRoot, privateAddress, tlsRoot } from './local-tls.mjs';

export async function createTlsProxy({root=tlsRoot,upstreamPort=8787}={}) {
  if(!Number.isInteger(upstreamPort) || upstreamPort<1 || upstreamPort>65535)throw new Error('Invalid loopback API port');
  root=await checkedTlsRoot(root);
  const key=await readFile(join(root,'server.key')),cert=await readFile(join(root,'server.pem'));
  const server=https.createServer({key,cert,minVersion:'TLSv1.2',maxHeaderSize:16*1024},(req,res)=>{
    const fail=(status,code)=>{
      if(res.headersSent || res.destroyed)return;
      res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store','Connection':'close'});
      res.end(JSON.stringify({error:{code,message:'서버 연결 상태를 확인해 주세요.'}}));req.resume();
    };
    if(!['GET','POST'].includes(req.method))return fail(405,'method_not_allowed');
    if(!req.url?.startsWith('/') || req.url.startsWith('//'))return fail(400,'invalid_target');
    const max=2*1024*1024;
    if(Number(req.headers['content-length'])>max)return fail(413,'payload_too_large');
    const headers={};
    for(const name of ['authorization','content-type','content-length','accept'])if(req.headers[name])headers[name]=req.headers[name];
    const upstream=http.request({host:'127.0.0.1',port:upstreamPort,path:req.url,method:req.method,headers},response=>{
      if(res.destroyed){response.destroy();return;}
      const outgoing={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'};
      for(const name of ['content-type','content-length'])if(response.headers[name])outgoing[name]=response.headers[name];
      res.writeHead(response.statusCode,outgoing);response.pipe(res);
      response.on('error',()=>res.destroy());
    });
    upstream.on('error',()=>fail(503,'server_unavailable'));
    upstream.setTimeout(105_000,()=>{fail(504,'server_timeout');upstream.destroy();});
    let length=0;
    req.on('data',chunk=>{length+=chunk.length;if(length>max){req.unpipe(upstream);fail(413,'payload_too_large');upstream.destroy();}});
    req.on('aborted',()=>upstream.destroy());
    req.on('error',()=>upstream.destroy());
    res.on('close',()=>{if(!res.writableEnded)upstream.destroy();});
    req.pipe(upstream);
  });
  server.on('connect',(_,socket)=>socket.end('HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'));
  server.on('upgrade',(_,socket)=>socket.end('HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'));
  server.requestTimeout=15_000;server.headersTimeout=10_000;server.maxConnections=32;
  return server;
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const config=JSON.parse(await readFile(join(tlsRoot,'config.json'),'utf8'));
    if(config.version!==1 || !privateAddress(config.host) || !Number.isInteger(config.port) || config.port<1024 || config.port>65535)throw new Error('Invalid project TLS configuration');
    const server=await createTlsProxy();
    server.on('error',()=>{console.error('Project HTTPS could not listen on the configured address.');process.exitCode=1;});
    server.listen(config.port,config.host,()=>console.log(`SpeechBridge test HTTPS listening at ${config.origin}`));
    const stop=()=>{server.close();server.closeAllConnections();};
    process.once('SIGINT',stop);process.once('SIGTERM',stop);
  } catch { console.error('Prepare project TLS first. No system trust or other project was changed.');process.exitCode=1; }
}
