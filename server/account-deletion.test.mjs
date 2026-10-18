import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { rm } from 'node:fs/promises';
import { createDatabase } from './db/database.mjs';
import { createPostgresAccounts } from './db/accounts.mjs';
import { createUserData } from './db/user-data.mjs';
import { createServer } from './server.mjs';
import { seedDemo } from './demo.mjs';
import { createBackup,restoreBackup,dropRestoredDatabase } from './db/backup.mjs';
process.loadEnvFile(new URL('../.runtime/postgres/test.env',import.meta.url));
assert.equal(process.env.PGDATABASE,'speechbridge_test');
const password='Synthetic-deletion-test-123!';
async function fixture(t) {
  const db=createDatabase(process.env),accounts=createPostgresAccounts({db}),data=createUserData(db),users=[];
  t.after(async()=>{for(const id of users)await db.auth.query('DELETE FROM users WHERE id=$1',[id]);await db.close();});
  const register=async()=>{const session=await accounts.handle('/auth/register',{username:'del_'+randomUUID().replaceAll('-','').slice(0,20),password});users.push(session.user.id);return session;};
  const session=await register();
  const post=(path,body)=>path==='/data/snapshot'?data.snapshot(session.user.id,body.deviceId):data.mutate(session.user.id,body);
  await seedDemo(post);
  return {db,accounts,data,register,session,post};
}
test('deletion requires current password, authenticated identity and explicit confirmation; removes only that account',async t=>{
  const {db,accounts,data,register,session}=await fixture(t);
  const other=await register(),second=await accounts.handle('/auth/login',{username:session.user.username,password});
  const header=`Bearer ${session.token}`,body={username:session.user.username,password,confirm:true};
  const put=(entity,id,value,baseVersion='0')=>data.mutate(session.user.id,{deviceId:'deletion-device',mutationId:randomUUID(),entity,id,value,baseVersion});
  await put('settings','settings',{appearance:'DARK'});
  await put('draft','new',{text:'삭제할 전송 대기 초안',language:'KO',first:'KO',second:'EN'});
  const live=(await data.snapshot(session.user.id,'verify')).archive.conversations.find(c=>c.id==='demo-live');
  await put('conversation',live.id,{...live,ended:true},'1');
  const tables=['user_settings','devices','work_folders','conversations','utterances','utterance_revisions','translations','action_items','conversation_drafts','ai_jobs','sync_heads','sync_changes','sync_receipts'];
  for(const table of tables)assert.ok((await db.forUser(session.user.id,c=>c.query(`SELECT * FROM ${table}`))).rowCount>0,`${table} must contain synthetic data before deletion`);
  await assert.rejects(accounts.handle('/auth/delete',{...body,confirm:false},header),{status:400});
  await assert.rejects(accounts.handle('/auth/delete',{...body,password:'incorrect-password-123!'},header),{status:403});
  await assert.rejects(accounts.handle('/auth/delete',{...body,username:other.user.username},header),{status:400});
  await assert.rejects(accounts.handle('/auth/delete',body,'Bearer '+'a'.repeat(43)),{status:401});
  assert.equal((await data.snapshot(session.user.id,'verify')).archive.conversations.length,3);
  assert.deepEqual(await accounts.handle('/auth/delete',body,header),{deleted:true});
  assert.deepEqual(await accounts.handle('/auth/delete',body,header),{deleted:true},'lost acknowledgement may be retried');
  for(const token of [session.token,second.token])await assert.rejects(accounts.authenticate(`Bearer ${token}`),{status:401});
  await assert.rejects(accounts.handle('/auth/login',{username:session.user.username,password}),{status:401});
  assert.equal((await accounts.authenticate(`Bearer ${other.token}`)).id,other.user.id);
  for(const table of tables)
    assert.equal((await db.forUser(session.user.id,c=>c.query(`SELECT * FROM ${table}`))).rowCount,0,table);
  const hash=createHash('sha256').update(session.user.id).digest('hex');
  assert.equal((await db.auth.query('SELECT user_id_hash FROM account_deletions WHERE user_id_hash=$1',[hash])).rowCount,1);
  await assert.rejects(db.data.query('SELECT * FROM account_deletions'),{code:'42501'});
  await assert.rejects(db.auth.query('DELETE FROM account_deletions WHERE user_id_hash=$1',[hash]),{code:'42501'});
});
test('member HTTP exposes deletion with small JSON limit and rejects a logged-out token',async t=>{
  const {db,accounts,session}=await fixture(t);
  const server=createServer({env:{AUTH_MODE:'users'},database:db,accounts});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));});
  const url=`http://127.0.0.1:${server.address().port}/auth/delete`;
  const body={username:session.user.username,password,confirm:true};
  const send=(value,token=session.token)=>fetch(url,{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify(value)});
  assert.equal((await send({...body,extra:'x'.repeat(3000)})).status,400);
  await accounts.handle('/auth/logout',{},`Bearer ${session.token}`);
  assert.equal((await send(body)).status,401);
  const next=await accounts.handle('/auth/login',{username:session.user.username,password});
  const erased=await send(body,next.token);
  assert.equal(erased.status,200);assert.deepEqual(await erased.json(),{deleted:true});
});
test('restoring an older dump reapplies later account deletions instead of restoring passwords and history',async t=>{
  const {db,accounts,session}=await fixture(t);let backup,restored,recovered;
  t.after(async()=>{await recovered?.close();if(restored)await dropRestoredDatabase(restored.database);if(backup){await rm(backup.path);await rm(backup.path+'.json');}});
  backup=await createBackup('speechbridge_test');
  await accounts.handle('/auth/delete',{username:session.user.username,password,confirm:true},`Bearer ${session.token}`);
  restored=await restoreBackup(backup.path);
  assert.ok(restored.deletedAccountsRemoved>=1);
  recovered=createDatabase({...process.env,PGDATABASE:restored.database});
  await assert.rejects(recovered.ready(),/activation/,'restored snapshots must not serve users before cutover reconciliation');
  assert.equal((await recovered.auth.query('SELECT id FROM users WHERE id=$1',[session.user.id])).rowCount,0);
  assert.equal((await recovered.forUser(session.user.id,c=>c.query('SELECT * FROM conversations'))).rowCount,0);
  await assert.rejects(createPostgresAccounts({db:recovered}).handle('/auth/login',{username:session.user.username,password}),{status:401});
});

