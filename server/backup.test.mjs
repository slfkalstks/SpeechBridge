import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { copyFile, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { createDatabase } from './db/database.mjs';
import { createPostgresAccounts } from './db/accounts.mjs';
import { createUserData } from './db/user-data.mjs';
import { createBackup, restoreBackup, dropRestoredDatabase } from './db/backup.mjs';

process.loadEnvFile(new URL('../.runtime/postgres/test.env',import.meta.url));
assert.equal(process.env.PGDATABASE,'speechbridge_test');

test('project backup restores actual data and RLS into a fresh DB without replacing the source', async t => {
  const db = createDatabase(process.env);
  const accounts = createPostgresAccounts({db});
  const username = `backup_${randomUUID().replaceAll('-','').slice(0,20)}`;
  const password = 'Synthetic-backup-password-123!';
  const session = await accounts.handle('/auth/register',{username,password});
  let backup, restored, recovered;
  t.after(async()=>{
    await recovered?.close();
    if(restored) await dropRestoredDatabase(restored.database);
    if(backup) await Promise.all([rm(backup.path),rm(backup.path+'.json')]);
    await db.auth.query('DELETE FROM users WHERE id=$1',[session.user.id]); await db.close();
  });
  const data = createUserData(db);
  const mutation = {deviceId:'backup_test',mutationId:randomUUID(),entity:'folder',id:'work',baseVersion:'0',value:{id:'work',name:'복원할 업무 폴더'}};
  await data.mutate(session.user.id,mutation);
  const before = await data.snapshot(session.user.id,'backup_test');
  backup = await createBackup('speechbridge_test');
  assert.equal((await stat(backup.path)).mode & 0o777,0o600);
  assert.equal((await stat(backup.path+'.json')).mode & 0o777,0o600);
  // A later source edit must not leak into the consistent backup snapshot.
  await data.mutate(session.user.id,{...mutation,mutationId:randomUUID(),baseVersion:'1',value:{id:'work',name:'백업 이후 변경'}});
  restored = await restoreBackup(backup.path);
  assert.match(restored.database,/^speechbridge_restore_[a-f0-9]{24}$/);
  assert.equal(restored.verified,true); assert.ok(restored.tables >= 10);
  recovered = createDatabase({...process.env,PGDATABASE:restored.database});
  const recoveredAccounts = createPostgresAccounts({db:recovered});
  // Restoring an old dump must not resurrect previously revoked bearer sessions.
  await assert.rejects(recoveredAccounts.authenticate(`Bearer ${session.token}`),{status:401});
  const login = await recoveredAccounts.handle('/auth/login',{username,password});
  assert.equal(login.user.id,session.user.id);
  const after = await createUserData(recovered).snapshot(session.user.id,'backup_test');
  assert.deepEqual(after,before);
  assert.equal((await recovered.data.query('SELECT * FROM work_folders')).rowCount,0);
  assert.equal((await recovered.forUser('someone_else',c=>c.query('SELECT * FROM work_folders'))).rowCount,0);
  assert.equal((await data.snapshot(session.user.id,'backup_test')).archive.folders[0].name,'백업 이후 변경');
  // Password and bearer token must never occur literally in the dump or verification metadata.
  for(const file of [backup.path,backup.path+'.json']) {
    const bytes = await readFile(file); assert.equal(bytes.includes(password),false); assert.equal(bytes.includes(session.token),false);
  }
});

test('backup tools reject foreign DB names, existing production targets and changed archives', async t => {
  await assert.rejects(createBackup('another_project'));
  await assert.rejects(dropRestoredDatabase('speechbridge'));
  await assert.rejects(dropRestoredDatabase('speechbridge_test'));
  await assert.rejects(restoreBackup(new URL('../README.md',import.meta.url).pathname));
  const backup = await createBackup('speechbridge_test');
  const copy = backup.path.replace('.dump','-corrupt.dump');
  t.after(async()=>{for(const file of [backup.path,backup.path+'.json',copy,copy+'.json']) await rm(file,{force:true});});
  await copyFile(backup.path+'.json',copy+'.json');
  await writeFile(copy,Buffer.concat([await readFile(backup.path),Buffer.from('corrupt')]),{mode:0o600});
  await assert.rejects(restoreBackup(copy),/checksum/i);
});
