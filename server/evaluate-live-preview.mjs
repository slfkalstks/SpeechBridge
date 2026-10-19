// Real local-model requests on cumulative synthetic WAV snapshots. No microphone is opened.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { createServer } from './server.mjs';
import { createDatabase } from './db/database.mjs';
import { validateWav } from './voice-provider.mjs';
process.loadEnvFile(new URL('../.runtime/accounts-server.env', import.meta.url));
process.loadEnvFile(new URL('../.runtime/postgres/test.env', import.meta.url));
assert.equal(process.env.PGDATABASE, 'speechbridge_test');
const db = createDatabase(process.env);
const server = createServer({ env: {...process.env, AUTH_MODE:'users'}, database:db });
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin=`http://127.0.0.1:${server.address().port}`;
const username=`preview_${randomUUID().replaceAll('-','').slice(0,18)}`;
let token;
const post=async(path,body)=>{
  const response=await fetch(origin+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});
  if(!response.ok) { const body=await response.json(); throw Object.assign(new Error(`Request failed: ${path}, ${response.status}`), {status:response.status, code:body.error?.code}); }
  return response.json();
};
const report={scope:'Synthetic cumulative PCM snapshots, real local STT/translation, isolated PostgreSQL; no microphone or listening test.',results:[]};
try {
  ({token}=await post('/auth/register',{username,password:'Synthetic-preview-validation-123!'}));
  for(const language of ['ko','en','ja']) {
    const full=validateWav(await readFile(new URL(`../.runtime/voice/fixtures/${language}-asr.wav`,import.meta.url)));
    const sizes=[Math.min(96000,full.length-44),full.length-44];
    for(const size of [...new Set(sizes)]) {
      const audio=Buffer.from(full.subarray(0,size+44)); audio.writeUInt32LE(audio.length-8,4); audio.writeUInt32LE(size,40);
      const start=performance.now();
      let transcript;
      try { transcript=await post('/speech/transcribe',{language:'auto',audioBase64:audio.toString('base64')}); }
      catch(error) {
        if(size===full.length-44)throw error;
        report.results.push({language,audioDurationMs:size/32,previewUnavailable:error.code});
        console.log(`${language} partial: ${error.code}; final retry follows`);continue;
      }
      const sttMs=Math.round(performance.now()-start);
      assert.equal(transcript.language,language);
      const to=language==='en'?'ko':'en';
      const translation=await post('/translate',{text:transcript.text,from:language,to,revision:1});
      assert.ok(translation.text.trim());
      const row={language,audioDurationMs:size/32,transcript:transcript.text,target:to,translation:translation.text,sttMs,totalMs:Math.round(performance.now()-start)};
      if(language==='ja' && size===full.length-44) {
        const changed=await post('/translate',{text:transcript.text,from:'ja',to:'ko',revision:1});
        row.changedTarget='ko'; row.changedTranslation=changed.text;
        const value={id:'preview-duration',title:'합성 음성 검증',createdAt:new Date().toISOString(),translate:true,record:true,first:'JA',second:'KO',ended:false,
          metadataRevision:0,organized:false,organizationAttempted:false,summary:null,organizationSources:{},category:null,workType:null,folderId:null,items:[],
          utterances:[{id:'u1',speaker:'',language:'JA',originalText:transcript.text,text:transcript.text,revision:1,translation:changed.text,translationRevision:1,audioDurationMs:Math.ceil(size/32)}]};
        await post('/data/mutate',{deviceId:'preview-test',mutationId:randomUUID(),entity:'conversation',id:value.id,baseVersion:'0',value});
        assert.deepEqual((await post('/data/snapshot',{deviceId:'second-device'})).archive.conversations[0],value);
        row.databaseRoundTrip=true;
      }
      report.results.push(row);
      console.log(`${language} ${row.audioDurationMs} ms audio: STT ${sttMs} ms, total ${row.totalMs} ms`);
    }
  }
} finally {
  server.cancelActiveRequests();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));
  await db.auth.query('DELETE FROM users WHERE username=$1',[username]);await db.close();
}
await writeFile(new URL('./validation/live-preview.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
