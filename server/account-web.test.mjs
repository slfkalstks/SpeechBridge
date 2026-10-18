import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from './server.mjs';

test('member server serves an accessible deletion flow without credential storage and privacy information',async t=>{
 const server=createServer({env:{AUTH_MODE:'users'},accounts:{admit(){},close(){}}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>{server.closeAllConnections();server.close();});
 const base=`http://127.0.0.1:${server.address().port}`;
 const page=await fetch(base+'/account/delete');assert.equal(page.status,200);
 assert.match(page.headers.get('content-security-policy'),/frame-ancestors 'none'/);
 const html=await page.text();assert.match(html,/SpeechBridge/);assert.match(html,/현재 비밀번호/);assert.match(html,/계정 영구 삭제/);assert.match(html,/aria-live/);
 const js=await (await fetch(base+'/account/app.js')).text();assert.doesNotMatch(js,/localStorage|sessionStorage|document.cookie|innerHTML/);assert.match(js,/\/auth\/delete/);assert.match(js,/confirm: true/);
 const privacy=await fetch(base+'/privacy');assert.equal(privacy.status,200);assert.match(await privacy.text(),/30일/);
 assert.equal((await fetch(base+'/account/../server.mjs')).status,404);
});

test('foreign browser requests cannot submit credentials even with JSON',async t=>{
 let calls=0;const server=createServer({env:{AUTH_MODE:'users'},accounts:{admit(){},handle(){calls++;return {ok:true};},close(){}}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>{server.closeAllConnections();server.close();});
 const response=await fetch(`http://127.0.0.1:${server.address().port}/auth/login`,{method:'POST',headers:{'Content-Type':'application/json','Sec-Fetch-Site':'cross-site'},body:'{}'});
 assert.equal(response.status,403);assert.equal(calls,0);
});
