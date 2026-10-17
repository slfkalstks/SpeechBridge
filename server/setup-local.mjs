import { mkdir, writeFile } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
const directory = new URL('../.runtime/', import.meta.url);
await mkdir(directory, { recursive: true });
try {
  await writeFile(new URL('server.env', directory), [
    'HOST=127.0.0.1', 'PORT=8787', `APP_ACCESS_TOKEN=${randomBytes(32).toString('hex')}`,
    'AI_PROVIDER=ollama-local', 'OLLAMA_BASE_URL=http://127.0.0.1:11435',
    'TRANSLATION_MODEL=translategemma:12b', 'EXTRACTION_MODEL=gemma4:12b-it-qat', 'SPEECH_PROVIDER=disabled', '',
  ].join('\n'), { flag: 'wx', mode: 0o600 });
  console.log('Created .runtime/server.env. Access token stays in that local file.');
} catch (error) {
  if (error.code !== 'EEXIST') throw error;
  console.log('Existing .runtime/server.env preserved.');
}
