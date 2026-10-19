import pg from 'pg';
import {createHash,randomUUID} from 'node:crypto';
import {readFile,writeFile,rename,rm} from 'node:fs/promises';
import {parseEnv} from 'node:util';
import {fileURLToPath} from 'node:url';
import {resolve,join} from 'node:path';
const root=fileURLToPath(new URL('../../.runtime/postgres/',import.meta.url));
const restored=/^speechbridge_restore_[a-f0-9]{24}$/;
const ident=value=>'"'+value.replaceAll('"','""')+'"';
export async function openAdmin(database='postgres') {
 if(!['postgres','speechbridge','speechbridge_test'].includes(database) && !restored.test(database))throw Error('Not a project database');
 const credentials=JSON.parse(await readFile(join(root,'credentials.json'),'utf8'));
 const client=new pg.Client({host:join(root,'socket'),port:5433,user:'speechbridge_admin',password:credentials.admin,database,connectionTimeoutMillis:5000});
 client.on('error',()=>{});try{await client.connect();return client;}catch(error){await client.end();throw error;}
}
async function reconcile(source,target) {
 await source.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
 try {
  for(const row of (await source.query('SELECT * FROM account_deletions')).rows)
   await target.query('INSERT INTO account_deletions(user_id_hash,username_hash,receipt_hash,deleted_at) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING',[row.user_id_hash,row.username_hash,row.receipt_hash,row.deleted_at]);
  for(const row of (await source.query('SELECT * FROM account_deletion_sessions')).rows)
   await target.query('INSERT INTO account_deletion_sessions(token_hash,user_id_hash) VALUES($1,$2) ON CONFLICT DO NOTHING',[row.token_hash,row.user_id_hash]);
  await source.query('COMMIT');
 }catch(error){await source.query('ROLLBACK');throw error;}
 const deleted=new Set((await target.query('SELECT user_id_hash FROM account_deletions')).rows.map(x=>x.user_id_hash));
 let removed=0;
 for(const row of (await target.query('SELECT id FROM users')).rows)
  if(deleted.has(createHash('sha256').update(row.id).digest('hex')))removed+=(await target.query('DELETE FROM users WHERE id=$1',[row.id])).rowCount;
 await target.query('DELETE FROM sessions');return removed;
}
// No caller-selected arbitrary database. Source and target must match verified restore provenance.
export async function activateRestoredDatabase({source,target,confirm,beforeCommit}={}) {
 if(!(source==='speechbridge' || restored.test(source)) || !restored.test(target) || source===target || confirm!==source)throw Error('Explicit project source confirmation is required');
 const admin=await openAdmin();let old,next,frozen=false,committed=false,wasFrozen=false,targetFrozen=false;
 try {
  await admin.query('SELECT pg_advisory_lock(83917403)');
  // A prior completed activation can be retried without reopening its retired source.
  const existing=await admin.query('SELECT 1 FROM pg_database WHERE datname=$1',[target]);
  if(!existing.rowCount)throw Error('Restore target missing');
  next=await openAdmin(target);
  const state=(await next.query('SELECT * FROM restore_activation WHERE singleton')).rows[0];
  if(!state || state.retired_to || state.source_database!==source)throw Error('Verified restore source does not match');
  if(state.activated) {
   const status=(await admin.query("SELECT has_database_privilege('speechbridge_auth',$1,'CONNECT') OR has_database_privilege('speechbridge_app',$1,'CONNECT') AS connected",[source])).rows[0];
   if(status?.connected!==false)throw Error('Retired source must remain disconnected');
   await admin.query(`GRANT CONNECT ON DATABASE ${ident(target)} TO speechbridge_auth,speechbridge_app`);
   return {activated:true,alreadyActive:true,source,target};
  }
  old=await openAdmin(source);
  wasFrozen=!(await admin.query("SELECT has_database_privilege('speechbridge_auth',$1,'CONNECT') OR has_database_privilege('speechbridge_app',$1,'CONNECT') AS connected",[source])).rows[0].connected;
  const sourceState=(await old.query('SELECT retired_to FROM restore_activation WHERE singleton')).rows[0];
  if(sourceState?.retired_to && sourceState.retired_to!==target)throw Error('Source is retired to another target');
  // Retire application access at the DB boundary. Keep admin recovery possible after a crash.
  await admin.query(`REVOKE CONNECT ON DATABASE ${ident(source)} FROM PUBLIC,speechbridge_auth,speechbridge_app`);frozen=true;
  await old.query('UPDATE restore_activation SET retired_to=$1 WHERE singleton',[target]);
  await admin.query('SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=$1 AND pid<>$2',[source,old.processID]);
  await admin.query(`REVOKE CONNECT ON DATABASE ${ident(target)} FROM PUBLIC,speechbridge_auth,speechbridge_app`);targetFrozen=true;
  await admin.query('SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname=$1 AND pid<>$2',[target,next.processID]);
  await next.query('BEGIN');
  const deletedAccountsRemoved=await reconcile(old,next);
  await beforeCommit?.();
  await next.query('UPDATE restore_activation SET activated=true,activated_at=now() WHERE singleton');
  await next.query('COMMIT');committed=true;
  await admin.query(`GRANT CONNECT ON DATABASE ${ident(target)} TO speechbridge_auth,speechbridge_app`);
  return {activated:true,source,target,deletedAccountsRemoved};
 }catch(error){
  await next?.query('ROLLBACK').catch(()=>{});
  if(!committed){
   if(frozen && !wasFrozen){
    await old.query('UPDATE restore_activation SET retired_to=NULL WHERE singleton AND retired_to=$1',[target]);
    await admin.query(`GRANT CONNECT ON DATABASE ${ident(source)} TO speechbridge_auth,speechbridge_app`);
   }
   if(targetFrozen)await admin.query(`GRANT CONNECT ON DATABASE ${ident(target)} TO speechbridge_auth,speechbridge_app`);
  }
  throw error;
 }finally{await old?.end();await next?.end();await admin.end();}
}
async function selectTarget(source,target) {
 const path=join(root,'server.env'),raw=await readFile(path,'utf8'),env=parseEnv(raw);
 if(env.PGDATABASE!==source && env.PGDATABASE!==target)throw Error('Active configuration changed; preserved');
 const changed=raw.replace(/^PGDATABASE=.*$/m,'PGDATABASE='+target);
 if(changed===raw && env.PGDATABASE!==target)throw Error('No active database field');
 const temp=path+'.cutover-'+randomUUID()+'.tmp';await writeFile(temp,changed,{flag:'wx',mode:0o600});
 try{await rename(temp,path);}finally{await rm(temp,{force:true});}
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
 try {
  const [source,target,flag,confirmation]=process.argv.slice(2);
  if(flag!=='--confirm-source')throw Error('Confirmation required');
  const env=parseEnv(await readFile(join(root,'server.env'),'utf8'));
  if(![source,target].includes(env.PGDATABASE))throw Error('Source is not configured');
  const result=await activateRestoredDatabase({source,target,confirm:confirmation});
  await selectTarget(source,target);console.log(JSON.stringify({...result,restartApiRequired:true}));
 }catch{console.error('Cutover did not finish. Do not enable both databases. Inspect restore activation and retry the identical source/target command; the retired source may already be frozen.');process.exitCode=1;}
}
