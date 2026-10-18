import { randomUUID } from 'node:crypto';
import { validateOrganizationResult } from '../organization.mjs';

const guard = (c, utterances, folders) => ({
  title:c.title, summary:c.summary ?? null, category:c.category ?? null, workType:c.workType ?? null, folderId:c.folderId ?? null,
  metadataRevision:c.metadataRevision, first:c.first, second:c.second,
  utterances:utterances.map(u=>({id:u.id,revision:u.revision,text:u.text,language:u.language})), folders,
});
const foldersFor = async (client,user) => (await client.query(
  'SELECT id,name FROM work_folders WHERE user_id=$1 AND deleted_at IS NULL ORDER BY id',[user])).rows;

/** Only a newly-ended, previously stored conversation schedules initial AI work.
 * Importing an already-ended archive never silently rewrites that archive. */
export async function enqueueOrganization(client,user,c,previous) {
  if (!previous || previous.ended || !c.ended || c.organized || c.organizationAttempted || !c.utterances.length) return;
  const folders = await foldersFor(client,user);
  const input = {utterances:c.utterances.map(({id,revision,text})=>({id,revision,text})),language:c.first.toLowerCase(),folders};
  await client.query(`INSERT INTO ai_jobs(user_id,id,conversation_id,kind,source_revisions,metadata_revision,state,input,guard)
    VALUES($1,$2,$3,'organize',$4,$5,'pending',$6,$7) ON CONFLICT DO NOTHING`,
  [user,randomUUID(),c.id,Object.fromEntries(c.utterances.map(u=>[u.id,u.revision])),c.metadataRevision,input,guard(c,c.utterances,folders)]);
}

