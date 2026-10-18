import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createDatabase } from './db/database.mjs';
import { createPostgresAccounts } from './db/accounts.mjs';
import { createUserData } from './db/user-data.mjs';
import { createServer } from './server.mjs';

process.loadEnvFile(new URL('../.runtime/postgres/test.env', import.meta.url));
assert.equal(process.env.PGDATABASE, 'speechbridge_test');
const conversation = () => ({ id: 'c1', title: '회의', createdAt: '2026-10-18T00:00:00Z', translate: true, record: true,
  first: 'KO', second: 'EN', ended: false, category: null, workType: null, folderId: null,
  metadataRevision: 0, organized: false, organizationAttempted: false, summary: null, organizationSources: {},
  utterances: [{ id: 'u1', speaker: '', language: 'KO', originalText: '내일까지 문서를 보냅니다.', text: '내일까지 문서를 보냅니다.', revision: 1, translation: 'I will send the document by tomorrow.', translationRevision: 1 }],
  items: [{ id: 'i1', sourceId: 'u1', sourceRevision: 1, kind: 'REQUEST', text: '문서 보내기', owner: null, dueText: '내일', reviewed: true, status: 'TODO', origin: 'MANUAL', evidenceQuote: null, contextRevisions: {}, omittedByLatestExtraction: false }] });
