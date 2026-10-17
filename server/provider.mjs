import { validateOrganization } from './organization.mjs';
import { ApiError } from './errors.mjs';
import { createLocalProvider } from './local-provider.mjs';
import { createVoiceProvider, validateSynthesis, validateTranscription } from './voice-provider.mjs';
export { ApiError } from './errors.mjs';
const upstreamFailure = () => new ApiError(502, 'upstream_failed', '외부 서비스 응답을 확인하지 못했습니다.');
const badInput = () => new ApiError(400, 'invalid_input', '입력 형식을 확인해 주세요.');
const languages = new Set(['ko', 'en', 'ja']);
const nonempty = (v, max = 16000) => typeof v === 'string' && v.trim().length > 0 && v.length <= max;
const revision = v => Number.isSafeInteger(v) && v > 0;

export function validateTranslation(body) {
  if (!body || !nonempty(body.text) || !languages.has(body.from) || !languages.has(body.to) || !revision(body.revision)) throw badInput();
  return { text: body.text, from: body.from, to: body.to, revision: body.revision };
}
export function validateExtraction(body) {
  if (!body || !languages.has(body.language) || !Array.isArray(body.utterances) || !body.utterances.length || body.utterances.length > 200) throw badInput();
  if (!body.utterances.every(u => u && nonempty(u.id, 100) && revision(u.revision) && nonempty(u.text))) throw badInput();
  if (new Set(body.utterances.map(u => u.id)).size !== body.utterances.length || body.utterances.reduce((n, u) => n + u.text.length, 0) > 60000) throw badInput();
  return { language: body.language, utterances: body.utterances.map(({ id, revision, text }) => ({ id, revision, text })) };
}

export function validateExtractedItems(value, input, { requireEvidence = false } = {}) {
  if (!value || !Array.isArray(value.items) || value.items.length > 100) throw upstreamFailure();
  const items = value.items.map(item => {
    const source = input.utterances.find(u => u.id === item?.sourceUtteranceId && u.revision === item?.sourceRevision);
    if (!source || !['request', 'decision', 'note'].includes(item.kind) || !nonempty(item.text, 2000) ||
        ![item.owner, item.dueText].every(v => v === null || nonempty(v, 200))) throw upstreamFailure();
    if ((requireEvidence || item.evidenceQuote !== undefined) &&
        (!nonempty(item.evidenceQuote, 16000) || !source.text.includes(item.evidenceQuote))) throw upstreamFailure();
    // Reject invented names/dates at the boundary. Keep relative dates literal; user reviews semantics.
    const grounded = v => v !== null && source.text.includes(v) ? v : null;
    return { sourceUtteranceId: source.id, sourceRevision: source.revision, kind: item.kind,
      text: item.text, owner: grounded(item.owner), dueText: grounded(item.dueText),
      ...(item.evidenceQuote ? { evidenceQuote: item.evidenceQuote } : {}) };
  });
  return { items };
}

// Explicit local-only provider; no paid/cloud provider can be enabled by keys.
export function createProviders(env, options = {}) {
  if (env.AI_PROVIDER === 'ollama-local') {
    const provider = createLocalProvider(env, { validateTranslation, validateExtraction, validateExtractedItems, ...options });
    if (env.SPEECH_PROVIDER === 'local-process') {
      const voice = createVoiceProvider(options.voice);
      const health = provider.health;
      provider.configured.speech = true;
      provider.health = async context => {
        const [result, ready] = await Promise.all([health(context), voice.ready()]);
        result.available.speech = ready;
        return { ...result, speechRuntime: ready ? 'files_ready' : 'not_ready' };
      };
      provider.transcribe = voice.transcribe;
      provider.synthesize = voice.synthesize;
    } else {
      provider.transcribe = async body => { validateTranscription(body); return provider.speechToken(); };
      provider.synthesize = async body => { validateSynthesis(body); return provider.speechToken(); };
    }
    return provider;
  }
  const disabled = () => new ApiError(503, 'free_provider_pending', '유료 API는 사용하지 않습니다. 무료 AI 서버 연결을 준비 중입니다.');
  return {
    configured: {
      auth: typeof env.APP_ACCESS_TOKEN === 'string' && env.APP_ACCESS_TOKEN.length >= 32,
      translation: false, extraction: false, speech: false,
    },
    async translate(raw) {
      const input = validateTranslation(raw);
      if (input.from === input.to) return { text: input.text, revision: input.revision };
      throw disabled();
    },
    async organize(raw) { validateOrganization(raw, validateExtraction); throw disabled(); },
    async extract(raw) { validateExtraction(raw); throw disabled(); },
    async speechToken() { throw disabled(); },
    async transcribe(body) { validateTranscription(body); throw disabled(); },
    async synthesize(body) { validateSynthesis(body); throw disabled(); },
  };
}
