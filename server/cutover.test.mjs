import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {rm} from 'node:fs/promises';
import {createDatabase} from './db/database.mjs';
import {createPostgresAccounts} from './db/accounts.mjs';
import {createBackup,restoreBackup,dropRestoredDatabase} from './db/backup.mjs';
import {activateRestoredDatabase,openAdmin} from './db/cutover.mjs';
process.loadEnvFile(new URL('../.runtime/postgres/test.env',import.meta.url));
assert.equal(process.env.PGDATABASE,'speechbridge_test');
test('cutover freezes only the source, reapplies later deletion, revokes sessions and activates verified target',async t=>{
 const db=createDatabase(process.env),password='Synthetic-cutover-password-123!';
 const a=createPostgresAccounts({db}),user=await a.handle('/auth/register',{username:'cut_'+randomUUID().replaceAll('-','').slice(0,20),password});
 const backups=[],restores=[];let old,next;
 t.after(async()=>{await old?.close();await next?.close();for(const name of restores.reverse())await dropRestoredDatabase(name);for(const path of backups){await rm(path);await rm(path+'.json');}await db.auth.query('DELETE FROM users WHERE id=$1',[user.user.id]);await db.close();});
 const first=await createBackup('speechbridge_test');backups.push(first.path);
 const source=await restoreBackup(first.path);restores.push(source.database);
 const second=await createBackup(source.database);backups.push(second.path);
 const target=await restoreBackup(second.path);restores.push(target.database);
 old=createDatabase({...process.env,PGDATABASE:source.database});next=createDatabase({...process.env,PGDATABASE:target.database});
 await assert.rejects(next.ready());
 const oldAccounts=createPostgresAccounts({db:old});const session=await oldAccounts.handle('/auth/login',{username:user.user.username,password});
 await oldAccounts.handle('/auth/delete',{username:user.user.username,password,confirm:true},'Bearer '+session.token);
 await assert.rejects(activateRestoredDatabase({source:source.database,target:target.database,confirm:'wrong'}));
 await assert.rejects(activateRestoredDatabase({source:source.database,target:target.database,confirm:source.database,beforeCommit:()=>{throw Error('synthetic interruption');}}));
 // Failure before activation restores source connectivity.
 const check=await openAdmin(source.database);await check.end();
 const result=await activateRestoredDatabase({source:source.database,target:target.database,confirm:source.database});
 assert.equal(result.activated,true);assert.ok(result.deletedAccountsRemoved>=1);
 await next.ready();
 assert.equal((await next.auth.query('SELECT id FROM users WHERE id=$1',[user.user.id])).rowCount,0);
 assert.equal((await next.auth.query('SELECT count(*)::int AS n FROM sessions')).rows[0].n,0);
 await assert.rejects(old.auth.query('SELECT 1'));
 const adminRecovery=await openAdmin(source.database);await adminRecovery.end();
 assert.equal((await activateRestoredDatabase({source:source.database,target:target.database,confirm:source.database})).alreadyActive,true);
 // A target retired by a later cutover must never be reopened by retrying an old command.
 const manager=await openAdmin();const targetAdmin=await openAdmin(target.database);
 try {
  await targetAdmin.query('UPDATE restore_activation SET retired_to=$1',['speechbridge_restore_000000000000000000000000']);
  await manager.query(`REVOKE CONNECT ON DATABASE "${target.database}" FROM PUBLIC,speechbridge_auth,speechbridge_app`);
  await assert.rejects(activateRestoredDatabase({source:source.database,target:target.database,confirm:source.database}));
  assert.equal((await manager.query("SELECT has_database_privilege('speechbridge_auth',$1,'CONNECT') AS connected",[target.database])).rows[0].connected,false);
 }finally{await manager.end();await targetAdmin.end();}
 // The original shared test DB was never frozen.
 await db.ready();
});

test('an interrupted cutover can be resumed after the process dies while source is frozen',async t=>{
 const {spawnSync}=await import('node:child_process');const backups=[],restores=[];
 t.after(async()=>{for(const name of restores.reverse())await dropRestoredDatabase(name);for(const path of backups){await rm(path);await rm(path+'.json');}});
 const a=await createBackup('speechbridge_test');backups.push(a.path);const source=await restoreBackup(a.path);restores.push(source.database);
 const b=await createBackup(source.database);backups.push(b.path);const target=await restoreBackup(b.path);restores.push(target.database);
 const script=`import {activateRestoredDatabase} from ${JSON.stringify(new URL('./db/cutover.mjs',import.meta.url).href)};await activateRestoredDatabase({source:${JSON.stringify(source.database)},target:${JSON.stringify(target.database)},confirm:${JSON.stringify(source.database)},beforeCommit:()=>process.kill(process.pid,'SIGKILL')});`;
 const child=spawnSync(process.execPath,['--input-type=module','-e',script],{timeout:10000});assert.equal(child.signal,'SIGKILL');
 const result=await activateRestoredDatabase({source:source.database,target:target.database,confirm:source.database});assert.equal(result.activated,true);
});
