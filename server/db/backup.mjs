import pg from 'pg';
import { createHash, randomBytes } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { chmod, mkdir, readFile, realpath, rm, stat, writeFile } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { activeDatabase } from './target.mjs';
import { migrate } from './migrate.mjs';

const execute = promisify(execFile);
const root = fileURLToPath(new URL('../../.runtime/postgres/',import.meta.url));
const backups = fileURLToPath(new URL('../../.runtime/backups/',import.meta.url));
const restoredName = /^speechbridge_restore_[a-f0-9]{24}$/;
const identifier = value => '"'+value.replaceAll('"','""')+'"';
async function configuration(database='postgres') {
  const secrets = JSON.parse(await readFile(join(root,'credentials.json'),'utf8'));
  return {host:join(root,'socket'),port:5433,user:'speechbridge_admin',password:secrets.admin,database,connectionTimeoutMillis:5000};
}
async function client(database) {
  const db = new pg.Client(await configuration(database));
  db.on('error',()=>{});
  await db.connect(); return db;
}
async function command(name,args,database) {
  const config = await configuration(database);
  try {
    await execute(join(root,'install/bin',name),['-h',config.host,'-p','5433','-U',config.user,...args],{
      env:{PATH:'/usr/bin:/bin',TMPDIR:join(root,'tmp'),PGPASSWORD:config.password},timeout:300_000,maxBuffer:1024*1024,
    });
  } catch { throw new Error(`Project ${name} failed; source database was not replaced`); }
}
async function hashFile(path) {
  const hash = createHash('sha256');
  for await(const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest('hex');
}
/** Must run inside a repeatable-read transaction. No user contents leave the process. */
async function fingerprint(db) {
  const tables = (await db.query(`SELECT c.relname AS name,r.rolname AS owner,c.relrowsecurity AS rls,c.relforcerowsecurity AS force_rls
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.oid=c.relowner
    WHERE n.nspname='public' AND c.relkind='r' ORDER BY c.relname`)).rows;
  const contents = {};
  for(const table of tables) {
    const hash = createHash('sha256'); let count = 0;
    await db.query(`DECLARE backup_rows NO SCROLL CURSOR FOR SELECT to_jsonb(t)::text AS payload FROM public.${identifier(table.name)} t ORDER BY to_jsonb(t)::text`);
    try {
      while(true) {
        const {rows} = await db.query('FETCH FORWARD 1000 FROM backup_rows');
        if(!rows.length) break;
        for(const row of rows) { hash.update(row.payload+'\n'); count++; }
      }
    } finally { await db.query('CLOSE backup_rows'); }
    contents[table.name] = {count,sha256:hash.digest('hex')};
  }
  const policies = (await db.query("SELECT tablename,policyname,permissive,roles,cmd,qual,with_check FROM pg_policies WHERE schemaname='public' ORDER BY tablename,policyname")).rows;
  const grants = (await db.query("SELECT table_name,grantee,privilege_type,is_grantable FROM information_schema.role_table_grants WHERE table_schema='public' ORDER BY table_name,grantee,privilege_type")).rows;
  const constraints = (await db.query("SELECT cl.relname,con.conname,pg_get_constraintdef(con.oid) AS definition FROM pg_constraint con JOIN pg_class cl ON cl.oid=con.conrelid JOIN pg_namespace n ON n.oid=cl.relnamespace WHERE n.nspname='public' ORDER BY cl.relname,con.conname")).rows;
  return {tables,contents,policies,grants,constraints};
}

export async function createBackup(database) {
  database ??= await activeDatabase();
  if(!['speechbridge','speechbridge_test'].includes(database) && !restoredName.test(database)) throw new Error('Only project databases may be backed up');
  await mkdir(backups,{recursive:true,mode:0o700}); await chmod(backups,0o700);
  const path = join(backups,`${database}-${new Date().toISOString().replaceAll(':','-')}-${randomBytes(6).toString('hex')}.dump`);
  const db = await client(database);
  let created = false;
  try {
    await db.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
    await db.query("SET LOCAL TIME ZONE 'UTC'");
    const snapshot = (await db.query('SELECT pg_export_snapshot() AS id')).rows[0].id;
    const expected = await fingerprint(db);
    await writeFile(path,'',{flag:'wx',mode:0o600}); created = true;
    await command('pg_dump',['-d',database,'-Fc','--snapshot',snapshot,'-f',path],database);
    const manifest = {version:1,createdAt:new Date().toISOString(),source:database,sha256:await hashFile(path),expected};
    await writeFile(path+'.json',JSON.stringify(manifest,null,2)+'\n',{flag:'wx',mode:0o600});
    await db.query('COMMIT');
    return {path,tables:expected.tables.length};
  } catch(error) {
    if(created) { await rm(path,{force:true}); await rm(path+'.json',{force:true}); }
    throw error;
  } finally { await db.end(); }
}

export async function dropRestoredDatabase(database) {
  if(!restoredName.test(database)) throw new Error('Only generated restore databases may be removed');
  const admin = await client('postgres');
  try { await admin.query(`DROP DATABASE IF EXISTS ${identifier(database)}`); }
  finally { await admin.end(); }
}

/** Always restores into a new DB. Never accepts a destination DB or overwrites a live one. */
export async function restoreBackup(input) {
  const path = await realpath(resolve(input));
  if(dirname(path)!==await realpath(backups) || !path.endsWith('.dump') || !(await stat(path)).isFile()) throw new Error('Backup must be a project archive');
  const manifest = JSON.parse(await readFile(path+'.json','utf8'));
  if(manifest.version!==1 || manifest.sha256!==await hashFile(path)) throw new Error('Backup checksum does not match');
  if(!['speechbridge','speechbridge_test'].includes(manifest.source) && !restoredName.test(manifest.source))throw new Error('Invalid project backup source');
  // Fail closed when the live deletion ledger is unavailable. An old archive alone
  // cannot prove that its users have not since requested erasure.
  const source=await client(manifest.source);
  const database = 'speechbridge_restore_'+randomBytes(12).toString('hex');
  const admin = await client('postgres');
  let created = false, restored;
  try {
    await admin.query(`CREATE DATABASE ${identifier(database)} OWNER speechbridge_owner TEMPLATE template0`); created = true;
    await admin.query(`REVOKE ALL ON DATABASE ${identifier(database)} FROM PUBLIC`);
    await admin.query(`GRANT CONNECT ON DATABASE ${identifier(database)} TO speechbridge_auth,speechbridge_app`);
    await command('pg_restore',['-d',database,'--exit-on-error','--single-transaction',path],database);
    restored = await client(database);
    await restored.query('BEGIN ISOLATION LEVEL REPEATABLE READ');
    await restored.query("SET LOCAL TIME ZONE 'UTC'");
    const actual = await fingerprint(restored);
    if(!isDeepStrictEqual(actual,manifest.expected)) throw new Error('Restored data or ownership policies do not match the backup');
    await restored.query('COMMIT');
    const migrationPool=new pg.Pool(await configuration(database));
    try {await migrate(migrationPool);}finally{await migrationPool.end();}
    const ledger=(await source.query('SELECT * FROM account_deletions')).rows;
    await restored.query('BEGIN');
    for(const row of ledger)await restored.query('INSERT INTO account_deletions(user_id_hash,username_hash,receipt_hash,deleted_at) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING',
      [row.user_id_hash,row.username_hash,row.receipt_hash,row.deleted_at]);
    const deleted=new Set((await restored.query('SELECT user_id_hash FROM account_deletions')).rows.map(row=>row.user_id_hash));
    let deletedAccountsRemoved=0;
    for(const user of (await restored.query('SELECT id FROM users')).rows) {
      if(deleted.has(createHash('sha256').update(user.id).digest('hex')))
        deletedAccountsRemoved+=(await restored.query('DELETE FROM users WHERE id=$1',[user.id])).rowCount;
    }
    // An old backup can contain a session revoked after it was created. Force fresh login.
    const revoked = (await restored.query('DELETE FROM sessions')).rowCount;
    await restored.query('COMMIT');
    return {database,verified:true,tables:actual.tables.length,sessionsRevoked:revoked,deletedAccountsRemoved,activationRequired:true};
  } catch(error) {
    if(restored) { await restored.end(); restored = null; }
    if(created) await admin.query(`DROP DATABASE ${identifier(database)}`);
    throw error;
  } finally { await restored?.end(); await admin.end(); await source.end(); }
}

export async function verifyBackup(path) {
  const result = await restoreBackup(path);
  await dropRestoredDatabase(result.database);
  return {verified:result.verified,tables:result.tables,sessionsRevoked:result.sessionsRevoked,temporaryDatabaseRemoved:true};
}

if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const [action,path] = process.argv.slice(2);
    if(action==='backup') console.log(JSON.stringify(await createBackup(path||undefined)));
    else if(action==='verify' && path) console.log(JSON.stringify(await verifyBackup(path)));
    else if(action==='restore' && path) console.log(JSON.stringify(await restoreBackup(path)));
    else throw new Error('Use backup, verify <project dump>, or restore <project dump>');
  } catch { console.error('Project backup operation failed; the source database was not replaced.'); process.exitCode=1; }
}
