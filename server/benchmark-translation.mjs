// Opt-in synthetic performance test. No user conversations, DB writes or microphone access.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash, randomBytes } from 'node:crypto';
import { createProviders } from './provider.mjs';
import { createServer } from './server.mjs';
const label=process.argv[2];
if(!/^[a-z][a-z0-9-]{0,40}$/.test(label||''))throw new Error('Usage: node server/benchmark-translation.mjs <label>');
const base='http://127.0.0.1:11435';
const env={AI_PROVIDER:'ollama-local',SPEECH_PROVIDER:'disabled',APP_ACCESS_TOKEN:randomBytes(32).toString('hex')};
const fixtures=[
 ['ko','en','내일 오후 3시까지 보고서 두 부를 보내 주세요.'],
 ['en','ko','Please do not cancel the reservation. Change it to 7 PM tomorrow.'],
 ['ko','ja','이 음식에 땅콩이 들어 있나요? 저는 땅콩을 먹을 수 없습니다.'],
 ['ja','ko','会議は金曜日ではなく、木曜日の午後2時に変更になりました。'],
 ['en','ja','Please send the updated schedule to Yuki by Friday.'],
 ['ja','en','駅まで歩いて何分かかりますか。タクシーは使いません。'],
 ['ko','en','예약을 취소하지 마세요. 참석자는 12명에서 9명으로 줄었고, 시작 시간은 다음 화요일 오후 2시에서 3시 30분으로 바뀌었습니다. 장소와 식사 메뉴는 그대로 유지해 주세요.'],
 ['en','ko','The draft is not approved yet. Please send only the revised introduction to Mina by Thursday, not the full report. If the figures are still unverified, mark them as estimates rather than confirmed results.'],
 ['ko','ja','다나카 씨, 지난번에 보낸 파일은 사용하지 마세요. 이번 메일의 첨부 파일로 바꿔 주세요. 금액은 15,000엔이 아니라 1,500엔입니다. 확인되면 답장을 부탁드립니다.'],
 ['ja','ko','会議は金曜日ではなく木曜日の午後2時に変更になりました。田中さんは資料を準備し、ユキさんは会場の予約だけを確認してください。予約を取り直す必要はありません。'],
 ['en','ja','Yuki, please review pages 2 through 5 by Friday. Do not change the customer names. We agreed to postpone the launch, but we have not chosen a new date. The budget remains unchanged.'],
 ['ja','en','駅まで歩いて何分かかりますか。タクシーは使いません。午後6時までに受付を済ませたいので、途中で道に迷った場合の連絡先も教えてください。'],
].map(([from,to,text])=>({from,to,text,revision:1}));
const report={label,date:new Date().toISOString(),scope:'Synthetic HTTP translation on project-local Ollama; no microphone, UI rendering, TLS or PostgreSQL. Cold means unloaded runner, not OS disk cache.',
 sourceSha256:createHash('sha256').update(await readFile(new URL('./local-provider.mjs',import.meta.url))).digest('hex'),
 runtime:await(await fetch(base+'/api/version')).json(),fixtures,results:[]};
const out=new URL(`../.ecc/benchmarks/translation-${label}.json`,import.meta.url);
const save=()=>writeFile(out,JSON.stringify(report,null,2)+'\n');
let metrics=[];
const provider=createProviders(env,{fetchImpl:async(url,options)=>{
 const start=performance.now();const response=await fetch(url,options);
 const result=await response.clone().json();
 metrics.push({path:new URL(url).pathname,elapsedMs:performance.now()-start,...Object.fromEntries(['total_duration','load_duration','prompt_eval_count','prompt_eval_duration','eval_count','eval_duration'].filter(k=>result[k]!==undefined).map(k=>[k,result[k]]))});
 return response;
}});
// This test server's admission clock advances per request so the 60/min anti-abuse limit
// does not censor fast sequential measurements. Production rate limits stay unchanged.
let clock=Date.now();
const server=createServer({env,providers:provider,now:()=>clock+=61000});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin=`http://127.0.0.1:${server.address().port}`;
async function request(input){
 const start=performance.now();
 const response=await fetch(origin+'/translate',{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${env.APP_ACCESS_TOKEN}`},body:JSON.stringify(input)});
 const result=await response.json();return {status:response.status,elapsedMs:performance.now()-start,result};
}
async function measure(group,index){
 metrics=[];const r=await request(fixtures[index%fixtures.length]);
 report.results.push({group,index,fixture:index%fixtures.length,...r,metrics});
 await save();if(group!=='warm'||index%10===0)console.log(group,index,r.status,Math.round(r.elapsedMs)+'ms');
 if(r.status!==200)throw new Error('Sequential benchmark failed');
}
async function load(model,keep_alive,num_ctx){
 const response=await fetch(base+'/api/generate',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({model,keep_alive,stream:false,options:{num_ctx}})});
 if(!response.ok)throw new Error('Model load/unload failed');await response.json();
}
const stats=values=>{const a=[...values].sort((a,b)=>a-b);const p=n=>a[Math.ceil(a.length*n)-1];return {n:a.length,meanMs:a.reduce((x,y)=>x+y,0)/a.length,p50Ms:p(.5),p95Ms:p(.95),p99Ms:p(.99)};};
try{
 report.models=(await(await fetch(base+'/api/tags')).json()).models.map(({name,digest,size})=>({name,digest,size}));
 for(let i=0;i<3;i++){await load('translategemma:12b',0,4096);await measure('cold',i);}
 for(let i=0;i<100;i++)await measure('warm',i);
 for(let i=0;i<3;i++){
  await load('gemma4:12b-it-qat','30m',8192);
  const residentBefore=(await(await fetch(base+'/api/ps')).json()).models.map(({name,size_vram})=>({name,size_vram}));
  await measure('after-summary-model',i);report.results.at(-1).residentBefore=residentBefore;
 }
 report.concurrent=await Promise.all(Array.from({length:10},(_,i)=>request({...fixtures[0],text:`회의 자료 ${i+1}번을 내일까지 보내 주세요.`})));
 report.residentAfter=(await(await fetch(base+'/api/ps')).json()).models.map(({name,size_vram})=>({name,size_vram}));
 report.summary=Object.fromEntries(['cold','warm','after-summary-model'].map(g=>[g,stats(report.results.filter(r=>r.group===g).map(r=>r.elapsedMs))]));
 if (provider.warmup) {
  report.preparedFirst=[];
  for(let i=0;i<3;i++) {
   await load('translategemma:12b',0,4096);
   const start=performance.now();await provider.warmup();
   const preparationMs=performance.now()-start;
   report.preparedFirst.push({fixture:i,preparationMs,...await request(fixtures[i])});
  }
 }
 await save();console.log(JSON.stringify(report.summary));
}finally{server.cancelActiveRequests();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
