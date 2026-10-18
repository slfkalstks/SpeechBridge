import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { ApiError, createProviders } from './provider.mjs';
import { createDatabase } from './db/database.mjs';
import { createPostgresAccounts } from './db/accounts.mjs';
import { createUserData } from './db/user-data.mjs';
import { createOrganizationJobs } from './db/organization-jobs.mjs';
import { createOrganizationWorker } from './organization-worker.mjs';

export function createServer({ env = process.env, providers = createProviders(env), now = Date.now, accounts: suppliedAccounts, database: suppliedDatabase, organizationJobs: suppliedJobs } = {}) {
  const mode = env.AUTH_MODE || "development";
  if (!["users", "development"].includes(mode)) throw new Error("Invalid AUTH_MODE");
  const database = suppliedDatabase || (mode === 'users' && !suppliedAccounts ? createDatabase(env) : null);
  const accounts = mode === "users" ? suppliedAccounts || createPostgresAccounts({ db: database, now }) : null;
  const data = database && mode === 'users' ? createUserData(database) : null;
  let active = 0;
  let textActive = 0;
  let voiceActive = false;
  const jobs=suppliedJobs || (data ? createOrganizationJobs(database) : null);
  const worker=jobs && createOrganizationWorker({jobs,organize:(input,context)=>providers.organize(input,context),
    acquire:()=>{
      if(voiceActive || textActive>0) return null;
      textActive++;
      return ()=>{textActive--;};
    }});
  const controllers = new Set();
  let windowStart = now();
  let count = 0;
  const send = (res, status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    res.end(JSON.stringify(body));
  };
  const server = http.createServer(async (req, res) => {
    let acquired = false;
    let admission;
    let user;
    const controller = new AbortController();
    controllers.add(controller);
    const cancel = () => controller.abort();
    const closed = () => { if (!res.writableEnded) cancel(); };
    req.on('aborted', cancel);
    res.on('close', closed);
    try {
      if (req.method === 'GET' && req.url === '/health') {
        if (accounts) { send(res, 200, { status: 'ok', configured: { auth: true }, authentication: 'users' }); return; }
        const health = providers.health ? await providers.health({ signal: controller.signal }) : { status: 'ok', configured: providers.configured };
        if (!res.destroyed) send(res, 200, health); return;
      }
      if (accounts && req.method === 'GET' && req.url === '/auth/me') {
        const { hash, ...user } = await accounts.authenticate(req.headers.authorization);
        send(res, 200, { user }); return;
      }
      const authRoute = accounts && ['/auth/register', '/auth/login', '/auth/logout'].includes(req.url);
      const dataRoute = data && ['/data/snapshot', '/data/mutate', '/data/changes'].includes(req.url);
      if (req.method !== 'POST' || (!authRoute && !dataRoute && !['/translate', '/extract', '/organize', '/speech/token', '/speech/transcribe', '/speech/synthesize'].includes(req.url))) throw new ApiError(404, 'not_found', '경로를 찾을 수 없습니다.');
      if (authRoute) accounts.admit(req.socket.remoteAddress || 'unknown');
      else if (accounts) { user = await accounts.authenticate(req.headers.authorization); accounts.authorize(user, req.url); }
      else {
        if (!providers.configured.auth) throw new ApiError(503, 'auth_not_configured', '서버 접속 인증 설정이 필요합니다.');
        const expected = Buffer.from(`Bearer ${env.APP_ACCESS_TOKEN}`);
        const received = Buffer.from(req.headers.authorization || '');
        if (expected.length !== received.length || !timingSafeEqual(expected, received)) throw new ApiError(401, 'unauthorized', '접속 인증에 실패했습니다.');
      }
      if (!req.headers['content-type']?.startsWith('application/json')) throw new ApiError(400, 'invalid_content_type', 'JSON 요청이 필요합니다.');
      if (now() - windowStart >= 60000) { windowStart = now(); count = 0; }
      if (++count > 60 || active >= 6) throw new ApiError(429, 'busy', '잠시 후 다시 시도해 주세요.');
      active++; acquired = true;
      const maxBody = authRoute ? 2048 : dataRoute ? 2 * 1024 * 1024 : req.url === '/speech/transcribe' ? 1_300_000 : 256 * 1024;
      const oversized = () => new ApiError(400, 'payload_too_large', '한 번에 처리할 입력이 너무 큽니다. 나누어 요청해 주세요.');
      if (Number(req.headers['content-length']) > maxBody) { req.resume(); throw oversized(); }
      let length = 0;
      const chunks = [];
      for await (const chunk of req.iterator({ destroyOnReturn: false })) {
        length += chunk.length;
        if (length > maxBody) { req.resume(); throw oversized(); }
        chunks.push(chunk);
      }
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new ApiError(400, 'invalid_json', 'JSON 형식을 확인해 주세요.'); }
      if (!body || Array.isArray(body) || typeof body !== 'object') throw new ApiError(400, 'invalid_input', '입력 형식을 확인해 주세요.');
      controller.signal.throwIfAborted();
      if (authRoute) {
        const result = await accounts.handle(req.url, body, req.headers.authorization, req.socket.remoteAddress || 'unknown');
        if (!res.destroyed) send(res, 200, result); return;
      }
      if (dataRoute) {
        const result = req.url === '/data/mutate' ? await data.mutate(user.id, body)
          : req.url === '/data/changes' ? await data.changes(user.id, body.after) : await data.snapshot(user.id, body.deviceId, body.platform);
        if (!res.destroyed && !controller.signal.aborted) send(res, 200, result);
        return;
      }
      // Count text work including its bounded provider queue. Voice never queues.
      const isVoice = ['/speech/transcribe', '/speech/synthesize'].includes(req.url);
      const isText = ['/translate', '/extract', '/organize'].includes(req.url);
      if ((isVoice && (voiceActive || textActive > 0)) || (isText && voiceActive)) {
        throw new ApiError(429, 'busy', 'AI 서버가 처리 중입니다. 잠시 후 다시 시도해 주세요.');
      }
      if (isVoice) { voiceActive = true; admission = 'voice'; }
      if (isText) { textActive++; admission = 'text'; }
      const context = { signal: controller.signal };
      const result = req.url === '/translate' ? await providers.translate(body, context)
        : req.url === '/organize' ? await providers.organize(body, context)
        : req.url === '/extract' ? await providers.extract(body, context)
        : req.url === '/speech/transcribe' ? await providers.transcribe(body, context)
        : req.url === '/speech/synthesize' ? await providers.synthesize(body, context) : await providers.speechToken(context);
      if (!res.destroyed && !controller.signal.aborted) {
        if (req.url === '/speech/synthesize') {
          res.writeHead(200, { 'Content-Type': 'audio/wav', 'Content-Length': result.length,
            'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
          res.end(result);
        } else send(res, 200, result);
      }
    } catch (error) {
      const safe = error instanceof ApiError ? error : new ApiError(500, 'internal_error', '요청을 처리하지 못했습니다.');
      if (!res.destroyed) send(res, safe.status, { error: { code: safe.code, message: safe.message } });
    } finally {
      if (acquired) active--;
      if (admission === 'voice') voiceActive = false;
      if (admission === 'text') textActive--;
      controllers.delete(controller);
      req.off('aborted', cancel); res.off('close', closed);
    }
  });
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.stopOrganizationWorker = async () => { await worker?.stop(); };
  server.cancelActiveRequests = () => { for (const controller of controllers) controller.abort(); void worker?.stop(); };
  server.ready = async () => {
    await database?.ready();
    if(server.listening) worker?.start(); else server.once('listening',()=>worker?.start());
  };
  server.once('close', () => {
    void (async()=>{await worker?.stop();if(database && !suppliedDatabase) await database.close();})().catch(()=>{});
  });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  // CLI defaults to individual accounts. Legacy shared-token mode must be explicit.
  const env = { ...process.env, AUTH_MODE: process.env.AUTH_MODE || 'users' };
  if (!['127.0.0.1', 'localhost', '::1'].includes(env.HOST || '127.0.0.1')) throw new Error('Bind the Mac API to loopback and expose it through an HTTPS proxy');
  const server = createServer({ env });
  await server.ready();
  const port = Number(process.env.PORT || 8787);
  const host = process.env.HOST || '127.0.0.1';
  server.listen(port, host, () => console.log(`SpeechBridge server listening on ${host}:${port}`));
  const shutdown = () => {
    server.cancelActiveRequests();
    server.close();
    server.closeAllConnections();
    // Do not process.exit(): let children close and temporary audio cleanup finish.
  };
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
  // Never log request bodies, authorization headers, provider keys or raw upstream errors.
}
