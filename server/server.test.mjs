import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from './server.mjs';
import { createProviders, validateExtractedItems } from './provider.mjs';

const token = 'test-only-token-with-at-least-32-characters';
const env = { APP_ACCESS_TOKEN: token, AZURE_TRANSLATOR_KEY: 'test', AZURE_TRANSLATOR_REGION: 'koreacentral', OPENAI_API_KEY: 'test', AZURE_SPEECH_KEY: 'test', AZURE_SPEECH_REGION: 'koreacentral' };
const input = { text: '안녕하세요', from: 'ko', to: 'en', revision: 3 };
const sources = { language: 'ko', utterances: [{ id: 'u1', revision: 2, text: '민수님, 내일까지 보고서를 보내 주세요.' }] };
const item = { sourceUtteranceId: 'u1', sourceRevision: 2, kind: 'request', text: '보고서 보내기', owner: '민수', dueText: '내일까지' };
const response = body => ({ ok: true, json: async () => body, text: async () => body });
async function withServer(t, options) {
  const server = createServer(options);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  return `http://127.0.0.1:${server.address().port}`;
}
const post = (url, path, body, auth = token) => fetch(url + path, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth}` }, body: JSON.stringify(body) });

test('health distinguishes configuration; missing auth fails closed without provider call', async t => {
  const providers = createProviders({}, () => { assert.fail('must not call paid API'); });
  const url = await withServer(t, { env: {}, providers });
  const health = await fetch(url + '/health');
  assert.equal(health.headers.get('cache-control'), 'no-store');
  assert.equal((await health.json()).configured.auth, false);
  assert.equal((await post(url, '/translate', input)).status, 503);
});
test('wrong access token returns 401; missing API returns 503', async t => {
  const url = await withServer(t, { env: { APP_ACCESS_TOKEN: token } });
  assert.equal((await post(url, '/translate', input, 'wrong')).status, 401);
  assert.equal((await post(url, '/translate', input)).status, 503);
});
test('paid provider credentials cannot enable outbound requests', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; throw new Error('No outbound requests allowed'); };
  try {
    const p = createProviders(env);
    assert.equal(p.configured.translation, false);
    assert.equal(p.configured.extraction, false);
    assert.equal(p.configured.speech, false);
    await assert.rejects(p.translate(input), { status: 503, code: 'free_provider_pending' });
    await assert.rejects(p.extract(sources), { status: 503, code: 'free_provider_pending' });
    await assert.rejects(p.speechToken(), { status: 503, code: 'free_provider_pending' });
    assert.deepEqual(await p.translate({ ...input, to: 'ko' }), { text: input.text, revision: 3 });
    assert.equal(calls, 0);
  } finally { globalThis.fetch = originalFetch; }
});
test('unknown evidence/revision rejected and invented owner/date removed', () => {
  assert.throws(() => validateExtractedItems({ items: [{ ...item, sourceRevision: 1 }] }, sources), { status: 502 });
  const result = validateExtractedItems({ items: [{ ...item, owner: '철수', dueText: '2026-10-18' }] }, sources);
  assert.equal(result.items[0].owner, null); assert.equal(result.items[0].dueText, null);
});
test('malformed payload is rejected before external calls', async t => {
  const providers = createProviders(env, () => assert.fail('external call not expected'));
  const url = await withServer(t, { env, providers });
  assert.equal((await post(url, '/extract', { ...sources, utterances: [sources.utterances[0], sources.utterances[0]] })).status, 400);
  assert.equal((await post(url, '/translate', { ...input, revision: 0 })).status, 400);
  assert.equal((await post(url, '/translate', null)).status, 400);
});
test('concurrency cap rejects excess requests while allowing in-flight requests to finish', async t => {
  const pending = [];
  const providers = { configured: { auth: true }, translate: () => new Promise(resolve => pending.push(resolve)) };
  const url = await withServer(t, { env, providers });
  const requests = Array.from({ length: 6 }, () => post(url, '/translate', input));
  const deadline = Date.now() + 3000;
  while (pending.length < 6 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 5));
  try {
    assert.equal(pending.length, 6);
    assert.equal((await post(url, '/translate', input)).status, 429);
  } finally { pending.forEach(resolve => resolve({ text: 'test', revision: 3 })); }
  assert.ok((await Promise.all(requests)).every(r => r.status === 200));
});
test('rate window recovers after configured period', async t => {
  let time = 0;
  const providers = { configured: { auth: true }, translate: async () => ({ text: 'test', revision: 3 }) };
  const url = await withServer(t, { env, providers, now: () => time });
  for (let i = 0; i < 60; i++) assert.equal((await post(url, '/translate', input)).status, 200);
  assert.equal((await post(url, '/translate', input)).status, 429);
  time = 60001;
  assert.equal((await post(url, '/translate', input)).status, 200);
});

test('closing a client request cancels provider inference', async t => {
  let started, stopped;
  const begin = new Promise(resolve => { started = resolve; });
  const end = new Promise(resolve => { stopped = resolve; });
  const providers = { configured: { auth: true }, translate: (_, { signal }) => new Promise((_, reject) => {
    signal.addEventListener('abort', () => { stopped(); reject(signal.reason); }, { once: true });
    started();
  }) };
  const url = await withServer(t, { env, providers });
  const controller = new AbortController();
  const request = fetch(url + '/translate', { method: 'POST', signal: controller.signal,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify(input) });
  const rejected = assert.rejects(request);
  await begin; controller.abort(); await rejected;
  await Promise.race([end, new Promise((_, reject) => { const timer = setTimeout(() => reject(new Error('provider not cancelled')), 1000); timer.unref(); })]);
});

test('organization endpoint authenticates and routes through text provider', async t => {
  let calls = 0;
  const result = { summary: '대화 내용의 요약입니다.', title: '회의 정리', category: 'WORK', workType: 'MEETING', folderId: null };
  const providers = { configured: { auth: true }, async organize(body, context) {
    calls++; assert.deepEqual(body, { ...sources, folders: [] }); assert.ok(context.signal); return result;
  } };
  const url = await withServer(t, { env, providers });
  assert.equal((await post(url, '/organize', { ...sources, folders: [] }, 'wrong')).status, 401);
  assert.equal(calls, 0);
  const response = await post(url, '/organize', { ...sources, folders: [] });
  assert.equal(response.status, 200); assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await response.json(), result); assert.equal(calls, 1);
});
