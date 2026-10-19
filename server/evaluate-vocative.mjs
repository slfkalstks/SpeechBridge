// Opt-in real local-model regression. Synthetic text only; never opens user data.
import assert from 'node:assert/strict';
import {writeFile,readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {createProviders} from './provider.mjs';
// Known limitations intentionally make this opt-in evaluator fail; it is not the CI suite.
const before=true;
const cases=[
 {from:'ko',to:'en',text:'유키씨 금요일까지 수정한 일정을 보내주세요.',check:s=>/^Yuki[,，]/i.test(s) && !/to me|send Yuki/i.test(s) && /Friday/.test(s),expected:'Yuki is addressed; no invented recipient; Friday retained.'},
 {from:'ko',to:'en',text:'유키 씨에게 금요일까지 수정한 일정을 보내주세요.',check:s=>/to Yuki|send Yuki/i.test(s) && !/^Yuki[,，]/i.test(s) && /Friday/.test(s),expected:'Yuki is recipient with no invented gender/honorific; Friday retained.'},
 {from:'ja',to:'ko',text:'ユキさん、金曜日までに修正した予定を送ってください。',check:s=>/유키/.test(s) && !/유키.*에게/.test(s) && /금요일/.test(s),expected:'Yuki is addressed; Friday retained.'},
 {from:'en',to:'ko',text:'Yuki, please send the updated schedule by Friday.',check:s=>/유키/.test(s) && !/유키.*에게/.test(s) && /금요일/.test(s),expected:'Yuki is addressed; Friday retained.'},
 {from:'ko',to:'en',text:'회의는 금요일이 아니라 목요일 오후 2시입니다.',check:s=>/Thursday/.test(s) && /not.*Friday|rather than Friday/i.test(s) && /2.*(?:p\.?m\.?|afternoon)|14:00/i.test(s),expected:'Thursday 14:00, not Friday; negation and time retained.'}
];
const provider=createProviders({AI_PROVIDER:'ollama-local'}),results=[];
for(const c of cases){const start=performance.now();const r=await provider.translate({...c,revision:1});results.push({from:c.from,to:c.to,source:c.text,translation:r.text,expected:c.expected,passed:c.check(r.text),milliseconds:Math.round(performance.now()-start)});}
const report={createdAt:new Date().toISOString(),scope:'Five synthetic typed inputs, real local TranslateGemma. Targeted semantic predicates, not general accuracy or microphone validation.',providerSha256:createHash('sha256').update(await readFile(new URL('./local-provider.mjs',import.meta.url))).digest('hex'),results};
await writeFile(new URL(`./validation/vocative-${before?'before':'after'}.json`,import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(`${results.filter(x=>x.passed).length}/${results.length} targeted semantic cases passed.`);
assert.ok(results.every(x=>x.passed),'Review the synthetic report for changed semantic roles.');
