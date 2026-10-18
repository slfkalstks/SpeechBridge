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
 await assert.rejects(openAdmin(source.database));
 assert.equal((await activateRestoredDatabase({source:source.database,target:target.database,confirm:source.database})).alreadyActive,true);
 // The original shared test DB was never frozen.
 await db.ready();
});