test('login hashing that began before deletion cannot issue a session afterwards',async t=>{
  const {db,accounts,session}=await fixture(t);
  const reached=Promise.withResolvers(),resume=Promise.withResolvers();
  const delayed=createPostgresAccounts({db:{...db,auth:{connect:()=>db.auth.connect(),query:async(sql,params)=>{
    const result=await db.auth.query(sql,params);
    if(sql==='SELECT * FROM users WHERE username=$1'){reached.resolve();await resume.promise;}
    return result;
  }}}});
  const login=assert.rejects(delayed.handle('/auth/login',{username:session.user.username,password}),{status:401});
  await reached.promise;
  try {await accounts.handle('/auth/delete',{username:session.user.username,password,confirm:true},`Bearer ${session.token}`);}
  finally{resume.resolve();}
  await login;
  assert.equal((await db.auth.query('SELECT * FROM sessions WHERE user_id=$1',[session.user.id])).rowCount,0);
});

test('logout during deletion reauthentication prevents erasure and preserves all records',async t=>{
  const {db,accounts,data,session}=await fixture(t);
  const reached=Promise.withResolvers(),resume=Promise.withResolvers();
  const delayed=createPostgresAccounts({db:{...db,auth:{query:(...args)=>db.auth.query(...args),connect:async()=>{
    const client=await db.auth.connect();return {release:()=>client.release(),query:async(sql,params)=>{
      const result=await client.query(sql,params);
      if(sql==='SELECT * FROM users WHERE id=$1 FOR UPDATE'){reached.resolve();await resume.promise;}
      return result;
    }};
  }}}});
  const erase=assert.rejects(delayed.handle('/auth/delete',{username:session.user.username,password,confirm:true},`Bearer ${session.token}`),{status:401});
  await reached.promise;
  try{await accounts.handle('/auth/logout',{},`Bearer ${session.token}`);}finally{resume.resolve();}
  await erase;
  assert.equal((await data.snapshot(session.user.id,'still-here')).archive.conversations.length,3);
});
