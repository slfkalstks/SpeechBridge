import {createHash} from 'node:crypto';
import {createReadStream} from 'node:fs';
import {lstat,realpath,readdir,readFile,rm,mkdir,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
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
 for(const name of (await readdir(dir)).filter(n=>n.endsWith('.dump'))) {
  const path=join(dir,name),meta=path+'.json';
  if(!name.startsWith('speechbridge-') && !name.startsWith('speechbridge_test-') && !/^speechbridge_restore_[a-f0-9]{24}-/.test(name))throw new Error('Unknown backup archive; preserved');
  for(const file of [path,meta]){const stat=await lstat(file);if(!stat.isFile() || stat.isSymbolicLink())throw new Error('Backup must be a regular project file');}
  const manifest=JSON.parse(await readFile(meta,'utf8')),created=Date.parse(manifest.createdAt);
  if(manifest.version!==1 || !/^speechbridge(?:_test|_restore_[a-f0-9]{24})?$/.test(manifest.source) || !Number.isFinite(created) || created>now || !/^[a-f0-9]{64}$/.test(manifest.sha256))throw new Error('Invalid backup metadata; preserved');
  if(now-created<RETENTION_DAYS*86400000)continue;
  const hash=createHash('sha256');for await(const bytes of createReadStream(path))hash.update(bytes);
  if(hash.digest('hex')!==manifest.sha256)throw new Error('Backup checksum mismatch; preserved');
  candidates.push([path,meta]);
 }
 // Validate the entire inventory before deleting any files.
 if(apply)for(const [path,meta] of candidates){await rm(path);await rm(meta);}
 return {retentionDays:RETENTION_DAYS,expired:candidates.length,removed:apply?candidates.length:0};
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
 try{console.log(JSON.stringify(await pruneBackups({apply:process.argv.includes('--apply')})));}
 catch{console.error('Backup retention failed; unknown or damaged files were preserved. Inspect the project backup inventory.');process.exitCode=1;}
}
