// Compare paired synthetic runs, keeping failures separate from latency statistics.
import {readFile,writeFile} from 'node:fs/promises';
const labels=process.argv.slice(2);
if(labels.length!==2 || labels.some(x=>!/^[a-z][a-z0-9-]{0,40}$/.test(x)))throw new Error('Usage: node server/compare-translation-benchmarks.mjs before after');
const [before,after]=await Promise.all(labels.map(x=>readFile(new URL(`../.ecc/benchmarks/translation-${x}.json`,import.meta.url),'utf8').then(JSON.parse)));
if(JSON.stringify(before.fixtures)!==JSON.stringify(after.fixtures) || JSON.stringify(before.models)!==JSON.stringify(after.models))throw new Error('Inputs/model digests differ');
const report={createdAt:new Date().toISOString(),runs:labels,groups:{},outputComparison:{paired:0,identical:0,differences:[]},concurrent:{}};
for(const group of Object.keys(before.summary)) {
 const b=before.summary[group],a=after.summary[group];
 if(b.n!==a.n)throw new Error('Sample counts differ');
 report.groups[group]={before:b,after:a,meanReductionPercent:100*(b.meanMs-a.meanMs)/b.meanMs,p50ReductionPercent:100*(b.p50Ms-a.p50Ms)/b.p50Ms};
}
for(const b of before.results){
 const a=after.results.find(x=>x.group===b.group&&x.index===b.index);
 if(!a||a.status!==200||b.status!==200)throw new Error('Missing or failed paired result');
 report.outputComparison.paired++;
 if(a.result.text===b.result.text)report.outputComparison.identical++;
 else report.outputComparison.differences.push({group:b.group,index:b.index,fixture:b.fixture,before:b.result.text,after:a.result.text});
}
for(const [label,run] of [[labels[0],before],[labels[1],after]])report.concurrent[label]={requests:run.concurrent.length,statusCounts:run.concurrent.reduce((a,r)=>(a[r.status]=(a[r.status]||0)+1,a),{}),successfulCompletionMs:run.concurrent.filter(r=>r.status===200).map(r=>r.elapsedMs)};
report.preparedFirst=after.preparedFirst;
report.residentAfter=after.residentAfter;
await writeFile(new URL('../.ecc/benchmarks/translation-comparison.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
