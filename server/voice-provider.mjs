import { spawn } from 'node:child_process';
import { constants } from 'node:fs';
import { access, chmod, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ApiError } from './errors.mjs';

const runtime = fileURLToPath(new URL('../.runtime/voice/', import.meta.url));
const languages = new Set(['ko', 'en', 'ja']);
export const STT_MAX_BYTES = 960_044;
export const TTS_MAX_BYTES = 1_440_044;
const bad = () => new ApiError(400, 'invalid_audio', '16kHz mono PCM16 WAV, 최대 30초 음성이 필요합니다.');
const failed = () => new ApiError(502, 'voice_failed', '음성을 처리하지 못했습니다. 다시 시도해 주세요.');
const cancelled = () => new ApiError(499, 'request_cancelled', '요청을 취소했습니다.');
const missing = () => new ApiError(503, 'voice_not_ready', 'Mac 음성 모델과 실행 환경을 확인해 주세요.');

// Canonical 44-byte WAV only: no metadata chunks, compressed data or trailing bytes.
export function validateWav(audio, rate = 16000, rejectSilence = true) {
  if (!Buffer.isBuffer(audio) || audio.length <= 44 || audio.length > 44 + rate * 2 * 30 ||
      audio.toString('latin1', 0, 4) !== 'RIFF' || audio.readUInt32LE(4) !== audio.length - 8 ||
      audio.toString('latin1', 8, 16) !== 'WAVEfmt ' || audio.readUInt32LE(16) !== 16 ||
      audio.readUInt16LE(20) !== 1 || audio.readUInt16LE(22) !== 1 ||
      audio.readUInt32LE(24) !== rate || audio.readUInt32LE(28) !== rate * 2 ||
      audio.readUInt16LE(32) !== 2 || audio.readUInt16LE(34) !== 16 ||
      audio.toString('latin1', 36, 40) !== 'data' || audio.readUInt32LE(40) !== audio.length - 44 ||
      (audio.length - 44) % 2 !== 0) throw bad();
  if (rejectSilence) {
    let squares = 0;
    for (let i = 44; i < audio.length; i += 2) squares += audio.readInt16LE(i) ** 2;
    if (squares / ((audio.length - 44) / 2) <= 16 ** 2) {
      throw new ApiError(400, 'empty_audio', '음성이 비어 있거나 너무 작습니다. 다시 녹음해 주세요.');
    }
  }
  return audio;
}

export function validateTranscription(body) {
  if (!languages.has(body?.language)) throw new ApiError(400, 'invalid_language', '한국어·영어·일본어만 지원합니다.');
  const encoded = body.audioBase64;
  if (typeof encoded !== 'string' || encoded.length > Math.ceil(STT_MAX_BYTES / 3) * 4 ||
      !encoded.length || encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) throw bad();
  const audio = Buffer.from(encoded, 'base64');
  if (audio.toString('base64') !== encoded) throw bad();
  return { audio: validateWav(audio), language: body.language };
}

export function validateSynthesis(body) {
  if (!languages.has(body?.language)) throw new ApiError(400, 'invalid_language', '한국어·영어·일본어만 지원합니다.');
  if (typeof body.text !== 'string' || !body.text.trim() || [...body.text].length > 300 ||
      /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(body.text)) {
    throw new ApiError(400, 'invalid_input', '읽을 문장은 1~300자로 입력해 주세요.');
  }
  return { text: body.text.trim(), language: body.language };
}

// Do not settle on abort/error/exit: close confirms that the child and its stdio are reaped.
export function runVoiceProcess(command, args, { signal, input, cwd, env, killGraceMs = 500 } = {}) {
  signal?.throwIfAborted();
  return new Promise((resolve, reject) => {
    let child, failure, killTimer, size = 0;
    const chunks = [];
    const kill = type => {
      try { process.kill(-child.pid, type); } catch (error) { if (error.code !== 'ESRCH') child.kill(type); }
    };
    const stop = error => {
      if (failure) return;
      failure = error;
      if (child.pid) {
        kill('SIGTERM');
        killTimer = setTimeout(() => kill('SIGKILL'), killGraceMs);
      }
    };
    const abort = () => stop(signal.reason instanceof ApiError ? signal.reason : cancelled());
    try {
      child = spawn(command, args, { cwd, env, detached: true, stdio: ['pipe', 'pipe', 'ignore'] });
    } catch { reject(failed()); return; }
    child.on('error', () => stop(failed()));
    child.stdout.on('data', chunk => {
      size += chunk.length;
      if (size > 256 * 1024) stop(failed()); else chunks.push(chunk);
    });
    child.stdin.on('error', () => stop(failed()));
    child.on('close', code => {
      clearTimeout(killTimer);
      signal?.removeEventListener('abort', abort);
      if (failure) reject(failure);
      else if (code !== 0) reject(failed());
      else resolve(Buffer.concat(chunks).toString('utf8'));
    });
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
    child.stdin.end(input);
  });
}

