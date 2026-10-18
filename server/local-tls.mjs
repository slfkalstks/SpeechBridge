import { mkdir, readFile, writeFile, chmod, access, mkdtemp, rm, rename, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join, resolve } from 'node:path';
import { networkInterfaces } from 'node:os';
import { isIP } from 'node:net';
import { X509Certificate, createPrivateKey, randomBytes } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execute=promisify(execFile);
const runtime=fileURLToPath(new URL('../.runtime/',import.meta.url));
export const tlsRoot=join(runtime,'tls');
const exists=async path=>access(path).then(()=>true,()=>false);
export function privateAddress(host) {
  if(isIP(host)!==4)return false;
  const [a,b]=host.split('.').map(Number);
  return host==='127.0.0.1' || a===10 || a===172 && b>=16 && b<=31 || a===192 && b===168;
}
export function localAddress(interfaces=networkInterfaces()) {
  const candidates=Object.entries(interfaces).sort(([a],[b])=>(a==='en0'?-1:b==='en0'?1:a.localeCompare(b)))
    .flatMap(([,values])=>values ?? []).filter(v=>v.family==='IPv4' && !v.internal && privateAddress(v.address));
  if(!candidates.length)throw new Error('No private LAN IPv4 address; connect the Mac to the test Wi-Fi');
  return candidates[0].address;
}
async function openssl(args) {
  try { await execute('/usr/bin/openssl',args,{timeout:30_000,maxBuffer:1024*1024}); }
  catch { throw new Error('Project TLS certificate preparation failed'); }
}
export async function checkedTlsRoot(root=tlsRoot) {
  const path=resolve(root),base=resolve(runtime);
  if(!path.startsWith(base+'/'))throw new Error('TLS files must stay inside the project runtime');
  await mkdir(path,{recursive:true,mode:0o700});
  if(!(await realpath(path)).startsWith((await realpath(base))+'/'))throw new Error('TLS files must stay inside the project runtime');
  await chmod(path,0o700);return path;
}
const validFor = (certificate,days) => Date.parse(certificate.validFrom)<=Date.now() && Date.parse(certificate.validTo)>Date.now()+days*86400000;
async function atomic(root,name,value) {
  const temp=join(root,`.${name}-${randomBytes(8).toString('hex')}`);
  await writeFile(temp,value,{flag:'wx',mode:0o600});await rename(temp,join(root,name));
}
export async function prepareLocalTls({root=tlsRoot,host=localAddress(),port=8443}={}) {
  if(!privateAddress(host))throw new Error('Use a private IPv4 address or 127.0.0.1 for testing');
  if(!Number.isInteger(port) || port<1024 || port>65535)throw new Error('Use an unprivileged TLS port');
  root=await checkedTlsRoot(root);
  const work=await mkdtemp(join(root,'prepare-'));await chmod(work,0o700);
  try {
    const hasCa=await Promise.all(['ca.key','ca.pem','ca.der'].map(name=>exists(join(root,name))));
    if(hasCa.some(Boolean) && !hasCa.every(Boolean))throw new Error('Incomplete project CA; preserve files and inspect before retrying');
    if(!hasCa.every(Boolean)) {
      const config=join(work,'ca.cnf');
      await writeFile(config,'[req]\ndistinguished_name=dn\nx509_extensions=ca\nprompt=no\n[dn]\nCN=SpeechBridge Local Test CA\n[ca]\nbasicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n',{mode:0o600});
      await openssl(['req','-new','-x509','-newkey','rsa:3072','-nodes','-keyout',join(work,'ca.key'),'-out',join(work,'ca.pem'),'-days','365','-config',config]);
      await openssl(['x509','-in',join(work,'ca.pem'),'-outform','DER','-out',join(work,'ca.der')]);
      for(const name of ['ca.key','ca.pem','ca.der'])await atomic(root,name,await readFile(join(work,name)));
    }
    const ca=new X509Certificate(await readFile(join(root,'ca.pem')));
    if(!ca.ca || !ca.verify(ca.publicKey) || !ca.checkPrivateKey(createPrivateKey(await readFile(join(root,'ca.key')))) || !validFor(ca,31)) {
      throw new Error('Project CA is invalid or near expiry; explicitly renew test trust and rebuild apps');
    }
    if(!ca.raw.equals(await readFile(join(root,'ca.der'))))throw new Error('Project CA public files disagree');
    let leaf;
    if(await exists(join(root,'server.pem')) && await exists(join(root,'server.key'))) {
      leaf=new X509Certificate(await readFile(join(root,'server.pem')));
      if(!leaf.checkIP(host) || !leaf.verify(ca.publicKey) || !leaf.checkPrivateKey(createPrivateKey(await readFile(join(root,'server.key')))) || !validFor(leaf,1))leaf=null;
    }
    if(!leaf) {
      const extension=join(work,'server.cnf');
      const addresses=[...new Set([host,'127.0.0.1'])].map(ip=>'IP:'+ip).join(',');
      await writeFile(extension,`basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=${addresses},DNS:localhost\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n`,{mode:0o600});
      await openssl(['req','-new','-newkey','rsa:2048','-nodes','-keyout',join(work,'server.key'),'-out',join(work,'server.csr'),'-subj','/CN=SpeechBridge Local Test Server']);
      await openssl(['x509','-req','-in',join(work,'server.csr'),'-CA',join(root,'ca.pem'),'-CAkey',join(root,'ca.key'),'-set_serial','0x'+randomBytes(16).toString('hex'),'-days','30','-extfile',extension,'-out',join(work,'server.pem')]);
      for(const name of ['server.key','server.pem'])await atomic(root,name,await readFile(join(work,name)));
      leaf=new X509Certificate(await readFile(join(root,'server.pem')));
    }
    for(const name of ['ca.key','ca.pem','ca.der','server.key','server.pem'])await chmod(join(root,name),0o600);
    const config={version:1,host,port,origin:`https://${host}:${port}`,caFingerprint:ca.fingerprint256,expiresAt:new Date(leaf.validTo).toISOString()};
    await atomic(root,'client.properties',`serverUrl=${config.origin}\ncaFingerprint=${config.caFingerprint}\n`);
    await atomic(root,'config.json',JSON.stringify(config,null,2)+'\n');
    return config;
  } finally { await rm(work,{recursive:true,force:true}); }
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const config=await prepareLocalTls({host:process.argv[2] || localAddress()});
    console.log(`Project test HTTPS prepared: ${config.origin}. Private keys remain in .runtime/tls. Debug apps must include this project's public CA.`);
  } catch(error) { console.error(error.message);process.exitCode=1; }
}