async function fixture(t) {
  const db = createDatabase(process.env);
  const accounts = createPostgresAccounts({ db });
  const username = `d_${randomUUID().replaceAll('-', '').slice(0, 20)}`;
  const user = (await accounts.handle('/auth/register', { username, password: 'Synthetic-data-password-123!' })).user;
  const data = createUserData(db);
  t.after(async () => { await db.auth.query('DELETE FROM users WHERE id=$1', [user.id]); await db.close(); });
  const put = (entity, id, value, baseVersion = '0', mutationId = randomUUID()) => data.mutate(user.id, { deviceId: 'test-device', mutationId, entity, id, baseVersion, value });
  return { db, data, accounts, username, user, put };
}
test('all record fields, settings and drafts round-trip through normalized PostgreSQL records', async t => {
  const { db, data, user, put } = await fixture(t);
  await put('folder', 'folder', { id: 'folder', name: '학교' });
  const c = { ...conversation(), category: 'WORK', workType: 'MEETING', folderId: 'folder', summary: '문서 발송 예정', organized: true, organizationAttempted: true, ended: true, organizationSources: { u1: 1 } };
  await put('conversation', 'c1', c);
  await put('settings', 'settings', { appearance: 'DARK' });
  await put('draft', 'new', { language: 'KO', text: '작성 중', first: 'KO', second: 'EN' });
  const snapshot = await data.snapshot(user.id, 'test-device');
  assert.deepEqual(snapshot.archive.conversations, [c]);
  assert.deepEqual(snapshot.archive.folders, [{ id: 'folder', name: '학교' }]);
  assert.equal(snapshot.archive.appearance, 'DARK');
  assert.equal(snapshot.drafts.new.text, '작성 중');
  assert.equal(snapshot.versions['conversation:c1'], '1');
  assert.equal(snapshot.cursor, '4');
  const normalized = await db.forUser(user.id, client => client.query('SELECT count(*)::int AS n FROM action_items'));
  assert.equal(normalized.rows[0].n, 1);
});
test('retry is idempotent; changed payload with the same mutation ID is rejected', async t => {
  const { data, user, put } = await fixture(t);
  const mutation = randomUUID();
  const result = await put('conversation', 'c1', conversation(), '0', mutation);
  assert.deepEqual(await put('conversation', 'c1', conversation(), '0', mutation), result);
  await assert.rejects(put('conversation', 'c1', { ...conversation(), title: 'changed' }, '0', mutation), { status: 409, code: 'mutation_reused' });
  assert.equal((await data.snapshot(user.id, 'test-device')).cursor, '1');
});
test('stale edits conflict and deletion cannot be undone by a late upload', async t => {
  const { data, user, put } = await fixture(t);
  await put('conversation', 'c1', conversation());
  await put('conversation', 'c1', { ...conversation(), title: '수동 제목', metadataRevision: 1 }, '1');
  await assert.rejects(put('conversation', 'c1', { ...conversation(), title: '늦은 AI 제목' }, '1'), { status: 409, code: 'version_conflict' });
  await put('conversation', 'c1', null, '2');
  await assert.rejects(put('conversation', 'c1', conversation(), '3'), { status: 409 });
  assert.equal((await data.snapshot(user.id, 'test-device')).archive.conversations.length, 0);
});
test('utterance revisions are immutable and revisions invalidate stale translations', async t => {
  const { data, db, user, put } = await fixture(t);
  const c = conversation();
  await put('conversation', 'c1', c);
  const edited = { ...c, utterances: [{ ...c.utterances[0], text: '다음 주에 문서를 보냅니다.', revision: 2, translation: null, translationRevision: null }], items: [{ ...c.items[0], reviewed: false }] };
  await put('conversation', 'c1', edited, '1');
  assert.deepEqual((await data.snapshot(user.id, 'test-device')).archive.conversations[0], edited);
  assert.equal((await db.forUser(user.id, client => client.query('SELECT * FROM utterance_revisions ORDER BY revision'))).rowCount, 2);
  await assert.rejects(put('conversation', 'c1', { ...edited, utterances: [{ ...edited.utterances[0], text: 'same revision overwrite' }] }, '2'), { status: 409 });
  await assert.rejects(put('conversation', 'c1', { ...edited, utterances: [{ ...edited.utterances[0], translation: 'old', translationRevision: 1 }] }, '2'), { status: 400 });
});
test('foreign user IDs are ignored and cross-user folder references cannot be created', async t => {
  const a = await fixture(t); const b = await fixture(t);
  await a.put('folder', 'secret', { id: 'secret', name: 'A only' });
  await a.put('conversation', 'c1', conversation());
  assert.equal((await b.data.snapshot(b.user.id, 'test-device')).archive.conversations.length, 0);
  await assert.rejects(b.put('conversation', 'c1', { ...conversation(), category: 'WORK', folderId: 'secret', user_id: a.user.id }), { status: 400 });
});
test('deleting a folder unassigns its conversations and publishes both changes atomically', async t => {
  const { data, user, put } = await fixture(t);
  await put('folder', 'f', { id: 'f', name: '임시' });
  await put('conversation', 'c1', { ...conversation(), category: 'WORK', folderId: 'f' });
  await put('folder', 'f', null, '1');
  const snapshot = await data.snapshot(user.id, 'test-device');
  assert.equal(snapshot.archive.conversations[0].folderId, null);
  assert.equal(snapshot.versions['conversation:c1'], '2');
  assert.equal(snapshot.archive.folders.length, 0);
  assert.equal(snapshot.cursor, '4');
});
test('per-user change cursor cannot skip concurrently committed updates', async t => {
  const { data, user, put } = await fixture(t);
  await Promise.all(Array.from({ length: 8 }, (_, i) => put('folder', `f${i}`, { id: `f${i}`, name: `폴더${i}` })));
  const changes = await data.changes(user.id, '0');
  assert.deepEqual(changes.changes.map(c => c.sequence), ['1','2','3','4','5','6','7','8']);
  assert.equal(changes.cursor, '8');
  assert.equal((await data.changes(user.id, '8')).changes.length, 0);
});
test('invalid fields fail before any partial record or receipt is committed', async t => {
  const { data, user, put } = await fixture(t);
  await assert.rejects(put('conversation', 'c1', { ...conversation(), first: 'EN', second: 'EN' }), { status: 400 });
  await assert.rejects(put('conversation', 'c1', { ...conversation(), items: [{ ...conversation().items[0], sourceId: 'missing' }] }), { status: 400 });
  await assert.rejects(put('settings', 'settings', { appearance: 'INVALID' }), { status: 400 });
  await assert.rejects(put('folder', 'f', { id: 'f', name: '' }), { status: 400 });
  assert.equal((await data.snapshot(user.id, 'test-device')).cursor, '0');
});

