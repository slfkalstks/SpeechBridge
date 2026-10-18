import { createHash } from 'node:crypto';
import { ApiError } from '../errors.mjs';

const bad = () => new ApiError(400, 'invalid_data', '저장할 내용을 확인해 주세요.');
const conflict = (code = 'version_conflict') => new ApiError(409, code, '다른 변경 사항이 있습니다. 최신 기록을 확인해 주세요.');
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const text = (v, max = 16000) => typeof v === 'string' && v.trim().length > 0 && v.length <= max;
const nullableText = (v, max = 16000) => v == null || typeof v === 'string' && v.length <= max;
const id = v => typeof v === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(v);
const integer = (v, min = 0) => Number.isSafeInteger(v) && v >= min;
const version = v => typeof v === 'string' && /^(0|[1-9][0-9]{0,17})$/.test(v);
const platform = v => v === undefined || ['android','ios','test'].includes(v);
const language = v => ['KO', 'EN', 'JA'].includes(v);
const revisionMap = v => object(v) && Object.entries(v).every(([key, rev]) => id(key) && integer(rev, 1));
const stable = v => Array.isArray(v) ? v.map(stable) : object(v) ? Object.fromEntries(Object.keys(v).sort().map(k => [k, stable(v[k])])) : v;
const same = (a, b) => JSON.stringify(stable(a)) === JSON.stringify(stable(b));

function validateConversation(c, key) {
  if (!object(c) || c.id !== key || !text(c.title, 200) || typeof c.createdAt !== 'string' || !Number.isFinite(Date.parse(c.createdAt)) ||
      !language(c.first) || !language(c.second) || c.first === c.second || c.record !== true || typeof c.translate !== 'boolean' || typeof c.ended !== 'boolean' ||
      !integer(c.metadataRevision) || typeof c.organized !== 'boolean' || typeof c.organizationAttempted !== 'boolean' ||
      !nullableText(c.summary, 2000) || !revisionMap(c.organizationSources) ||
      ![null, 'DAILY', 'WORK'].includes(c.category ?? null) || ![null, 'MEETING', 'TASK', 'IDEA', 'OTHER'].includes(c.workType ?? null) ||
      (c.folderId != null && !id(c.folderId)) || (c.category !== 'WORK' && (c.workType != null || c.folderId != null)) ||
      !Array.isArray(c.utterances) || !Array.isArray(c.items)) throw bad();
  const utterances = new Map();
  for (const u of c.utterances) {
    if (!object(u) || !id(u.id) || utterances.has(u.id) || !language(u.language) || !text(u.originalText) || !text(u.text) || !integer(u.revision, 1) ||
        !nullableText(u.speaker, 200) || !nullableText(u.translation) ||
        (u.translation == null ? u.translationRevision != null : u.translationRevision !== u.revision)) throw bad();
    utterances.set(u.id, u);
  }
  const seen = new Set();
  for (const item of c.items) {
    if (!object(item) || !id(item.id) || seen.has(item.id) || !utterances.has(item.sourceId) || !integer(item.sourceRevision, 1) || item.sourceRevision > utterances.get(item.sourceId).revision ||
        !['REQUEST', 'DECISION', 'NOTE'].includes(item.kind) || !['CANDIDATE', 'TODO', 'DONE'].includes(item.status) || !['MANUAL', 'SERVICE'].includes(item.origin) ||
        !text(item.text) || !nullableText(item.owner, 200) || !nullableText(item.dueText, 200) || !nullableText(item.evidenceQuote) ||
        typeof item.reviewed !== 'boolean' || typeof item.omittedByLatestExtraction !== 'boolean' || !revisionMap(item.contextRevisions)) throw bad();
    seen.add(item.id);
  }
}

const entities = { conversation: 'conversations', folder: 'work_folders', settings: 'user_settings', draft: 'conversation_drafts' };
function validateMutation(m) {
  if (!object(m) || !platform(m.platform) || !id(m.deviceId) || !id(m.mutationId) || !Object.hasOwn(entities, m.entity) || !id(m.id) || !version(m.baseVersion) || !Object.hasOwn(m, 'value')) throw bad();
  if (m.entity === 'settings' && (m.id !== 'settings' || !object(m.value) || !['SYSTEM', 'LIGHT', 'DARK'].includes(m.value.appearance))) throw bad();
  if (m.value === null) return;
  if (m.entity === 'conversation') validateConversation(m.value, m.id);
  if (m.entity === 'folder' && (!object(m.value) || m.value.id !== m.id || !text(m.value.name, 200))) throw bad();
  if (m.entity === 'draft' && (!object(m.value) || !language(m.value.language) || !nullableText(m.value.text) || !language(m.value.first) || !language(m.value.second) || m.value.first === m.value.second)) throw bad();
}

