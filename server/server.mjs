import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { ApiError, createProviders } from './provider.mjs';

export function createServer({ env = process.env, providers = createProviders(env), now = Date.now } = {}) {
  let active = 0;
  let textActive = 0;
  let voiceActive = false;
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
    const controller = new AbortController();
    controllers.add(controller);
    const cancel = () => controller.abort();
    const closed = () => { if (!res.writableEnded) cancel(); };
    req.on('aborted', cancel);
    res.on('close', closed);
    try {
      if (req.method === 'GET' && req.url === '/health') {
        const health = providers.health ? await providers.health({ signal: controller.signal }) : { status: 'ok', configured: providers.configured };
        if (!res.destroyed) send(res, 200, health); return;
      }
      if (req.method !== 'POST' || !['/translate', '/extract', '/organize', '/speech/token', '/speech/transcribe', '/speech/synthesize'].includes(req.url)) throw new ApiError(404, 'not_found', '경로를 찾을 수 없습니다.');
      if (!providers.configured.auth) throw new ApiError(503, 'auth_not_configured', '서버 접속 인증 설정이 필요합니다.');
      const expected = Buffer.from(`Bearer ${env.APP_ACCESS_TOKEN}`);
      const received = Buffer.from(req.headers.authorization || '');
      if (expected.length !== received.length || !timingSafeEqual(expected, received)) throw new ApiError(401, 'unauthorized', '접속 인증에 실패했습니다.');
      if (!req.headers['content-type']?.startsWith('application/json')) throw new ApiError(400, 'invalid_content_type', 'JSON 요청이 필요합니다.');
      if (now() - windowStart >= 60000) { windowStart = now(); count = 0; }
      if (++count > 60 || active >= 6) throw new ApiError(429, 'busy', '잠시 후 다시 시도해 주세요.');
      active++; acquired = true;
      const maxBody = req.url === '/speech/transcribe' ? 1_300_000 : 256 * 1024;
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
  server.cancelActiveRequests = () => { for (const controller of controllers) controller.abort(); };
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createServer();
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