async function prepareTemporaryRoot(root) {
  await mkdir(root, { recursive: true, mode: 0o700 });
  await chmod(root, 0o700);
  // Only this feature's dead-owner request folders; preserve other servers and model files.
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const match = /^request-(\d+)-[A-Za-z0-9]+$/.exec(entry.name);
    if (!match || !entry.isDirectory()) continue;
    try { process.kill(Number(match[1]), 0); }
    catch (error) { if (error.code === 'ESRCH') await rm(join(root, entry.name), { recursive: true, force: true }); }
  }
}

export function createVoiceProvider({
  root = runtime, timeoutMs = 90_000, runProcess = runVoiceProcess,
} = {}) {
  const tempRoot = join(root, 'requests');
  const python = join(root, 'venv/bin/python');
  const nemo = join(root, 'nemo-speech-0.2.0-macos-aarch64-metal/bin/nemo-speech');
  const asrModel = join(root, 'models/nemotron-3.5-asr-streaming-0.6b.q8_0.gguf');
  const ttsModel = join(root, 'models/qwen3-tts-0.6b');
  const worker = fileURLToPath(new URL('./voice-worker.py', import.meta.url));
  // Convert startup failure into readiness, never an unhandled rejected promise.
  const prepared = prepareTemporaryRoot(tempRoot).then(() => true, () => false);
  let active = false;
  async function ready() {
    if (!await prepared) return false;
    try {
      await Promise.all([access(python, constants.X_OK), access(nemo, constants.X_OK), access(asrModel, constants.R_OK),
        access(worker, constants.R_OK), access(join(ttsModel, 'config.json'), constants.R_OK)]);
      const weights = (await readdir(ttsModel)).filter(name => name.endsWith('.safetensors'));
      return weights.length > 0 && (await Promise.all(weights.map(name => stat(join(ttsModel, name))))).every(s => s.size > 0);
    } catch { return false; }
  }
  async function run(context, work) {
    if (active) throw new ApiError(429, 'busy', '음성을 처리 중입니다. 잠시 후 다시 시도해 주세요.');
    active = true;
    const controller = new AbortController();
    const abort = () => controller.abort(cancelled());
    context?.signal?.addEventListener('abort', abort, { once: true });
    if (context?.signal?.aborted) abort();
    const timer = setTimeout(() => controller.abort(new ApiError(504, 'voice_timeout', '음성 처리 시간이 초과되었습니다.')), timeoutMs);
    let directory;
    try {
      controller.signal.throwIfAborted();
      if (!await ready()) throw missing();
      controller.signal.throwIfAborted();
      directory = await mkdtemp(join(tempRoot, `request-${process.pid}-`));
      await chmod(directory, 0o700);
      const result = await work(directory, controller.signal);
      controller.signal.throwIfAborted();
      return result;
    } catch (error) {
      if (controller.signal.aborted) throw controller.signal.reason;
      throw error instanceof ApiError ? error : failed();
    } finally {
      clearTimeout(timer);
      context?.signal?.removeEventListener('abort', abort);
      try { if (directory) await rm(directory, { recursive: true, force: true }); }
      finally { active = false; }
    }
  }
  // No API credentials in child environment, and no online model fallback.
  const childEnv = directory => ({ PATH: '/usr/bin:/bin', HOME: directory, TMPDIR: directory,
    HF_HOME: join(root, 'hf'), HF_HUB_OFFLINE: '1', TRANSFORMERS_OFFLINE: '1',
    HF_HUB_DISABLE_TELEMETRY: '1', PYTHONDONTWRITEBYTECODE: '1', TOKENIZERS_PARALLELISM: 'false' });
  return {
    ready,
    async transcribe(body, context) {
      const { audio, language } = validateTranscription(body);
      return run(context, async (directory, signal) => {
        const file = join(directory, 'input.wav');
        await writeFile(file, audio, { flag: 'wx', mode: 0o600 });
        const stdout = await runProcess(nemo, ['--quiet', 'transcribe', file, '--model', asrModel,
          '--language', language, '--backend', 'metal', '--no-warmup', '--format', 'json'],
        { signal, cwd: directory, env: childEnv(directory) });
        let result;
        try { result = JSON.parse(stdout); } catch { throw failed(); }
        if (typeof result.text !== 'string' || result.text.length > 16000) throw failed();
        if (!result.text.trim()) throw new ApiError(400, 'empty_audio', '인식한 음성이 없습니다. 다시 녹음해 주세요.');
        return { text: result.text.trim(), language };
      });
    },
    async synthesize(body, context) {
      const input = validateSynthesis(body);
      return run(context, async (directory, signal) => {
        const file = join(directory, 'output.wav');
        await writeFile(file, '', { flag: 'wx', mode: 0o600 });
        await runProcess(python, [worker, ttsModel, file], { signal, cwd: directory, env: childEnv(directory), input: JSON.stringify(input) });
        if ((await stat(file)).size > TTS_MAX_BYTES) throw failed();
        const audio = await readFile(file);
        try { validateWav(audio, 24000, false); } catch { throw failed(); }
        return audio;
      });
    },
  };
}