export function createOrganizationJobs(db) {
  async function lock(client,user) {
    // Same lock/order as snapshots and mutations: the result and sync cursor commit together.
    await client.query('SELECT sequence FROM sync_heads WHERE user_id=$1 FOR UPDATE',[user]);
  }
  async function current(client,job) {
    const c=(await client.query('SELECT * FROM conversations WHERE user_id=$1 AND id=$2',[job.user_id,job.conversation_id])).rows[0];
    if(!c || c.deleted_at || !c.ended || c.organized || c.organization_attempted) return null;
    const utterances=(await client.query(`SELECT id,current_revision AS revision,current_text AS text,language FROM utterances
      WHERE user_id=$1 AND conversation_id=$2 ORDER BY position`,[job.user_id,job.conversation_id])).rows;
    const value=guard({...c,metadataRevision:c.metadata_revision,first:c.first_language,second:c.second_language,workType:c.work_type,folderId:c.folder_id},utterances,await foldersFor(client,job.user_id));
    // JSONB object key order is not stable. Compare through PostgreSQL JSONB equality.
    const {rows:[equal]}=await client.query('SELECT $1::jsonb = $2::jsonb AS matches',[value,job.guard]);
    return equal.matches ? c : null;
  }
  async function publish(client,user,id,version) {
    const {rows:[head]}=await client.query('UPDATE sync_heads SET sequence=sequence+1 WHERE user_id=$1 RETURNING sequence',[user]);
    await client.query(`INSERT INTO sync_changes(user_id,sequence,entity_type,entity_id,version,deleted)
      VALUES($1,$2,'conversation',$3,$4,false)`,[user,head.sequence,id,version]);
  }
  async function finish(client,job,state,code=null) {
    await client.query(`UPDATE ai_jobs SET state=$3,error_code=$4,lease_token=NULL,lease_until=NULL,updated_at=now()
      WHERE user_id=$1 AND id=$2`,[job.user_id,job.id,state,code]);
    const {rows:[c]}=await client.query(`UPDATE conversations SET organization_attempted=true,version=version+1
      WHERE user_id=$1 AND id=$2 AND deleted_at IS NULL AND NOT organization_attempted RETURNING version`,[job.user_id,job.conversation_id]);
    if(c) await publish(client,job.user_id,job.conversation_id,c.version);
  }
  async function owned(client,job) {
    return (await client.query(`SELECT * FROM ai_jobs WHERE user_id=$1 AND id=$2 AND state='running'
      AND lease_token=$3 AND lease_until>now()`,[job.user_id,job.id,job.lease_token])).rows[0];
  }
  return {
    // The authentication role already knows account IDs. Content is always read/written
    // through forUser and FORCE RLS; no privileged cross-account content connection.
    async usersAfter(after='',limit=100) {
      return (await db.auth.query('SELECT id FROM users WHERE id>$1 ORDER BY id LIMIT $2',[after,limit])).rows.map(r=>r.id);
    },
    async claim(user) {
      return db.forUser(user,async client=>{
        await lock(client,user);
        const {rows:[job]}=await client.query(`SELECT * FROM ai_jobs WHERE user_id=$1 AND kind='organize' AND
          ((state='pending' AND available_at<=now()) OR (state='running' AND lease_until<=now()))
          ORDER BY created_at,id LIMIT 1 FOR UPDATE`,[user]);
        if(!job) return null;
        if(!await current(client,job)) { await finish(client,job,'cancelled','source_changed'); return null; }
        if(job.attempts>=3) { await finish(client,job,'failed','worker_interrupted'); return null; }
        const {rows:[claimed]}=await client.query(`UPDATE ai_jobs SET state='running',attempts=attempts+1,
          lease_token=$3,lease_until=now()+interval '120 seconds',updated_at=now()
          WHERE user_id=$1 AND id=$2 RETURNING *`,[user,job.id,randomUUID()]);
        return claimed;
      });
    },
    async complete(job,value) {
      await db.forUser(job.user_id,async client=>{
        await lock(client,job.user_id);
        const saved=await owned(client,job); if(!saved) return;
        if(!await current(client,saved)) { await finish(client,saved,'cancelled','source_changed'); return; }
        const result=validateOrganizationResult(value,saved.input);
        const {rows:[c]}=await client.query(`UPDATE conversations SET title=$3,summary=$4,category=$5,work_type=$6,folder_id=$7,
          organized=true,organization_attempted=true,organization_sources=$8,metadata_revision=metadata_revision+1,version=version+1
          WHERE user_id=$1 AND id=$2 RETURNING version`,
        [saved.user_id,saved.conversation_id,result.title,result.summary,result.category,result.workType,result.folderId,saved.source_revisions]);
        await publish(client,saved.user_id,saved.conversation_id,c.version);
        await finish(client,saved,'succeeded');
      });
    },
    async fail(job,error) {
      const transient=['runtime_offline','runtime_failed','model_timeout','busy'];
      const safe=[...transient,'invalid_model_output','context_too_large','model_missing','local_model_required','incomplete_output','ai_disabled'];
      const code=safe.includes(error?.code)?error.code:'organization_failed';
      await db.forUser(job.user_id,async client=>{
        await lock(client,job.user_id);
        const saved=await owned(client,job); if(!saved) return;
        if(transient.includes(code) && saved.attempts<3) {
          await client.query(`UPDATE ai_jobs SET state='pending',error_code=$3,lease_token=NULL,lease_until=NULL,
            available_at=now()+($4 * interval '1 second'),updated_at=now() WHERE user_id=$1 AND id=$2`,
          [saved.user_id,saved.id,code,10 * 2**(saved.attempts-1)]);
        } else await finish(client,saved,'failed',code);
      });
    },
    async release(job) {
      await db.forUser(job.user_id,async client=>{
        await lock(client,job.user_id);
        await client.query(`UPDATE ai_jobs SET state='pending',lease_token=NULL,lease_until=NULL,attempts=greatest(0,attempts-1),
          available_at=now(),updated_at=now() WHERE user_id=$1 AND id=$2 AND state='running' AND lease_token=$3`,
        [job.user_id,job.id,job.lease_token]);
      });
    },
  };
}
