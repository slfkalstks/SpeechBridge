// Package only completed builds; public trust/address metadata accompanies the APK.
import { readFile, writeFile, copyFile, access } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
const run=promisify(execFile);
const root=fileURLToPath(new URL('../',import.meta.url));
const directory=join(root,'.runtime/demo');
const app=join(root,'.runtime/xcode-derived/Build/Products/Debug-iphonesimulator/SpeechBridge.app');
const apk=join(directory,'SpeechBridge-demo.apk');
const hash=async path=>createHash('sha256').update(await readFile(path)).digest('hex');
const command=process.argv[2];
if(!['package','check','launch'].includes(command))throw new Error('Use package, check or launch.');
const config=JSON.parse(await readFile(join(root,'.runtime/tls/config.json'),'utf8'));
const manifest=join(directory,'build.json');
if(command==='package') {
  const generated=await readFile(join(root,'shared/build/generated/serviceConfig/kc/ac/uc/speechbridge/ServiceConfig.kt'),'utf8');
  if(!generated.includes(JSON.stringify(config.origin)))throw new Error('Rebuild the debug apps for the current HTTPS address.');
  await access(join(app,'SpeechBridge'));
  await copyFile(join(root,'androidApp/build/outputs/apk/debug/androidApp-debug.apk'),apk);
  await writeFile(manifest,JSON.stringify({createdAt:new Date().toISOString(),origin:config.origin,caFingerprint:config.caFingerprint,
    apkSha256:await hash(apk),simulatorSha256:await hash(join(app,'SpeechBridge')),simulatorDebugSha256:await hash(join(app,'SpeechBridge.debug.dylib'))},null,2)+'\n',{mode:0o600});
  console.log('Prepared .runtime/demo/SpeechBridge-demo.apk and signed iOS simulator app.');
} else {
  const built=JSON.parse(await readFile(manifest,'utf8'));
  if(built.origin!==config.origin || built.caFingerprint!==config.caFingerprint ||
    built.apkSha256!==await hash(apk) || built.simulatorSha256!==await hash(join(app,'SpeechBridge')) ||
    built.simulatorDebugSha256!==await hash(join(app,'SpeechBridge.debug.dylib')))throw new Error('Demo builds changed or use a different server. Run scripts/demo-build.sh.');
  if(command==='launch') {
    const devices=JSON.parse((await run('xcrun',['simctl','list','devices','available','--json'])).stdout);
    const candidates=Object.values(devices.devices).flat().filter(d=>d.isAvailable && d.name.startsWith('iPhone'));
    const device=candidates.find(d=>d.state==='Booted') || candidates.find(d=>d.name==='iPhone 17 Pro');
    if(!device)throw new Error('Open an available iPhone simulator first.');
    if(device.state!=='Booted')await run('xcrun',['simctl','boot',device.udid]);
    await run('xcrun',['simctl','bootstatus',device.udid,'-b']);
    await run('xcrun',['simctl','install',device.udid,app]);
    await run('xcrun',['simctl','launch',device.udid,'kc.ac.uc.speechbridge.SpeechBridge']);
    console.log('iPhone simulator demo launched. Open Simulator to present.');
  }else console.log('Demo artifacts match the prepared HTTPS server.');
}