export function createUserData(db) {
  async function device(client,user,deviceId,kind) {
    await client.query(`INSERT INTO devices(user_id,id,platform) VALUES($1,$2,$3)
      ON CONFLICT(user_id,id) DO UPDATE SET platform=CASE WHEN $4 THEN excluded.platform ELSE devices.platform END,last_seen=now()`,
      [user,deviceId,kind ?? 'test',kind !== undefined]);
  }
  async function head(client, user) {
    await client.query('INSERT INTO sync_heads(user_id) VALUES($1) ON CONFLICT DO NOTHING', [user]);
    await client.query('SELECT sequence FROM sync_heads WHERE user_id=$1 FOR UPDATE', [user]);
  }
  async function changed(client, user, entity, key, rev, deleted = false) {
    const { rows: [h] } = await client.query('UPDATE sync_heads SET sequence=sequence+1 WHERE user_id=$1 RETURNING sequence', [user]);
    await client.query('INSERT INTO sync_changes(user_id,sequence,entity_type,entity_id,version,deleted) VALUES($1,$2,$3,$4,$5,$6)', [user, h.sequence, entity, key, rev, deleted]);
    return h.sequence;
  }
  async function saveConversation(client, user, c, rev, previous) {
    if (c.folderId != null && !(await client.query('SELECT id FROM work_folders WHERE user_id=$1 AND id=$2 AND deleted_at IS NULL', [user, c.folderId])).rowCount) throw bad();
    if (previous && (c.metadataRevision < previous.metadata_revision || c.createdAt !== previous.created_at_text || (previous.ended && !c.ended) ||
        (previous.organization_attempted && !c.organizationAttempted))) throw conflict();
    if (previous) {
      const old = (await client.query('SELECT * FROM utterances WHERE user_id=$1 AND conversation_id=$2', [user, c.id])).rows;
      for (const u of old) {
        const next = c.utterances.find(v => v.id === u.id);
        if (!next || next.originalText !== u.original_text || next.revision < u.current_revision ||
            next.revision === u.current_revision && next.text !== u.current_text) throw conflict();
      }
    }
    await client.query(`INSERT INTO conversations(user_id,id,title,summary,created_at,created_at_text,ended,first_language,second_language,translate,category,work_type,folder_id,metadata_revision,organized,organization_attempted,organization_sources,version)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18)
      ON CONFLICT(user_id,id) DO UPDATE SET title=excluded.title, summary=excluded.summary, ended=excluded.ended, first_language=excluded.first_language,
        second_language=excluded.second_language, translate=excluded.translate, category=excluded.category, work_type=excluded.work_type, folder_id=excluded.folder_id,
        metadata_revision=excluded.metadata_revision, organized=excluded.organized, organization_attempted=excluded.organization_attempted,
        organization_sources=excluded.organization_sources,version=excluded.version`,
    [user,c.id,c.title,c.summary ?? null,c.createdAt,c.createdAt,c.ended,c.first,c.second,c.translate,c.category ?? null,c.workType ?? null,c.folderId ?? null,c.metadataRevision,c.organized,c.organizationAttempted,c.organizationSources,rev]);
    for (const [position, u] of c.utterances.entries()) {
      await client.query(`INSERT INTO utterances(user_id,conversation_id,id,position,language,original_text,current_text,current_revision,legacy_speaker) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)
        ON CONFLICT(user_id,conversation_id,id) DO UPDATE SET current_text=excluded.current_text,current_revision=excluded.current_revision,language=excluded.language`,
      [user,c.id,u.id,position,u.language,u.originalText,u.text,u.revision,u.speaker ?? '']);
      for (const [r, txt] of [[1,u.originalText],[u.revision,u.text]]) {
        const result = await client.query(`INSERT INTO utterance_revisions(user_id,conversation_id,utterance_id,revision,text) VALUES($1,$2,$3,$4,$5)
          ON CONFLICT(user_id,conversation_id,utterance_id,revision) DO NOTHING RETURNING revision`, [user,c.id,u.id,r,txt]);
        if (!result.rowCount) {
          const existing = (await client.query('SELECT text FROM utterance_revisions WHERE user_id=$1 AND conversation_id=$2 AND utterance_id=$3 AND revision=$4', [user,c.id,u.id,r])).rows[0];
          if (existing.text !== txt) throw conflict();
        }
      }
      await client.query('DELETE FROM translations WHERE user_id=$1 AND conversation_id=$2 AND utterance_id=$3 AND revision=$4', [user,c.id,u.id,u.revision]);
      if (u.translation != null) await client.query('INSERT INTO translations(user_id,conversation_id,utterance_id,revision,target_language,text) VALUES($1,$2,$3,$4,$5,$6)', [user,c.id,u.id,u.revision,u.language===c.first ? c.second : c.first,u.translation]);
    }
    await client.query('DELETE FROM action_items WHERE user_id=$1 AND conversation_id=$2', [user,c.id]);
    for (const [position,item] of c.items.entries()) await client.query(`INSERT INTO action_items(user_id,conversation_id,id,source_id,source_revision,kind,text,owner_text,due_text,reviewed,status,origin,evidence_quote,context_revisions,omitted_by_latest_extraction,position)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)`, [user,c.id,item.id,item.sourceId,item.sourceRevision,item.kind,item.text,item.owner ?? null,item.dueText ?? null,item.reviewed,item.status,item.origin,item.evidenceQuote ?? null,item.contextRevisions,item.omittedByLatestExtraction,position]);
  }
  async function mutate(user, m) {
    validateMutation(m);
    const {platform: metadata, ...content} = m;
    const hash = createHash('sha256').update(JSON.stringify(stable(content))).digest('hex');
    try {
      return await db.forUser(user, async client => {
        await head(client, user);
        await device(client,user,m.deviceId,m.platform);
        const prior = (await client.query('SELECT request_hash,result FROM sync_receipts WHERE user_id=$1 AND device_id=$2 AND mutation_id=$3', [user,m.deviceId,m.mutationId])).rows[0];
        if (prior) { if (prior.request_hash !== hash) throw conflict('mutation_reused'); return prior.result; }
        const where = m.entity === 'settings' ? 'user_id=$1' : m.entity === 'draft' ? 'user_id=$1 AND scope_id=$2 AND device_id=$3' : 'user_id=$1 AND id=$2';
        const params = m.entity === 'settings' ? [user] : m.entity === 'draft' ? [user,m.id,m.deviceId] : [user,m.id];
        const table = entities[m.entity];
        const previous = (await client.query(`SELECT * FROM ${table} WHERE ${where}`, params)).rows[0];
        if ((previous?.version ?? '0') !== m.baseVersion || (previous?.deleted_at && (m.entity !== 'draft' || m.value === null))) throw conflict();
        const rev = (BigInt(m.baseVersion) + 1n).toString();
        if (m.value === null) {
          if (!previous) throw conflict();
          if (m.entity === 'folder') {
            const affected = (await client.query('UPDATE conversations SET folder_id=NULL,metadata_revision=metadata_revision+1,version=version+1 WHERE user_id=$1 AND folder_id=$2 AND deleted_at IS NULL RETURNING id,version', [user,m.id])).rows;
            for (const c of affected) await changed(client,user,'conversation',c.id,c.version);
          }
          await client.query(`UPDATE ${table} SET deleted_at=now(),version=version+1 WHERE ${where}`, params);
        } else if (m.entity === 'conversation') await saveConversation(client,user,m.value,rev,previous);
        else if (m.entity === 'folder') await client.query('INSERT INTO work_folders(user_id,id,name,version) VALUES($1,$2,$3,$4) ON CONFLICT(user_id,id) DO UPDATE SET name=excluded.name,version=excluded.version', [user,m.id,m.value.name.trim(),rev]);
        else if (m.entity === 'settings') await client.query('INSERT INTO user_settings(user_id,appearance,version) VALUES($1,$2,$3) ON CONFLICT(user_id) DO UPDATE SET appearance=excluded.appearance,version=excluded.version,updated_at=now()', [user,m.value.appearance,rev]);
        else {
          await client.query('INSERT INTO conversation_drafts(user_id,device_id,scope_id,payload,version) VALUES($1,$2,$3,$4,$5) ON CONFLICT(user_id,device_id,scope_id) DO UPDATE SET payload=excluded.payload,version=excluded.version,updated_at=now(),deleted_at=NULL', [user,m.deviceId,m.id,m.value,rev]);
        }
        const cursor = await changed(client,user,m.entity,m.id,rev,m.value===null);
        const result = { entity: m.entity, id: m.id, version: rev, cursor };
        await client.query('INSERT INTO sync_receipts(user_id,device_id,mutation_id,request_hash,result) VALUES($1,$2,$3,$4,$5)', [user,m.deviceId,m.mutationId,hash,result]);
        return result;
      });
    } catch (error) {
      if (['23503','23505','23514','22007','22008','22003'].includes(error.code)) throw bad();
      throw error;
    }
  }
  async function snapshot(user, deviceId, kind) {
    if (!id(deviceId) || !platform(kind)) throw bad();
    return db.forUser(user, async client => {
      // Same lock as writers: rows and cursor describe a single committed state.
      await head(client,user);
      await device(client,user,deviceId,kind);
      const cursor = (await client.query('SELECT sequence FROM sync_heads WHERE user_id=$1', [user])).rows[0].sequence;
      const rows = async (table, suffix = '', params = [user]) => (await client.query(`SELECT * FROM ${table} WHERE user_id=$1 ${suffix}`, params)).rows;
      const conversations = await rows('conversations','ORDER BY created_at,id');
      const folders = await rows('work_folders','ORDER BY name,id');
      const settings = (await rows('user_settings'))[0];
      const drafts = await rows('conversation_drafts','AND device_id=$2', [user,deviceId]);
      const utterances = await rows('utterances','ORDER BY position');
      const translations = await rows('translations');
      const items = await rows('action_items','ORDER BY position,id');
      const versions = {};
      for (const [entity, list] of [['conversation',conversations],['folder',folders],['draft',drafts]]) for (const r of list) versions[`${entity}:${r.id ?? r.scope_id}`] = r.version;
      if (settings) versions['settings:settings'] = settings.version;
      return { cursor, versions, drafts: Object.fromEntries(drafts.filter(d=>!d.deleted_at).map(d=>[d.scope_id,d.payload])), archive: {
        version: 1, appearance: settings?.appearance ?? 'SYSTEM', folders: folders.filter(f=>!f.deleted_at).map(f=>({id:f.id,name:f.name})),
        conversations: conversations.filter(c=>!c.deleted_at).map(c=>({ id:c.id,title:c.title,summary:c.summary,createdAt:c.created_at_text || c.created_at.toISOString(),
          first:c.first_language,second:c.second_language,translate:c.translate,record:true,ended:c.ended,category:c.category,workType:c.work_type,folderId:c.folder_id,
          metadataRevision:c.metadata_revision,organized:c.organized,organizationAttempted:c.organization_attempted,organizationSources:c.organization_sources,
          utterances: utterances.filter(u=>u.conversation_id===c.id).map(u=>{
            const tr = translations.find(tr=>tr.conversation_id===c.id && tr.utterance_id===u.id && tr.revision===u.current_revision && tr.target_language===(u.language===c.first_language?c.second_language:c.first_language));
            return {id:u.id,speaker:u.legacy_speaker,language:u.language,originalText:u.original_text,text:u.current_text,revision:u.current_revision,translation:tr?.text ?? null,translationRevision:tr?.revision ?? null};
          }),
          items: items.filter(i=>i.conversation_id===c.id).map(i=>({id:i.id,sourceId:i.source_id,sourceRevision:i.source_revision,kind:i.kind,text:i.text,owner:i.owner_text,dueText:i.due_text,reviewed:i.reviewed,status:i.status,origin:i.origin,evidenceQuote:i.evidence_quote,contextRevisions:i.context_revisions,omittedByLatestExtraction:i.omitted_by_latest_extraction}))
        })) } };
    });
  }
  async function changes(user, after) {
    if (!version(after)) throw bad();
    return db.forUser(user, async client => {
      const { rows } = await client.query('SELECT sequence,entity_type AS entity,entity_id AS id,version,deleted FROM sync_changes WHERE user_id=$1 AND sequence>$2 ORDER BY sequence LIMIT 500', [user,after]);
      return { changes: rows, cursor: rows.at(-1)?.sequence ?? after };
    });
  }
  return { mutate, snapshot, changes };
}
