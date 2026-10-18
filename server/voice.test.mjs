import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { chmod, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createVoiceProvider, runVoiceProcess, validateSynthesis, validateTranscription, validateWav } from './voice-provider.mjs';
import { createServer } from './server.mjs';
import { createProviders } from './provider.mjs';

const token = 'voice-test-only-token-more-than-32-characters';
const env = { APP_ACCESS_TOKEN: token, AI_PROVIDER: 'ollama-local', SPEECH_PROVIDER: 'local-process' };
const text = { text: 'Hello.', language: 'en' };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function wav(rate = 16000, samples = 160, amplitude = 1000) {
  const b = Buffer.alloc(44 + samples * 2);
  b.write('RIFF'); b.writeUInt32LE(b.length - 8, 4); b.write('WAVEfmt ', 8);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(rate, 24); b.writeUInt32LE(rate * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34);
  b.write('data', 36); b.writeUInt32LE(samples * 2, 40);
  for (let i = 44; i < b.length; i += 2) b.writeInt16LE(amplitude, i);
  return b;
}
const audioBody = () => ({ audioBase64: wav().toString('base64'), language: 'en' });
async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'speechbridge-voice-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  for (const directory of ['venv/bin', 'nemo-speech-0.2.0-macos-aarch64-metal/bin', 'models/qwen3-tts-0.6b']) {
    await mkdir(join(root, directory), { recursive: true });
  }
  for (const file of ['venv/bin/python', 'nemo-speech-0.2.0-macos-aarch64-metal/bin/nemo-speech',
    'models/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf', 'models/qwen3-tts-0.6b/config.json', 'models/qwen3-tts-0.6b/model.safetensors']) {
    await writeFile(join(root, file), 'fixture', { mode: 0o700 });
  }
  return root;
}
async function serve(t, providers) {
  const server = createServer({ env, providers });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.cancelActiveRequests(); server.closeAllConnections(); server.close(resolve); }));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}
