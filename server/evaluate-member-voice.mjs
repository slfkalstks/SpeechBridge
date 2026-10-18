// Installed local models + real PostgreSQL membership; synthetic audio only.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { createServer } from './server.mjs';
import { createDatabase } from './db/database.mjs';
import { validateWav } from './voice-provider.mjs';

process.loadEnvFile(new URL('../.runtime/postgres/test.env', import.meta.url));
assert.equal(process.env.PGDATABASE, 'speechbridge_test');
const env = { ...process.env, AUTH_MODE: 'users', AI_PROVIDER: 'ollama-local', SPEECH_PROVIDER: 'local-process' };
const database = createDatabase(env);
const server = createServer({ env, database });
const username = `voice_${randomUUID().replaceAll('-', '').slice(0,20)}`;
const fullFlow = process.argv.includes('--with-translation');
const report = { createdAt: new Date().toISOString(), scope: 'Synthetic audio through member-authenticated HTTP and real local models. No microphone or listening evaluation.', results: [] };
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const post = (path, body, token) => fetch(origin+path, { method:'POST', headers: {
  'Content-Type':'application/json', ...(token ? { Authorization:`Bearer ${token}` } : {}),
}, body:JSON.stringify(body) });
const checked = async response => {
  if(!response.ok) throw new Error(`API ${response.status}: ${(await response.json()).error?.code}`);
  return response;
};
try {
  const session = await (await checked(await post('/auth/register', { username, password:'Synthetic-voice-validation-123!' }))).json();
  assert.ok(session.user.capabilities.includes('speech/transcribe'));
  assert.equal((await post('/speech/synthesize',{text:'Hello',language:'en'})).status,401);
  for(const language of ['ko','en','ja']) {
    const audio = await readFile(new URL(`../.runtime/voice/fixtures/${language}-asr.wav`,import.meta.url));
    let started = performance.now();
    const result = await (await checked(await post('/speech/transcribe',{audioBase64:audio.toString('base64'),language:'auto'},session.token))).json();
    const row = { expectedLanguage:language, transcription:result, sttMilliseconds:Math.round(performance.now()-started) };
    assert.equal(result.language,language);
    let spokenText = result.text, spokenLanguage = language;
    if(fullFlow) {
      spokenLanguage = language === 'ko' ? 'en' : 'ko';
      started = performance.now();
      const translation = await (await checked(await post('/translate', { text:result.text, from:language, to:spokenLanguage, revision:1 },session.token))).json();
      assert.equal(translation.revision,1); assert.ok(translation.text.trim());
      spokenText = translation.text;
      row.translation = translation; row.targetLanguage = spokenLanguage;
      row.translationMilliseconds = Math.round(performance.now()-started);
      const conversation = { id:`voice_${language}`, title:'합성 음성 통합 검증', createdAt:new Date().toISOString(), translate:true, record:true,
        first:language.toUpperCase(), second:spokenLanguage.toUpperCase(), ended:false, category:null, workType:null, folderId:null,
        metadataRevision:0, organized:false, organizationAttempted:false, summary:null, organizationSources:{}, items:[],
        utterances:[{ id:`utterance_${language}`, speaker:'', language:language.toUpperCase(), originalText:result.text, text:result.text,
          revision:1, translation:translation.text, translationRevision:1 }] };
      await checked(await post('/data/mutate',{ deviceId:'synthetic_voice_flow',mutationId:randomUUID(),entity:'conversation',
        id:conversation.id,baseVersion:'0',value:conversation },session.token));
      const snapshot = await (await checked(await post('/data/snapshot',{deviceId:'synthetic_voice_flow'},session.token))).json();
      assert.deepEqual(snapshot.archive.conversations.find(c=>c.id===conversation.id),conversation);
      row.databaseRoundTrip = true;
    }
    started = performance.now();
    const response = await checked(await post('/speech/synthesize',{text:spokenText,language:spokenLanguage},session.token));
    assert.equal(response.headers.get('cache-control'),'no-store');
    const wav = validateWav(Buffer.from(await response.arrayBuffer()),24000,false);
    row.ttsMilliseconds = Math.round(performance.now()-started); row.audioSeconds = (wav.length-44)/48000;
    report.results.push(row);
    console.log(`${language}: automatic language matched; STT ${row.sttMilliseconds} ms, TTS ${row.ttsMilliseconds} ms`);
  }
  await checked(await post('/auth/logout',{},session.token));
  assert.equal((await post('/speech/synthesize',{text:'Hello',language:'en'},session.token)).status,401);
  report.revokedSessionRejected = true;
  report.temporaryFolders = (await readdir(new URL('../.runtime/voice/requests/',import.meta.url))).filter(name=>name.startsWith('request-')).length;
  assert.equal(report.temporaryFolders,0);
} finally {
  server.cancelActiveRequests(); server.closeAllConnections();
  await new Promise(resolve=>server.close(resolve));
  await database.auth.query('DELETE FROM users WHERE username=$1',[username]);
  await database.close();
}
await writeFile(new URL(fullFlow ? './validation/member-voice-flow.json' : './validation/member-voice.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
