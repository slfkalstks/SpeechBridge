import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createDatabase } from './db/database.mjs';
import { createPostgresAccounts } from './db/accounts.mjs';
import { createUserData } from './db/user-data.mjs';
import { createOrganizationJobs } from './db/organization-jobs.mjs';

process.loadEnvFile(new URL('../.runtime/postgres/test.env', import.meta.url));
assert.equal(process.env.PGDATABASE, 'speechbridge_test');
const result = { title: '문서 전달', summary: '내일까지 문서를 전달할 예정이다.', category: 'WORK', workType: 'TASK', folderId: null };
const record = () => ({ id:'conversation',title:'새 대화',createdAt:'2026-10-18T00:00:00Z',first:'KO',second:'EN',translate:true,record:true,ended:false,
  category:null,workType:null,folderId:null,metadataRevision:0,organized:false,organizationAttempted:false,summary:null,organizationSources:{},items:[],
  utterances:[{id:'u',language:'KO',speaker:'',originalText:'내일까지 문서를 전달합니다.',text:'내일까지 문서를 전달합니다.',revision:1,translation:null,translationRevision:null}] });
async function fixture(t) {
  const db=createDatabase(process.env), accounts=createPostgresAccounts({db});
  const {user}=await accounts.handle('/auth/register',{username:`j_${randomUUID().replaceAll('-','').slice(0,20)}`,password:'Synthetic-password-123!'});
  const data=createUserData(db), jobs=createOrganizationJobs(db);
  const put=(c,baseVersion='0',mutationId=randomUUID()) => data.mutate(user.id,{deviceId:'test',mutationId,entity:'conversation',id:'conversation',baseVersion,value:c});
  const read=()=>data.snapshot(user.id,'test');
  const rows=()=>db.forUser(user.id,async client=>(await client.query('SELECT * FROM ai_jobs')).rows);
  const end=async()=>{const c=record(); await put(c); await put({...c,ended:true},'1');};
  t.after(async()=>{await db.auth.query('DELETE FROM users WHERE id=$1',[user.id]);await db.close();});
  return {db,user,data,jobs,put,read,rows,end};
}
test('ending queues exactly once in the same transaction; first AI result publishes a new revision',async t=>{
  const f=await fixture(t); const c=record(); await f.put(c);
  assert.equal((await f.rows()).length,0);
  const mutation=randomUUID(); await f.put({...c,ended:true},'1',mutation); await f.put({...c,ended:true},'1',mutation);
  assert.equal((await f.rows()).length,1);
  assert.equal((await f.read()).organizationJobs.conversation,'pending');
  const job=await f.jobs.claim(f.user.id);
  assert.equal(job.input.language,'ko'); assert.deepEqual(job.input.utterances,[{id:'u',revision:1,text:c.utterances[0].text}]);
  assert.equal(await f.jobs.claim(f.user.id),null);
  await f.jobs.complete(job,result);
  const snapshot=await f.read(), saved=snapshot.archive.conversations[0];
  assert.equal(saved.title,result.title); assert.equal(saved.summary,result.summary);
  assert.equal(saved.metadataRevision,1); assert.equal(saved.organizationAttempted,true); assert.equal(saved.organized,true);
  assert.deepEqual(saved.organizationSources,{u:1}); assert.equal(snapshot.versions['conversation:conversation'],'3');
  assert.equal(snapshot.organizationJobs.conversation,'succeeded'); assert.equal(snapshot.cursor,'3');
  await f.jobs.complete(job,{...result,title:'중복 완료'});
  assert.equal((await f.read()).cursor,'3');
  await f.put({...saved,title:'사용자 수정',metadataRevision:2},'3');
  assert.equal(await f.jobs.claim(f.user.id),null); assert.equal((await f.rows()).length,1);
});
test('new ended imports, empty conversations and already organized records do not trigger AI',async t=>{
  for(const variant of ['import','empty','manual']) {
    const f=await fixture(t),c=record();
    if(variant==='empty') c.utterances=[];
    if(variant==='manual') c.organized=true;
    if(variant!=='import') await f.put(c);
    await f.put({...c,ended:true},variant==='import'?'0':'1');
    assert.equal(await f.jobs.claim(f.user.id),null); assert.equal((await f.rows()).length,0);
  }
});
test('manual metadata, source, language, folder and deletion changes cancel late output',async t=>{
  for(const variant of ['metadata','source','language','folder','delete']) {
    const f=await fixture(t); await f.end(); const job=await f.jobs.claim(f.user.id);
    const c=(await f.read()).archive.conversations[0];
    if(variant==='folder') await f.data.mutate(f.user.id,{deviceId:'test',mutationId:randomUUID(),entity:'folder',id:'folder',baseVersion:'0',value:{id:'folder',name:'학교'}});
    else await f.put(variant==='delete'?null:variant==='metadata'?{...c,title:'내 제목',summary:'직접 작성',metadataRevision:1}:
      variant==='language'?{...c,first:'EN',second:'KO'}:{...c,utterances:[{...c.utterances[0],text:'전달을 취소합니다.',revision:2}]},'2');
    await f.jobs.complete(job,result);
    assert.equal((await f.rows())[0].state,'cancelled');
    const saved=(await f.read()).archive.conversations[0];
    if(variant==='delete') assert.equal(saved,undefined);
    else { assert.notEqual(saved.title,result.title); assert.equal(saved.organizationAttempted,true); }
  }
});
test('restart reclaims expired lease; old worker cannot publish or clear new lease',async t=>{
  const f=await fixture(t); await f.end(); const old=await f.jobs.claim(f.user.id);
  await f.db.forUser(f.user.id,c=>c.query("UPDATE ai_jobs SET lease_until=now()-interval '1 second'"));
  const restarted=createOrganizationJobs(f.db),job=await restarted.claim(f.user.id);
  assert.notEqual(job.lease_token,old.lease_token); assert.equal(job.attempts,2);
  await f.jobs.complete(old,result); await f.jobs.release(old);
  assert.equal((await f.rows())[0].state,'running');
  await restarted.complete(job,result); assert.equal((await f.rows())[0].state,'succeeded');
});
test('temporary errors retry with bounded attempts; permanent errors are sanitized and final',async t=>{
  const f=await fixture(t); await f.end();
  for(let attempt=1;attempt<=3;attempt++) {
    const job=await f.jobs.claim(f.user.id); assert.equal(job.attempts,attempt);
    await f.jobs.fail(job,{code:'runtime_offline',message:'never store raw error or credentials'});
    if(attempt<3) { assert.equal(await f.jobs.claim(f.user.id),null); await f.db.forUser(f.user.id,c=>c.query("UPDATE ai_jobs SET available_at=now()")); }
  }
  assert.equal((await f.rows())[0].state,'failed'); assert.equal((await f.rows())[0].error_code,'runtime_offline');
  assert.equal((await f.read()).archive.conversations[0].organizationAttempted,true);
  assert.equal(await f.jobs.claim(f.user.id),null);
  const g=await fixture(t); await g.end(); const job=await g.jobs.claim(g.user.id);
  await g.jobs.fail(job,new Error('secret raw failure')); assert.equal((await g.rows())[0].error_code,'organization_failed');
});
test('release on shutdown preserves queued job; invalid model output cannot be stored',async t=>{
  const f=await fixture(t); await f.end(); const job=await f.jobs.claim(f.user.id); await f.jobs.release(job);
  assert.equal((await f.rows())[0].state,'pending');
  const next=await f.jobs.claim(f.user.id);
  await assert.rejects(f.jobs.complete(next,{...result,folderId:'foreign-folder'}),{code:'invalid_model_output'});
  await f.jobs.fail(next,{code:'invalid_model_output'});
  assert.equal((await f.rows())[0].state,'failed'); assert.equal((await f.read()).archive.conversations[0].summary,null);
});
test('account isolation also applies to jobs and their lease completion',async t=>{
  const a=await fixture(t),b=await fixture(t); await a.end();
  assert.equal(await b.jobs.claim(b.user.id),null); assert.deepEqual((await b.read()).organizationJobs,{});
  const job=await a.jobs.claim(a.user.id); await b.jobs.complete({...job,user_id:b.user.id},result);
  assert.equal((await a.rows())[0].state,'running');
  await a.jobs.complete(job,result); assert.equal((await a.rows())[0].state,'succeeded');
});
test('stale queued work is cancelled before inference and repeated crash leases eventually fail',async t=>{
  const f=await fixture(t); await f.end(); const c=(await f.read()).archive.conversations[0];
  await f.put({...c,title:'직접 정리',metadataRevision:1},'2');
  assert.equal(await f.jobs.claim(f.user.id),null); assert.equal((await f.rows())[0].state,'cancelled');
  const g=await fixture(t); await g.end();
  for(let i=0;i<3;i++) { assert.ok(await g.jobs.claim(g.user.id)); await g.db.forUser(g.user.id,c=>c.query("UPDATE ai_jobs SET lease_until=now()-interval '1 second'")); }
  assert.equal(await g.jobs.claim(g.user.id),null); assert.equal((await g.rows())[0].state,'failed');
});
