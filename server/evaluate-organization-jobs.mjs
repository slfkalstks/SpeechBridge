// Synthetic record only. Uses installed free Mac models and the project's test DB.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { setTimeout as wait } from 'node:timers/promises';
import { createDatabase } from './db/database.mjs';
import { createOrganizationJobs } from './db/organization-jobs.mjs';
import { createServer } from './server.mjs';

process.loadEnvFile(new URL('../.runtime/postgres/test.env',import.meta.url));
assert.equal(process.env.PGDATABASE,'speechbridge_test');
const env={...process.env,AUTH_MODE:'users',AI_PROVIDER:'ollama-local'};
const db=createDatabase(env),jobs=createOrganizationJobs(db);
let server,origin,userId,token;
const scopedJobs={...jobs,usersAfter:async(after='')=>userId && userId>after?[userId]:[]};
async function start() {
  server=createServer({env,database:db,organizationJobs:scopedJobs});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  origin=`http://127.0.0.1:${server.address().port}`;
}
async function stop() {
  server.cancelActiveRequests();server.closeAllConnections();
  await new Promise(resolve=>server.close(resolve));await server.stopOrganizationWorker();
}
async function post(path,body) {
  const response=await fetch(origin+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});
  assert.equal(response.status,200,`Unexpected API status ${response.status}`);return response.json();
}
const report={createdAt:new Date().toISOString(),scope:'Synthetic typed conversation; member HTTP, real PostgreSQL test DB and installed local text model. No microphone or physical device test.'};
try {
  await start();
  const session=await post('/auth/register',{username:`jobs_${randomUUID().replaceAll('-','').slice(0,20)}`,password:'Synthetic-organization-123!'});
  token=session.token;userId=session.user.id;
  const c={id:'synthetic',title:'새 대화',createdAt:new Date().toISOString(),translate:true,record:true,first:'KO',second:'EN',ended:false,
    category:null,workType:null,folderId:null,summary:null,metadataRevision:0,organizationAttempted:false,organized:false,organizationSources:{},items:[],
    utterances:[{id:'u',speaker:'',language:'KO',originalText:'팀 회의에서 금요일 출시를 확정했습니다.',text:'팀 회의에서 금요일 출시를 확정했습니다.',revision:1,translation:null,translationRevision:null}]};
  const put=(value,baseVersion)=>post('/data/mutate',{deviceId:'synthetic-jobs',platform:'test',entity:'conversation',id:c.id,mutationId:randomUUID(),baseVersion,value});
  await put(c,'0');await put({...c,ended:true},'1');
  assert.equal((await post('/data/snapshot',{deviceId:'synthetic-jobs'})).organizationJobs.synthetic,'pending');
  await stop();await start();
  report.pendingSurvivedServerRecreation=true;
  const began=performance.now();await server.ready();
  let snapshot;
  for(let i=0;i<60;i++) {
    snapshot=await post('/data/snapshot',{deviceId:'synthetic-jobs'});
    if(['succeeded','failed','cancelled'].includes(snapshot.organizationJobs.synthetic))break;
    await wait(2500);
  }
  assert.equal(snapshot.organizationJobs.synthetic,'succeeded');
  const completed=snapshot.archive.conversations[0];
  assert.equal(completed.organizationAttempted,true);assert.equal(completed.organized,true);
  report.milliseconds=Math.round(performance.now()-began);
  report.result={title:completed.title,summary:completed.summary,category:completed.category,workType:completed.workType};
  await put({...completed,title:'사용자가 확정한 제목',metadataRevision:completed.metadataRevision+1},snapshot.versions['conversation:synthetic']);
  await wait(1500);
  snapshot=await post('/data/snapshot',{deviceId:'synthetic-jobs'});
  assert.equal(snapshot.archive.conversations[0].title,'사용자가 확정한 제목');
  const records=await db.forUser(userId,client=>client.query('SELECT state,attempts FROM ai_jobs'));
  assert.equal(records.rowCount,1);assert.equal(records.rows[0].state,'succeeded');
  report.jobCount=1;report.attempts=records.rows[0].attempts;report.manualEditPreserved=true;
  await post('/auth/logout',{});
} finally {
  if(server?.listening)await stop();
  if(userId)await db.auth.query('DELETE FROM users WHERE id=$1',[userId]);
  await db.close();
}
await writeFile(new URL('./validation/organization-jobs.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log('Persistent organization: server recreation, local AI, DB result and later manual edit verified. Synthetic account removed.');
