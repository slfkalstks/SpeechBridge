import { mkdir, writeFile } from 'node:fs/promises';
const directory = new URL('../.runtime/', import.meta.url);
await mkdir(directory, { recursive: true, mode: 0o700 });
try {
  await writeFile(new URL('accounts-server.env', directory), [
    'HOST=127.0.0.1', 'PORT=8787', 'AUTH_MODE=users',
    'AI_PROVIDER=ollama-local', 'OLLAMA_BASE_URL=http://127.0.0.1:11435',
    'TRANSLATION_MODEL=translategemma:12b', 'EXTRACTION_MODEL=gemma4:12b-it-qat',
    'SPEECH_PROVIDER=disabled', '',
  ].join('\n'), { flag: 'wx', mode: 0o600 });
  console.log('Created .runtime/accounts-server.env. No shared access token is needed.');
} catch (error) {
  if (error.code !== 'EEXIST') throw error;
  console.log('Existing account server configuration preserved.');
}