test('record order survives database round-trip independently of item IDs', async t => {
  const { data, user, put } = await fixture(t);
  const c = conversation();
  c.items = [{ ...c.items[0], id: 'z_last_alphabetically' }, { ...c.items[0], id: 'a_first_alphabetically' }];
  await put('conversation', 'c1', c);
  assert.deepEqual((await data.snapshot(user.id, 'test-device')).archive.conversations[0].items, c.items);
});

test('a cleared draft can be used again at its next version, while stale replays still conflict', async t => {
  const { data, user, put } = await fixture(t);
  const value = { language: 'KO', first: 'KO', second: 'EN', text: '초안' };
  await put('draft', 'new', value);
  await put('draft', 'new', null, '1');
  await assert.rejects(put('draft', 'new', value, '1'), { status: 409 });
  await put('draft', 'new', { ...value, text: '다음 발화' }, '2');
  assert.equal((await data.snapshot(user.id, 'test-device')).drafts.new.text, '다음 발화');
});

test('authenticated data API derives ownership from the bearer and rejects anonymous requests', async t => {
  const { db, accounts, username, user } = await fixture(t);
  const session = await accounts.handle('/auth/login', { username, password: 'Synthetic-data-password-123!' });
  const server = createServer({ env: { AUTH_MODE: 'users' }, accounts, database: db, providers: { configured: {} } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = (path, body, token = session.token) => fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
  assert.equal((await request('/data/snapshot', { deviceId: 'test-device' }, null)).status, 401);
  const saved = await request('/data/mutate', { userId: 'forged-owner', deviceId: 'test-device', mutationId: randomUUID(), entity: 'conversation', id: 'c1', baseVersion: '0', value: conversation() });
  assert.equal(saved.status, 200);
  const snapshot = await request('/data/snapshot', { deviceId: 'test-device' });
  assert.equal(snapshot.status, 200);
  assert.equal((await snapshot.json()).archive.conversations[0].title, '회의');
  assert.equal((await db.forUser(user.id, c => c.query('SELECT user_id FROM conversations'))).rows[0].user_id, user.id);
  const changes = await request('/data/changes', { after: '0' });
  assert.equal((await changes.json()).changes.length, 1);
});

test('existing mobile folder names up to 200 characters survive import', async t => {
  const { data, user, put } = await fixture(t);
  const name = '업'.repeat(200);
  await put('folder', 'long-folder', { id: 'long-folder', name });
  assert.equal((await data.snapshot(user.id, 'test-device')).archive.folders[0].name, name);
  await assert.rejects(put('folder', 'too-long', { id: 'too-long', name: name + '무' }), { code: 'invalid_data' });
});

test('device platform is recorded on reads and metadata never changes mutation idempotency', async t => {
  const {data,db,user}=await fixture(t);
  await data.snapshot(user.id,'phone_ios','ios');
  const mutation={deviceId:'phone_android',mutationId:randomUUID(),entity:'settings',id:'settings',baseVersion:'0',value:{appearance:'DARK'}};
  const first=await data.mutate(user.id,mutation);
  assert.deepEqual(await data.mutate(user.id,{...mutation,platform:'android'}),first);
  await data.snapshot(user.id,'phone_android');
  const devices=(await db.forUser(user.id,c=>c.query('SELECT id,platform FROM devices ORDER BY id'))).rows;
  assert.deepEqual(devices,[{id:'phone_android',platform:'android'},{id:'phone_ios',platform:'ios'}]);
  await assert.rejects(data.snapshot(user.id,'bad','other'),{status:400});
  await assert.rejects(data.mutate(user.id,{...mutation,platform:'other'}),{status:400});
});
