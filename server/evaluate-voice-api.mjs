// Real offline models through an ephemeral authenticated loopback API. Synthetic inputs only.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { createServer } from './server.mjs';
import { createProviders } from './provider.mjs';
import { runVoiceProcess, validateWav } from './voice-provider.mjs';

const env = { AI_PROVIDER: 'ollama-local', SPEECH_PROVIDER: 'local-process', APP_ACCESS_TOKEN: randomBytes(32).toString('hex') };
const root = new URL('../', import.meta.url);
const output = new URL('.runtime/voice/api-fixtures/', root);
const reportFile = new URL('docs/validation/local-voice-api.json', root);
const report = { date: new Date().toISOString(), scope: 'synthetic samples via authenticated HTTP; no microphone or listening evaluation',
  processPolicy: 'new process per request, 90s deadline, close before release', results: [] };
const save = () => writeFile(reportFile, JSON.stringify(report, null, 2) + '\n');
let processStarted, processFinished;
const providers = createProviders(env, { voice: { runProcess: async (...args) => {
  const promise = runVoiceProcess(...args);
  processStarted?.();
  try { return await promise; } finally { processFinished?.(); }
} } });
const server = createServer({ env, providers });
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const url = `http://127.0.0.1:${server.address().port}`;
const post = (path, body, signal) => fetch(url + path, { method: 'POST', signal,
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${env.APP_ACCESS_TOKEN}` }, body: JSON.stringify(body) });
async function checked(response) {
  if (!response.ok) throw new Error(`API ${response.status}: ${(await response.json()).error?.code}`);
  return response;
}
try {
  await mkdir(output, { recursive: true });
  report.health = await (await fetch(url + '/health')).json();
  assert.equal(report.health.available.speech, true);
  const cases = JSON.parse(await readFile(new URL('docs/validation/local-voice-smoke.json', root))).results;
  for (const item of cases) {
    const result = { language: item.language, input: item.input };
    report.results.push(result);
    const audio = await readFile(new URL(`.runtime/voice/fixtures/${item.language}-asr.wav`, root));
    let started = performance.now();
    result.transcription = await (await checked(await post('/speech/transcribe', { language: item.language, audioBase64: audio.toString('base64') }))).json();
    result.sttHttpSecondsIncludingLoad = (performance.now() - started) / 1000;
    started = performance.now();
    const synthesized = await checked(await post('/speech/synthesize', { language: item.language, text: item.input }));
    assert.equal(synthesized.headers.get('content-type'), 'audio/wav');
    assert.equal(synthesized.headers.get('cache-control'), 'no-store');
    const speech = validateWav(Buffer.from(await synthesized.arrayBuffer()), 24000, false);
    result.ttsHttpSecondsIncludingLoad = (performance.now() - started) / 1000;
    result.audioSeconds = (speech.length - 44) / 48000;
    result.wavBytes = speech.length;
    await writeFile(new URL(`${item.language}-tts.wav`, output), speech, { mode: 0o600 });
    await save();
    console.log(`${item.language}: STT ${result.sttHttpSecondsIncludingLoad.toFixed(3)}s, TTS ${result.ttsHttpSecondsIncludingLoad.toFixed(3)}s`);
  }
  const started = new Promise(resolve => { processStarted = resolve; });
  const finished = new Promise(resolve => { processFinished = resolve; });
  const controller = new AbortController();
  const cancelled = post('/speech/synthesize', { text: 'This synthetic request is cancelled before completion.', language: 'en' }, controller.signal);
  const rejected = assert.rejects(cancelled);
  await started;
  // Cancel during model startup rather than just cancelling an HTTP upload.
  await new Promise(resolve => setTimeout(resolve, 300));
  const cancelTime = performance.now();
  controller.abort(); await rejected; await finished;
  report.cancelToProcessCloseSeconds = (performance.now() - cancelTime) / 1000;
  processStarted = undefined; processFinished = undefined;
  // A successful next request proves admission recovered after the actual child closed.
  const sample = await readFile(new URL('.runtime/voice/fixtures/en-asr.wav', root));
  for (let attempt = 0; attempt < 10; attempt++) {
    const response = await post('/speech/transcribe', { language: 'en', audioBase64: sample.toString('base64') });
    if (response.status === 429) { await response.arrayBuffer(); await new Promise(resolve => setTimeout(resolve, 20)); continue; }
    await checked(response); report.afterCancel = await response.json(); break;
  }
  assert.ok(report.afterCancel);
  report.temporaryRequestsRemaining = (await readdir(new URL('.runtime/voice/requests/', root))).length;
  assert.equal(report.temporaryRequestsRemaining, 0);
  await save();
  console.log('Cancellation, recovery and temporary-file cleanup verified.');
} catch (error) {
  report.error = error.message; await save(); throw error;
} finally {
  server.cancelActiveRequests();
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
}
