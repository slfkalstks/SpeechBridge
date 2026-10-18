import {readFile} from 'node:fs/promises';
const files=new Map([
 ['/account/delete',['delete.html','text/html']],['/privacy',['privacy.html','text/html']],
 ['/account/app.js',['app.js','text/javascript']],['/account/style.css',['style.css','text/css']],
]);
export async function serveAccountWeb(req,res) {
 const entry=files.get(req.url);if(req.method!=='GET' || !entry)return false;
 const body=await readFile(new URL('./web/'+entry[0],import.meta.url));
 res.writeHead(200,{'Content-Type':entry[1]+'; charset=utf-8','Content-Length':body.length,'Cache-Control':'no-store',
  'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
  'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','X-Frame-Options':'DENY','Permissions-Policy':'camera=(), microphone=(), geolocation=()'});
 res.end(body);return true;
}
