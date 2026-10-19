import test from 'node:test';
import assert from 'node:assert/strict';
import { createProviders } from './provider.mjs';
import { InferenceQueue, localBase, translationChunks } from './local-provider.mjs';

const env = { AI_PROVIDER: 'ollama-local', APP_ACCESS_TOKEN: 'local-test-token-at-least-32-characters' };
const source = { id: 'u1', revision: 1, text: '민수님, 내일까지 보고서를 보내 주세요.' };
const input = { utterances: [source], language: 'ko' };
const item = { sourceUtteranceId: 'u1', sourceRevision: 1, kind: 'request', text: '보고서 보내기', owner: '민수', dueText: '내일까지', evidenceQuote: source.text };
const trans = { text: '내일까지 보내 주세요.', from: 'ko', to: 'en', revision: 1 };
const json = body => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
function fixture({ chat = 'Please send it by tomorrow.', models, show, callback } = {}) {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options, body: options.body ? JSON.parse(options.body) : null });
    assert.ok(url.startsWith('http://127.0.0.1:11435/'));
    assert.equal(options.redirect, 'error');
    if (url.endsWith('/api/tags')) return json({ models: models ?? ['translategemma:12b', 'gemma4:12b-it-qat'].map(name => ({ name, size: 100, digest: 'a'.repeat(64) })) });
    if (url.endsWith('/api/show')) return json(show ?? { model_info: { architecture: 'test' }, details: { parameter_size: '12B' } });
    if (callback) return callback(url, options);
    return json({ done: true, done_reason: 'stop', message: { content: typeof chat === 'string' ? chat : JSON.stringify(chat) } });
  };
  return { calls, fetchImpl };
}

test('provider rejects non-loopback URLs and unsupported/cloud model tags', () => {
  for (const url of ['https://ollama.com', 'http://localhost:11434', 'http://127.0.0.1.evil.test', 'http://127.0.0.1/a', 'http://u:p@127.0.0.1', 'http://127.0.0.1?x=1']) {
    assert.throws(() => localBase(url));
  }
  assert.throws(() => createProviders({ ...env, EXTRACTION_MODEL: 'gemma4:cloud' }));
  assert.equal(localBase('http://127.0.0.1:11435'), 'http://127.0.0.1:11435');
});
test('translation uses explicit languages, local model and returns the original revision', async () => {
  const f = fixture(); const p = createProviders(env, f);
  const result = await p.translate(trans);
  assert.deepEqual(result, { text: 'Please send it by tomorrow.', revision: 1 });
  const call = f.calls.find(c => c.url.endsWith('/api/chat'));
  assert.equal(call.body.model, 'translategemma:12b');
  assert.match(call.body.messages[0].content, /Korean \(ko\) to English \(en\)/);
  assert.equal(call.body.stream, false);
  assert.equal(call.body.options.num_ctx, 4096);
  assert.ok(!f.calls.some(c => c.url.includes('/pull')));
});
test('sentence chunks preserve every input character and reject a single oversized sentence', () => {
  const text = '내일까지 보고서를 보내 주세요. '.repeat(50);
  const chunks = translationChunks(text, 'ko');
  assert.equal(chunks.join(''), text);
  assert.ok(chunks.every(c => Buffer.byteLength(c) <= 1200));
  assert.throws(() => translationChunks('가'.repeat(500), 'ko'), { code: 'context_too_large' });
});
test('no silent conversation truncation and no inference for oversized input', async () => {
  const f = fixture(); const p = createProviders(env, f);
  await assert.rejects(p.extract({ ...input, utterances: [{ ...source, text: '가'.repeat(5000) }] }), { code: 'context_too_large' });
  assert.equal(f.calls.length, 0);
});
test('uninstalled and remote alias models cannot initiate inference', async () => {
  for (const config of [{ models: [] }, { show: { remote_host: 'https://ollama.com', remote_model: 'gemma4' } }]) {
    const f = fixture(config); const p = createProviders(env, f);
    await assert.rejects(p.translate(trans), { status: 503 });
    assert.ok(!f.calls.some(c => c.url.endsWith('/api/chat')));
  }
});
test('health reports configuration separately from runtime and installed models', async () => {
  const p = createProviders(env, fixture({ models: [] }));
  assert.deepEqual(await p.health(), { status: 'ok', configured: p.configured,
    available: { translation: false, extraction: false, speech: false }, runtime: 'online' });
  const offline = createProviders(env, { fetchImpl: async () => { throw new Error('secret connection details'); } });
  assert.equal((await offline.health()).runtime, 'offline');
  await assert.rejects(offline.translate(trans), { code: 'runtime_offline' });
});
test('schema output preserves exact evidence and strips unsupported owner/date', async () => {
  const f = fixture({ chat: { items: [{ ...item, owner: '철수', dueText: '2026-10-18' }] } });
  const result = await createProviders(env, f).extract(input);
  assert.equal(result.items[0].evidenceQuote, source.text);
  assert.equal(result.items[0].owner, null); assert.equal(result.items[0].dueText, null);
  assert.equal(f.calls.at(-1).body.format, 'json');
  assert.match(f.calls.at(-1).body.messages[0].content, /evidenceQuote/);
});
test('invented/missing quotes, malformed JSON and incomplete output are rejected', async () => {
  for (const chat of [{ items: [{ ...item, evidenceQuote: '없는 말' }] }, { items: [{ ...item, evidenceQuote: undefined }] }, '{broken']) {
    await assert.rejects(createProviders(env, fixture({ chat })).extract(input), { status: 502 });
  }
  const p = createProviders(env, fixture({ callback: async () => json({ done: true, done_reason: 'length', message: { content: 'partial' } }) }));
  await assert.rejects(p.translate(trans), { code: 'incomplete_output' });
});
test('timeout aborts upstream fetch and releases inference slot', async () => {
  let wasAborted = false; let hang = true;
  const f = fixture({ callback: async (_, { signal }) => {
    if (!hang) return json({ done: true, done_reason: 'stop', message: { content: 'ok' } });
    return new Promise((_, reject) => signal.addEventListener('abort', () => { wasAborted = true; reject(signal.reason); }, { once: true }));
  } });
  const p = createProviders(env, { ...f, timeoutMs: 30 });
  await assert.rejects(p.translate(trans), { code: 'model_timeout' });
  assert.equal(wasAborted, true); hang = false;
  assert.equal((await p.translate(trans)).text, 'ok');
});
test('bounded queue prioritizes translation and drops cancelled waiting jobs', async () => {
  const queue = new InferenceQueue(2); const order = [];
  let release;
  const signal = new AbortController().signal;
  const first = queue.run(0, signal, () => new Promise(resolve => { release = resolve; }));
  const cancelled = new AbortController();
  const second = queue.run(1, cancelled.signal, async () => order.push('cancelled'));
  const rejected = assert.rejects(second);
  cancelled.abort(); await rejected;
  const extract = queue.run(1, signal, async () => order.push('extract'));
  const translation = queue.run(0, signal, async () => order.push('translate'));
  await assert.rejects(queue.run(0, signal, async () => {}), { status: 429 });
  release(); await Promise.all([first, extract, translation]);
  assert.deepEqual(order, ['translate', 'extract']); assert.equal(queue.active, false);
});

