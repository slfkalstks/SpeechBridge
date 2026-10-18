import {createHash} from 'node:crypto';
import {createReadStream} from 'node:fs';
import {lstat,realpath,readdir,readFile,rm,mkdir,writeFile} from 'node:fs/promises';
import {resolve,join,dirname,basename} from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../../.runtime/backups/',import.meta.url));
export const RETENTION_DAYS=30;
export async function pruneBackups({directory=root,now=Date.now(),apply=false}={}) {
 await mkdir(root,{recursive:true,mode:0o700});
 const base=await realpath(root),dir=await realpath(directory);
 if(base!==resolve(root))throw new Error('Project backup directory cannot traverse symlinks');
 if(dir!==base && !(dir.startsWith(base+'/retention-test-') && !dir.slice(base.length+1).includes('/')))throw new Error('Only project backup archives may be pruned');
 if((await lstat(directory)).isSymbolicLink())throw new Error('Backup directory must not be a symlink');
 const candidates=[];
 for(const name of (await readdir(dir)).filter(n=>n.endsWith('.dump') || n.endsWith('.sqlite'))) {
  const path=join(dir,name);
  let meta=path+'.json';try{await lstat(meta);}catch(error){if(error.code!=='ENOENT')throw error;meta=path+'.retention.json';}
  if(!name.startsWith('accounts-before-postgres-') && !name.startsWith('speechbridge-') && !name.startsWith('speechbridge_test-') && !/^speechbridge_restore_[a-f0-9]{24}-/.test(name))throw new Error('Unknown backup archive; preserved');
  for(const file of [path,meta]){const stat=await lstat(file);if(!stat.isFile() || stat.isSymbolicLink())throw new Error('Backup must be a regular project file');}
  const manifest=JSON.parse(await readFile(meta,'utf8')),created=Date.parse(manifest.createdAt);
  if(manifest.version!==1 || (meta.endsWith('.retention.json') && manifest.kind!=='legacy-copy') || !/^speechbridge(?:_test|_restore_[a-f0-9]{24})?$/.test(manifest.source) || !Number.isFinite(created) || created>now || !/^[a-f0-9]{64}$/.test(manifest.sha256))throw new Error('Invalid backup metadata; preserved');
  if(now-created<RETENTION_DAYS*86400000)continue;
  const hash=createHash('sha256');for await(const bytes of createReadStream(path))hash.update(bytes);
  if(hash.digest('hex')!==manifest.sha256)throw new Error('Backup checksum mismatch; preserved');
  candidates.push([path,meta]);
 }
 // Validate the entire inventory before deleting any files.
 if(apply)for(const [path,meta] of candidates){await rm(path);await rm(meta);}
 return {retentionDays:RETENTION_DAYS,expired:candidates.length,removed:apply?candidates.length:0};
}
/** Explicit adoption of legacy backup copies only; original databases are outside this directory. */
export async function registerLegacyBackup(input) {
 const path=resolve(input),base=await realpath(root),dir=await realpath(dirname(path));
 if(base!==resolve(root) || (dir!==base && !(dir.startsWith(base+'/retention-test-') && !dir.slice(base.length+1).includes('/'))))throw Error('Only project backup copies may be registered');
 const info=await lstat(path),name=basename(path);
 if(!info.isFile() || info.isSymbolicLink() || await realpath(path)!==path)throw Error('Regular backup copy required');
 if(/^speechbridge-.*\.dump$/.test(name)) {
  await promisify(execFile)(fileURLToPath(new URL('../../.runtime/postgres/install/bin/pg_restore',import.meta.url)),['--list',path],{maxBuffer:2*1024*1024});
 }else if(/^accounts-before-postgres-.*\.sqlite$/.test(name)) {
  const {DatabaseSync}=await import('node:sqlite');const db=new DatabaseSync(path,{readOnly:true});
  try{if(db.prepare('PRAGMA quick_check').get().quick_check!=='ok')throw Error('Invalid legacy backup');}finally{db.close();}
 }else throw Error('Unknown legacy backup name');
 const hash=createHash('sha256');for await(const bytes of createReadStream(path))hash.update(bytes);
 const manifest={version:1,kind:'legacy-copy',source:'speechbridge',createdAt:info.mtime.toISOString(),sha256:hash.digest('hex')};
 await writeFile(path+'.retention.json',JSON.stringify(manifest,null,2)+'\n',{flag:'wx',mode:0o600});
 return {registered:true};
}
export async function maintenance({backup,now=Date.now()}={}) {
 const report=await pruneBackups({now,apply:true});
 const status=new URL('../../.runtime/postgres/maintenance.json',import.meta.url);
 let previous;try{previous=JSON.parse(await readFile(status,'utf8'));}catch{}
 if(!previous?.backupAt || now-Date.parse(previous.backupAt)>=86400000) {
  await backup();report.backupAt=new Date(now).toISOString();
 }else report.backupAt=previous.backupAt;
 report.checkedAt=new Date(now).toISOString();await writeFile(status,JSON.stringify(report,null,2)+'\n',{mode:0o600});return report;
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 try{console.log(JSON.stringify(process.argv[2]==='adopt' ? await registerLegacyBackup(process.argv[3]) : await pruneBackups({apply:process.argv.includes('--apply')})));}
 catch{console.error('Backup retention failed; unknown or damaged files were preserved. Inspect the project backup inventory.');process.exitCode=1;}
}
