import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,rm,symlink} from 'node:fs/promises';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {pruneBackups} from './db/retention.mjs';
const root=new URL('../.runtime/backups/',import.meta.url).pathname;
test('retention previews then removes only verified expired project dump pairs; preserves fresh and unknown files',async t=>{
 const dir=await mkdtemp(join(root,'retention-test-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const now=Date.now(),bytes=Buffer.from('synthetic backup');
 for(const [name,days] of [['old',31],['fresh',1]]){
  const path=join(dir,'speechbridge-'+name+'.dump');await writeFile(path,bytes);
  await writeFile(path+'.json',JSON.stringify({version:1,source:'speechbridge',createdAt:new Date(now-days*86400000).toISOString(),sha256:createHash('sha256').update(bytes).digest('hex')}));
 }
 await writeFile(join(dir,'preserve.txt'),'legacy');
 const preview=await pruneBackups({directory:dir,now});assert.equal(preview.expired,1);assert.equal(preview.removed,0);
 const done=await pruneBackups({directory:dir,now,apply:true});assert.equal(done.removed,1);
 assert.equal((await readFile(join(dir,'speechbridge-fresh.dump'))).toString(),'synthetic backup');
 assert.equal((await readFile(join(dir,'preserve.txt'))).toString(),'legacy');
 assert.equal((await pruneBackups({directory:dir,now,apply:true})).removed,0);
});
test('retention refuses symlinks, corrupt metadata and paths outside the project backup directory',async t=>{
 const dir=await mkdtemp(join(root,'retention-test-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 await symlink('../',''+join(dir,'speechbridge-bad.dump'));
 await assert.rejects(pruneBackups({directory:dir,apply:true}));
 await rm(join(dir,'speechbridge-bad.dump'));await writeFile(join(dir,'speechbridge-bad.dump'),'x');
 await writeFile(join(dir,'speechbridge-bad.dump.json'),'{}');
 await assert.rejects(pruneBackups({directory:dir,apply:true}));
 await assert.rejects(pruneBackups({directory:'/tmp',apply:true}));
});

test('explicitly registered legacy backup copies follow the same bounded retention without modifying originals',async t=>{
 const {registerLegacyBackup}=await import('./db/retention.mjs');
 const dir=await mkdtemp(join(root,'retention-test-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const path=join(dir,'accounts-before-postgres-synthetic.sqlite');
 const {DatabaseSync}=await import('node:sqlite');const db=new DatabaseSync(path);db.exec('CREATE TABLE users(id TEXT)');db.close();
 const before=await readFile(path);await registerLegacyBackup(path);assert.deepEqual(await readFile(path),before);
 const manifest=JSON.parse(await readFile(path+'.retention.json','utf8'));assert.equal(manifest.kind,'legacy-copy');
 const result=await pruneBackups({directory:dir,now:Date.now()+31*86400000,apply:true});assert.equal(result.removed,1);
 await assert.rejects(readFile(path));
});