test('organization uses local AI and validates category and existing folder IDs', async () => {
  const raw = { ...input, utterances: [{ ...source, text: '고객 A 회의를 시작합니다.' }], folders: [{ id: 'f1', name: '고객 A' }] };
  const result = { summary: '대화 내용의 요약입니다.', title: '고객 A 회의', category: 'WORK', workType: 'MEETING', folderId: 'f1' };
  const f = fixture({ chat: result });
  assert.deepEqual(await createProviders(env, f).organize(raw), result);
  const call = f.calls.find(c => c.url.endsWith('/api/chat'));
  assert.equal(call.body.model, 'gemma4:12b-it-qat');
  assert.match(call.body.messages[0].content, /untrusted data/);
  assert.ok(call.body.format.required.includes('summary'));
  assert.ok(call.body.format.required.includes('title'));
  for (const bad of [
    { ...result, folderId: 'invented' }, { ...result, category: 'OTHER' },
    { ...result, category: 'DAILY' }, { ...result, workType: null },
    { ...result, summary: '' }, { ...result, summary: null }, { ...result, summary: 'x'.repeat(2001) },
    { ...result, title: '' }, { ...result, title: 'x'.repeat(81) },
  ]) await assert.rejects(createProviders(env, fixture({ chat: bad })).organize(raw), { code: 'invalid_model_output' });
  const daily = { summary: '주말 여행을 이야기했다.', title: '주말 여행', category: 'DAILY', workType: null, folderId: null };
  assert.deepEqual(await createProviders(env, fixture({ chat: daily })).organize(raw), daily);
});
test('organization rejects invalid folders and oversized context before model calls', async () => {
  const f = fixture(); const p = createProviders(env, f);
  for (const folders of [null, [{ id: 'f', name: '' }], [{ id: 'f', name: 'a' }, { id: 'f', name: 'b' }]]) {
    await assert.rejects(p.organize({ ...input, folders }), { code: 'invalid_input' });
  }
  await assert.rejects(p.organize({ ...input, utterances: [{ ...source, text: '가'.repeat(5000) }], folders: [] }), { code: 'context_too_large' });
  assert.equal(f.calls.length, 0);
});

test('organization never files a conversation under an unrelated existing project', async () => {
  const result = { summary: '대화 내용의 요약입니다.', title: '보고서 요청', category: 'WORK', workType: 'TASK', folderId: 'apollo' };
  const value = await createProviders(env, fixture({ chat: result })).organize({ ...input, folders: [{ id: 'apollo', name: '아폴로 프로젝트' }] });
  assert.equal(value.folderId, null);
});

test('translation remains resident across idle time while background model expires', async () => {
  const f = fixture(); const p = createProviders(env, f);
  await p.translate(trans);
  assert.equal(f.calls.find(c => c.url.endsWith('/api/chat')).body.keep_alive, -1);
  const background = fixture({ chat: { items: [] } });
  await createProviders(env, background).extract(input);
  assert.equal(background.calls.find(c => c.url.endsWith('/api/chat')).body.keep_alive, '30m');
});

test('startup warmup verifies local model and loads the exact translation context without user content', async () => {
  const f = fixture(); const p = createProviders(env, f);
  await p.warmup();
  const call = f.calls.find(c => c.url.endsWith('/api/generate'));
  assert.equal(call.body.model, 'translategemma:12b');
  assert.equal(call.body.keep_alive, -1);
  assert.equal(call.body.options.num_ctx, 4096);
  assert.equal(call.body.prompt, undefined);
  assert.ok(f.calls.findIndex(c=>c.url.endsWith('/api/show')) < f.calls.indexOf(call));
  const remote = fixture({ show: { remote_host: 'https://ollama.com', remote_model: 'fake' } });
  await assert.rejects(createProviders(env, remote).warmup(), {code:'local_model_required'});
  assert.ok(!remote.calls.some(c=>c.url.endsWith('/api/generate')));
});
