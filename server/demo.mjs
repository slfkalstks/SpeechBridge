// Project-local demonstration launcher. Never stops, resets or migrates another service.
import { execFile, spawn } from 'node:child_process';
import { promisify, parseEnv } from 'node:util';
import { readFile, writeFile, open, chmod } from 'node:fs/promises';
import { randomBytes, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { resolve, join } from 'node:path';
import { request } from 'node:https';
import { setTimeout as wait } from 'node:timers/promises';
import { prepareLocalTls, checkedTlsRoot } from './local-tls.mjs';
import { createProviders } from './provider.mjs';
import { databaseFromConfig } from './db/target.mjs';

const execute=promisify(execFile);
const root=fileURLToPath(new URL('../',import.meta.url)).replace(/\/$/,'');
const server=join(root,'server'),runtime=join(root,'.runtime');
export function assertProjectListeners(listeners,project,address) {
  for(const listener of listeners) {
    if(listener.cwd!==project && !listener.cwd?.startsWith(project+'/') || listener.address!==address)
      throw new Error('Port is occupied by another project or interface; nothing was stopped or changed.');
  }
}
export async function seedDemo(post) {
  const snapshot=await post('/data/snapshot',{deviceId:'speechbridge-demo',platform:'test'});
  const utterance=(id,text,translation)=>({id,speaker:'',language:'KO',originalText:text,text,revision:1,translation,translationRevision:translation?1:null});
  const base={title:'시연 대화',createdAt:new Date().toISOString(),translate:true,record:true,first:'KO',second:'EN',ended:true,
    category:null,workType:null,folderId:null,summary:null,metadataRevision:0,organized:false,organizationAttempted:true,organizationSources:{},items:[]};
  const fixtures=[
    ['folder',{id:'demo-folder',name:'시연 프로젝트'}],
    ['conversation',{...base,id:'demo-work',title:'[시연] 발표 준비',category:'WORK',workType:'MEETING',folderId:'demo-folder',
      summary:'시연용 예시: 금요일까지 발표 자료를 준비하기로 했습니다.',
      utterances:[utterance('work-u','금요일까지 발표 자료를 준비해 주세요.','Please prepare the presentation materials by Friday.')],
      items:[{id:'work-task',sourceId:'work-u',sourceRevision:1,kind:'REQUEST',text:'발표 자료 준비',owner:null,dueText:'금요일',reviewed:true,status:'TODO',origin:'MANUAL',evidenceQuote:'금요일까지 발표 자료를 준비해 주세요.',contextRevisions:{},omittedByLatestExtraction:false}]}],
    ['conversation',{...base,id:'demo-daily',title:'[시연] 주말 약속',category:'DAILY',summary:'시연용 예시: 토요일 오후에 공원에서 만나기로 했습니다.',
      utterances:[utterance('daily-u','토요일 오후에 공원에서 만나요.','Let’s meet at the park on Saturday afternoon.')]}],
    ['conversation',{...base,id:'demo-live',title:'[시연] 종료 후 자동 정리',ended:false,organizationAttempted:false,
      utterances:[utterance('live-u','팀 회의에서 금요일 출시를 확정했습니다.','The team confirmed the Friday launch at the meeting.')]}],
  ];
  for(const [entity,value] of fixtures) {
    // Tombstones have versions too: never resurrect a record the presenter deleted.
    if(snapshot.versions[`${entity}:${value.id}`]!==undefined)continue;
    if(entity==='conversation' && value.folderId && snapshot.versions[`folder:${value.folderId}`]!==undefined &&
      !snapshot.archive.folders.some(f=>f.id===value.folderId))value.folderId=null;
    await post('/data/mutate',{deviceId:'speechbridge-demo',platform:'test',entity,id:value.id,value,baseVersion:'0',mutationId:randomUUID()});
  }
}
async function listeners(port) {
  let output;
  try {output=(await execute('/usr/sbin/lsof',['-nP',`-iTCP:${port}`,'-sTCP:LISTEN','-Fpn'])).stdout;}
  catch(error){if(error.code===1 && !error.stdout)return [];throw new Error('Cannot inspect listening services.');}
  const found=[];let pid;
  for(const line of output.split('\n')) {
    if(line.startsWith('p'))pid=Number(line.slice(1));
    if(line.startsWith('n')) {
      const cwd=(await execute('/usr/sbin/lsof',['-a','-p',String(pid),'-d','cwd','-Fn'])).stdout.split('\n').find(v=>v.startsWith('n'))?.slice(1);
      found.push({pid,cwd,address:line.slice(1)});
    }
  }return found;
}
async function ensureService(name,port,address,command,args,env,directory) {
  const current=await listeners(port);assertProjectListeners(current,root,address);
  if(current.length)return;
  const log=await open(join(directory,`${name}-${Date.now()}.log`),'wx',0o600);
  let child;
  try {
    child=spawn(command,args,{cwd:server,env,detached:true,stdio:['ignore',log.fd,log.fd]});
    await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});
    child.unref();
    await writeFile(join(directory,`${name}.pid`),String(child.pid)+'\n',{mode:0o600});
  }finally{await log.close();}
  for(let attempt=0;attempt<60;attempt++) {
    const running=await listeners(port);assertProjectListeners(running,root,address);
    if(running.length)return;
    await wait(500);
  }throw new Error(`${name} did not start; inspect its private log in .runtime/demo.`);
}
export function tlsJson(origin,ca,path,body,token) {
  return new Promise((resolve,reject)=>{
    const content=body===undefined?undefined:JSON.stringify(body);
    const req=request(new URL(path,origin),{ca,method:content?'POST':'GET',headers:{...(content?{'Content-Type':'application/json'}:{}),...(token?{Authorization:`Bearer ${token}`}:{})}},res=>{
      let result='';res.setEncoding('utf8');
      res.on('data',chunk=>{result+=chunk;if(result.length>2_000_000)req.destroy(new Error('Response too large'));});
      res.on('error',reject);res.on('end',()=>{
        if(res.statusCode!==200){reject(new Error(`Demo API returned ${res.statusCode}; no credentials were printed.`));return;}
        try{resolve(JSON.parse(result));}catch{reject(new Error('Invalid demo API response'));}
      });
    });
    req.setTimeout(105_000,()=>req.destroy(new Error('Demo API timed out')));req.on('error',reject);req.end(content);
  });
}
async function main() {
  if(!['start','check'].includes(process.argv[2] || 'start'))throw new Error('Usage: node demo.mjs [start|check]');
  const start=process.argv[2]!=='check';
  const directory=await checkedTlsRoot(join(runtime,'demo'));
  const accountConfig=parseEnv(await readFile(join(runtime,'accounts-server.env'),'utf8'));
  const dbConfig=parseEnv(await readFile(join(runtime,'postgres/server.env'),'utf8'));databaseFromConfig(dbConfig);
  if(accountConfig.AUTH_MODE!=='users' || accountConfig.HOST!=='127.0.0.1' || accountConfig.PORT!=='8787' ||
    accountConfig.AI_PROVIDER!=='ollama-local' || accountConfig.SPEECH_PROVIDER!=='local-process' || accountConfig.OLLAMA_BASE_URL!=='http://127.0.0.1:11435')
    throw new Error('Demo needs the project member/local-AI server configuration. Existing configuration was preserved.');
  const config=start?await prepareLocalTls():JSON.parse(await readFile(join(runtime,'tls/config.json'),'utf8'));
  for(const [port,address] of [[11435,'127.0.0.1:11435'],[8787,'127.0.0.1:8787'],[config.port,`${config.host}:${config.port}`]])
    assertProjectListeners(await listeners(port),root,address);
  if(start) {
    await execute(process.execPath,['db/local.mjs','start'],{cwd:server});
    await ensureService('ai',11435,'127.0.0.1:11435','/bin/sh',['start-ai.sh'],process.env,directory);
    await ensureService('api',8787,'127.0.0.1:8787',process.execPath,[`--env-file=${join(runtime,'accounts-server.env')}`,`--env-file=${join(runtime,'postgres/server.env')}`,'server.mjs'],process.env,directory);
    await ensureService('https',config.port,`${config.host}:${config.port}`,process.execPath,['tls-proxy.mjs'],process.env,directory);
  }
  const ca=await readFile(join(runtime,'tls/ca.pem'));
  if((await tlsJson(config.origin,ca,'/health')).authentication!=='users')throw new Error('Member server is not ready.');
  const providers=createProviders(accountConfig);
  const health=await providers.health({signal:AbortSignal.timeout(10_000)});
  if(!health.available?.translation || !health.available?.extraction || !health.available?.speech)throw new Error('Local text or voice models are not ready.');
  const credentialPath=join(directory,'account.json');let credentials,fresh=false;
  try {credentials=JSON.parse(await readFile(credentialPath,'utf8'));}
  catch(error) {
    if(error.code!=='ENOENT')throw error;
    if(!start)throw new Error('Run demo:start once to prepare the synthetic account.');
    // Lowercase letters allow reliable entry with the simulator software keyboard.
    const letters=length=>Array.from(randomBytes(length),v=>String.fromCharCode(97+v%26)).join('');
    credentials={username:'demo'+letters(12),password:letters(24),createdAt:new Date().toISOString()};
    await writeFile(credentialPath,JSON.stringify(credentials,null,2)+'\n',{flag:'wx',mode:0o600});fresh=true;
  }
  await chmod(credentialPath,0o600);
  if(!/^demo[a-z]{12}$/.test(credentials.username) || !/^[a-z]{24}$/.test(credentials.password))throw new Error('Invalid private demo account file; preserved for inspection.');
  const session=await tlsJson(config.origin,ca,fresh?'/auth/register':'/auth/login',credentials);
  try {
    const post=(path,body)=>tlsJson(config.origin,ca,path,body,session.token);
    if(start)await seedDemo(post);
    const snapshot=await post('/data/snapshot',{deviceId:'speechbridge-demo',platform:'test'});
    const report={checkedAt:new Date().toISOString(),origin:config.origin,caFingerprint:config.caFingerprint,
      expiresAt:config.expiresAt,memberHttps:true,database:true,textModels:true,voiceFiles:true,
      records:snapshot.archive.conversations.length,physicalDeviceVerified:false};
    await writeFile(join(directory,'readiness.json'),JSON.stringify(report,null,2)+'\n',{mode:0o600});
    console.log(`시연 서버 준비 완료: ${config.origin}\n회원 HTTPS·PostgreSQL·모델 파일 확인. 실제 마이크/휴대전화 품질은 별도 검증입니다.\n시연 로그인: .runtime/demo/account.json (로컬 전용)\n시연 절차: docs/DEMO.md`);
  }finally{await tlsJson(config.origin,ca,'/auth/logout',{},session.token);}
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(()=>{
  console.error('시연 준비 실패. 프로젝트 서비스/포트·Wi-Fi·.runtime/demo 로그를 확인하세요. 기존 기록과 다른 프로젝트는 변경하지 않았습니다.');process.exitCode=1;
});