const post = (url, path, body, signal, auth = token) => fetch(url + path, {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${auth}` },
  body: JSON.stringify(body), signal,
});

test('WAV limits use actual PCM bytes and reject malformed formats and silence', () => {
  assert.equal(validateWav(wav(16000, 480000)).length, 960044);
  assert.throws(() => validateWav(wav(16000, 480001)), { code: 'invalid_audio' });
  assert.throws(() => validateWav(wav(24000)), { code: 'invalid_audio' });
  assert.throws(() => validateWav(wav(16000, 160, 0)), { code: 'empty_audio' });
  assert.throws(() => validateWav(wav(16000, 0)), { code: 'invalid_audio' });
  for (const offset of [0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40]) {
    const broken = wav(); broken[offset] ^= 0xff;
    assert.throws(() => validateWav(broken), { code: 'invalid_audio' });
  }
  assert.throws(() => validateWav(Buffer.concat([wav(), Buffer.from([0])])), { code: 'invalid_audio' });
  const highBit = wav(); highBit[0] |= 0x80;
  assert.throws(() => validateWav(highBit), { code: 'invalid_audio' });
});

test('strict base64, language and synthesis length validation precede inference', () => {
  assert.equal(validateTranscription(audioBody()).language, 'en');
  for (const audioBase64 of ['!!!!', 'a===', '', 'AAAA\n', audioBody().audioBase64 + '=', 'A'.repeat(1_280_061)]) {
    assert.throws(() => validateTranscription({ ...audioBody(), audioBase64 }), { code: 'invalid_audio' });
  }
  for (const language of ['fr', '', null]) {
    assert.throws(() => validateSynthesis({ ...text, language }), { code: 'invalid_language' });
    assert.throws(() => validateTranscription({ ...audioBody(), language }), { code: 'invalid_language' });
  }
  for (const value of ['', ' ', 'a'.repeat(301), '\0', 10]) {
    assert.throws(() => validateSynthesis({ ...text, text: value }), { code: 'invalid_input' });
  }
  assert.equal([...validateSynthesis({ ...text, text: '가'.repeat(300) }).text].length, 300);
});

test('voice readiness distinguishes missing files; disabled never calls engine', async t => {
  const root = await fixture(t);
  const p = createProviders(env, { voice: { root }, fetchImpl: async () => { throw Error('offline'); } });
  const health = await p.health();
  assert.equal(health.runtime, 'offline'); assert.equal(health.available.speech, true);
  assert.equal(health.speechRuntime, 'files_ready');
  await chmod(join(root, 'venv/bin/python'), 0o600);
  assert.equal((await p.health()).available.speech, false);
  await assert.rejects(p.synthesize(text), { code: 'voice_not_ready' });
  await assert.rejects(createProviders({ ...env, AI_PROVIDER: 'disabled' }).synthesize(text), { code: 'free_provider_pending' });
  await assert.rejects(createProviders({ ...env, SPEECH_PROVIDER: 'disabled' }).transcribe(audioBody()), { code: 'speech_pending' });
});

test('private temp files, no child credentials, clean success and failure', async t => {
  const root = await fixture(t);
  let fail = false;
  const p = createVoiceProvider({ root, runProcess: async (command, args, options) => {
    assert.equal((await stat(options.cwd)).mode & 0o777, 0o700);
    assert.equal(options.env.APP_ACCESS_TOKEN, undefined);
    assert.equal(options.env.HF_HUB_OFFLINE, '1');
    if (fail) throw new Error('secret model text');
    if (command.endsWith('nemo-speech')) {
      assert.equal((await stat(args[2])).mode & 0o777, 0o600);
      assert.deepEqual(await readFile(args[2]), wav());
      return JSON.stringify({ text: 'hello' });
    }
    assert.equal((await stat(args[2])).mode & 0o777, 0o600);
    assert.deepEqual(JSON.parse(options.input), text);
    await writeFile(args[2], wav(24000)); return '';
  } });
  assert.deepEqual(await p.transcribe(audioBody()), { text: 'hello', language: 'en' });
  assert.deepEqual(await p.synthesize(text), wav(24000));
  assert.deepEqual(await readdir(join(root, 'requests')), []);
  fail = true;
  await assert.rejects(p.transcribe(audioBody()), { code: 'voice_failed' });
  assert.deepEqual(await readdir(join(root, 'requests')), []);
});

test('startup deletes only stale request folders and preserves live-owner files', async t => {
  const root = await fixture(t);
  const requests = join(root, 'requests');
  await mkdir(requests);
  // macOS/Linux pid_max is far below this value; verify no process owns it before using it.
  assert.throws(() => process.kill(2147483647, 0), { code: 'ESRCH' });
  for (const name of ['request-2147483647-dead', `request-${process.pid}-live`, 'preserved']) await mkdir(join(requests, name));
  assert.equal(await createVoiceProvider({ root }).ready(), true);
  assert.deepEqual((await readdir(requests)).sort(), ['preserved', `request-${process.pid}-live`].sort());
});

test('late success after cancellation is discarded and slot held until worker settles', async t => {
  const root = await fixture(t); const started = deferred(), release = deferred();
  const p = createVoiceProvider({ root, runProcess: async () => { started.resolve(); await release.promise; return '{"text":"late"}'; } });
  const controller = new AbortController();
  const result = p.transcribe(audioBody(), { signal: controller.signal });
  const rejection = assert.rejects(result, { code: 'request_cancelled' });
  await started.promise; controller.abort();
  await assert.rejects(p.transcribe(audioBody()), { code: 'busy' });
  release.resolve(); await rejection;
  assert.deepEqual(await readdir(join(root, 'requests')), []);
  assert.equal((await p.transcribe(audioBody())).text, 'late');
});

test('process cancellation escalates SIGTERM to SIGKILL and reaps PID before rejection', async () => {
  const controller = new AbortController();
  // Let child report readiness through a temporary marker, so cancellation cannot precede its handler.
  const root = await mkdtemp(join(tmpdir(), 'speechbridge-process-test-'));
  const marker = join(root, 'pid');
  try {
    const program = "process.on('SIGTERM', () => {}); require('node:fs').writeFileSync(process.argv[1], String(process.pid)); setInterval(() => {}, 1000);";
    const result = runVoiceProcess(process.execPath, ['-e', program, marker], { signal: controller.signal, killGraceMs: 80 });
    const rejection = assert.rejects(result, { code: 'request_cancelled' });
    let pid;
    for (let i = 0; i < 200; i++) { try { pid = Number(await readFile(marker, 'utf8')); break; } catch { await delay(10); } }
    assert.ok(pid); controller.abort(); await rejection;
    assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
  } finally { controller.abort(); await rm(root, { recursive: true, force: true }); }
});

test('process launch failures, nonzero exit and oversized stdout are sanitized', async () => {
  await assert.rejects(runVoiceProcess('/nonexistent/speechbridge', []), { code: 'voice_failed' });
  await assert.rejects(runVoiceProcess(process.execPath, ['-e', "console.error('secret'); process.exit(1)"]), { code: 'voice_failed' });
  await assert.rejects(runVoiceProcess(process.execPath, ['-e', "process.stdout.write('x'.repeat(300000)); setInterval(() => {}, 1000)"]), { code: 'voice_failed' });
});

test('already cancelled requests never launch a process or leave temporary audio', async t => {
  const root = await fixture(t); const controller = new AbortController(); controller.abort();
  const p = createVoiceProvider({ root, runProcess: () => assert.fail('must not launch') });
  await assert.rejects(p.transcribe(audioBody(), { signal: controller.signal }), { code: 'request_cancelled' });
  await assert.rejects(p.synthesize(text, { signal: controller.signal }), { code: 'request_cancelled' });
  await p.ready(); assert.deepEqual(await readdir(join(root, 'requests')), []);
});

test('timeout propagates to real child and allows the next request only after close', async t => {
  const root = await fixture(t); let hang = true;
  const p = createVoiceProvider({ root, timeoutMs: 150, runProcess: async (_, args, options) => {
    if (hang) return runVoiceProcess(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], options);
    return '{"text":"recovered"}';
  } });
  await assert.rejects(p.transcribe(audioBody()), { code: 'voice_timeout' });
  assert.deepEqual(await readdir(join(root, 'requests')), []);
  hang = false; assert.equal((await p.transcribe(audioBody())).text, 'recovered');
});

test('invalid engine JSON, empty transcription and malformed/oversized WAV cannot escape', async t => {
  const root = await fixture(t); let output = 'bad';
  const p = createVoiceProvider({ root, runProcess: async (_, args) => {
    if (args[0].endsWith('voice-worker.py')) { await writeFile(args[2], output); return ''; }
    return output;
  } });
  await assert.rejects(p.transcribe(audioBody()), { code: 'voice_failed' });
  output = '{"text":" "}'; await assert.rejects(p.transcribe(audioBody()), { code: 'empty_audio' });
  for (const value of [Buffer.alloc(10), wav(16000), wav(24000, 720001)]) {
    output = value; await assert.rejects(p.synthesize(text), { code: 'voice_failed' });
  }
  assert.deepEqual(await readdir(join(root, 'requests')), []);
});

test('authenticated routes return bounded JSON/WAV and reject wrong auth/input', async t => {
  const root = await fixture(t); let calls = 0;
  const voice = createVoiceProvider({ root, runProcess: async (_, args) => {
    calls++;
    if (args[0].endsWith('voice-worker.py')) { await writeFile(args[2], wav(24000)); return ''; }
    return '{"text":"hello"}';
  } });
  const { url } = await serve(t, { configured: { auth: true }, ...voice });
  assert.equal((await post(url, '/speech/transcribe', audioBody(), undefined, 'wrong')).status, 401);
  assert.equal(calls, 0);
  assert.equal((await post(url, '/speech/transcribe', { ...audioBody(), language: 'fr' })).status, 400);
  assert.equal((await post(url, '/speech/transcribe', { ...audioBody(), audioBase64: wav(16000, 160, 0).toString('base64') })).status, 400);
  assert.equal((await post(url, '/speech/synthesize', { ...text, text: 'a'.repeat(301) })).status, 400);
  assert.equal(calls, 0);
  const transcribed = await post(url, '/speech/transcribe', { ...audioBody(), audioBase64: wav(16000, 480000).toString('base64') });
  assert.equal(transcribed.status, 200); assert.deepEqual(await transcribed.json(), { text: 'hello', language: 'en' });
  const spoken = await post(url, '/speech/synthesize', text);
  assert.equal(spoken.headers.get('content-type'), 'audio/wav'); assert.equal(spoken.headers.get('cache-control'), 'no-store');
  assert.deepEqual(Buffer.from(await spoken.arrayBuffer()), wav(24000));
  const big = await post(url, '/speech/transcribe', { audioBase64: 'x'.repeat(1_300_000), language: 'en' });
  assert.equal(big.status, 400); assert.equal((await big.json()).error.code, 'payload_too_large');
});

test('oversized chunked upload gets a structured error before inference', async t => {
  const { url } = await serve(t, { configured: { auth: true }, transcribe: () => assert.fail('must not run') });
  const response = await new Promise((resolve, reject) => {
    const request = http.request(url + '/speech/transcribe', { method: 'POST', headers: {
      Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'Transfer-Encoding': 'chunked',
    } }, res => { const chunks = []; res.on('data', c => chunks.push(c)); res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks)) })); });
    request.on('error', reject); request.write('x'.repeat(700000)); request.end('x'.repeat(700000));
  });
  assert.equal(response.status, 400); assert.equal(response.body.error.code, 'payload_too_large');
});

test('shared admission blocks voice/voice and voice/text until disconnected worker is reaped', async t => {
  const root = await fixture(t); const started = deferred(), stopped = deferred(), release = deferred();
  const voice = createVoiceProvider({ root, runProcess: async (_, args, { signal }) => {
    signal.addEventListener('abort', () => stopped.resolve(), { once: true });
    started.resolve(); await release.promise; return '{"text":"late"}';
  } });
  const { url } = await serve(t, { configured: { auth: true }, ...voice, translate: async () => ({ text: 'ok' }) });
  const controller = new AbortController();
  const request = post(url, '/speech/transcribe', audioBody(), controller.signal);
  const rejection = assert.rejects(request);
  await started.promise;
  assert.equal((await post(url, '/speech/synthesize', text)).status, 429);
  assert.equal((await post(url, '/translate', {})).status, 429);
  controller.abort(); await rejection; await stopped.promise;
  assert.equal((await post(url, '/translate', {})).status, 429);
  release.resolve();
  let response;
  for (let i = 0; i < 100; i++) { response = await post(url, '/translate', {}); if (response.status === 200) break; await delay(5); }
  assert.equal(response.status, 200);
});

test('voice is rejected while any text request including queued text is pending', async t => {
  const first = deferred(), second = deferred(), both = deferred(); let count = 0;
  const { url } = await serve(t, { configured: { auth: true }, transcribe: () => assert.fail('must not run'),
    translate: async () => { count++; if (count === 2) both.resolve(); await (count === 1 ? first.promise : second.promise); return {}; } });
  const a = post(url, '/translate', {}), b = post(url, '/translate', {});
  await both.promise;
  assert.equal((await post(url, '/speech/transcribe', audioBody())).status, 429);
  first.resolve(); await a;
  assert.equal((await post(url, '/speech/transcribe', audioBody())).status, 429);
  second.resolve(); await b;
});

test('server shutdown cancellation reaches the voice child and cleans its request directory', async t => {
  const root = await fixture(t), started = deferred();
  const voice = createVoiceProvider({ root, runProcess: async (_, args, options) => {
    const result = runVoiceProcess(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], options);
    started.resolve(); return result;
  } });
  const { server, url } = await serve(t, { configured: { auth: true }, ...voice });
  const response = post(url, '/speech/transcribe', audioBody());
  await started.promise; server.cancelActiveRequests();
  assert.equal((await response).status, 499);
  assert.deepEqual(await readdir(join(root, 'requests')), []);
});

test('automatic recognition uses model language tags and rejects unsupported or mixed languages', async t => {
  const root = await fixture(t);
  let detected = ['ko-KR'];
  const provider = createVoiceProvider({root,runProcess:async (_,args)=>{
    assert.equal(args[args.indexOf('--language')+1],'auto');
    return JSON.stringify({text:'가상 음성입니다.',languages:detected});
  }});
  assert.deepEqual(await provider.transcribe({...audioBody(),language:'auto'}),{text:'가상 음성입니다.',language:'ko'});
  for (const value of [['en-US'],['ja-JP']]) {
    detected=value;
    assert.equal((await provider.transcribe({...audioBody(),language:'auto'})).language,value[0].slice(0,2));
  }
  for (const value of [[],['fr-FR'],['ko-KR','en-US']]) {
    detected=value;
    await assert.rejects(provider.transcribe({...audioBody(),language:'auto'}),{code:'language_not_detected'});
  }
});
