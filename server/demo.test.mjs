import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createDatabase } from './db/database.mjs';
import { createPostgresAccounts } from './db/accounts.mjs';
import { createUserData } from './db/user-data.mjs';
import { seedDemo, assertProjectListeners } from './demo.mjs';

test('demo startup refuses listeners owned by other projects or exposed on another interface', () => {
  const root='/project/SpeechBridge';
  assert.doesNotThrow(()=>assertProjectListeners([{cwd:root+'/server',address:'127.0.0.1:8787'}],root,'127.0.0.1:8787'));
  assert.throws(()=>assertProjectListeners([{cwd:'/other',address:'127.0.0.1:8787'}],root,'127.0.0.1:8787'));
  assert.throws(()=>assertProjectListeners([{cwd:root+'/server',address:'*:8787'}],root,'127.0.0.1:8787'));
  assert.throws(()=>assertProjectListeners([{cwd:root+'-other',address:'127.0.0.1:8787'}],root,'127.0.0.1:8787'));
});

test('synthetic demo survives repeat preparation without overwriting edits, deleted records or other accounts', async t => {
  process.loadEnvFile(new URL('../.runtime/postgres/test.env',import.meta.url));
  assert.equal(process.env.PGDATABASE,'speechbridge_test');
  const db=createDatabase(process.env),accounts=createPostgresAccounts({db}),data=createUserData(db);
  const user=(await accounts.handle('/auth/register',{username:'demo_'+randomUUID().replaceAll('-','').slice(0,20),password:'Synthetic-demo-test-123!'})).user;
  t.after(async()=>{await db.auth.query('DELETE FROM users WHERE id=$1',[user.id]);await db.close();});
  const post=(path,body)=>path==='/data/snapshot'?data.snapshot(user.id,body.deviceId):data.mutate(user.id,body);
  await seedDemo(post);
  const first=await post('/data/snapshot',{deviceId:'demo'});
  assert.equal(first.archive.conversations.length,3);
  assert.equal(first.archive.folders.length,1);
  assert.equal(first.archive.conversations.filter(c=>!c.ended).length,1);
  assert.equal(first.archive.conversations.flatMap(c=>c.items).filter(i=>i.status==='TODO').length,1);
  const edited={...first.archive.conversations.find(c=>c.id==='demo-work'),title:'내가 수정한 시연 제목'};
  await data.mutate(user.id,{deviceId:'demo',entity:'conversation',id:edited.id,value:edited,baseVersion:'1',mutationId:randomUUID()});
  await data.mutate(user.id,{deviceId:'demo',entity:'conversation',id:'demo-daily',value:null,baseVersion:'1',mutationId:randomUUID()});
  await seedDemo(post);
  const last=await post('/data/snapshot',{deviceId:'demo'});
  assert.equal(last.archive.conversations.length,2);
  assert.equal(last.archive.conversations.find(c=>c.id===edited.id).title,edited.title);
  assert.equal(last.versions['conversation:demo-work'],'2');
});
