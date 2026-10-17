import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createAccounts } from './auth.mjs';
import { createServer } from './server.mjs';

const password = 'Synthetic-test-password-123!';
const credentials = { username: 'student_1', password };
async function fixture(t, options = {}) {
  let clock = 1_000_000;
  const accounts = createAccounts({ path: ':memory:', now: () => clock });
  const calls = [];
  const providers = { configured: { auth: false },
    translate: async body => { calls.push('translate'); return { text: body.text, revision: body.revision }; },
    organize: async () => { calls.push('organize'); return { title: 'Synthetic', summary: 'Test only' }; },
    extract: async () => { calls.push('extract'); return { items: [] }; },
  };
  const server = createServer({ env: { AUTH_MODE: 'users', APP_ACCESS_TOKEN: 'old-shared-token-should-not-work' }, providers,
    accounts, now: () => clock, ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); accounts.close(); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = (path, body, token, extraHeaders = {}) => fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { ...extraHeaders, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { request, accounts, calls, advance: ms => { clock += ms; } };
}
test('member register/login/restore/logout; all text capabilities; no guest or shared-token bypass', async t => {
  const { request, calls } = await fixture(t);
  assert.equal((await request('/auth/guest', {})).status, 404);
  assert.equal((await request('/translate', {}, 'old-shared-token-should-not-work')).status, 401);
  const registered = await request('/auth/register', credentials);
  assert.equal(registered.status, 200);
  const session = await registered.json();
  assert.equal(session.user.username, credentials.username);
  assert.deepEqual(session.user.capabilities, ['translate', 'organize', 'extract']);
  assert.equal((await request('/auth/me', undefined, session.token)).status, 200);
  for (const path of ['/translate', '/organize', '/extract']) assert.equal((await request(path, { text: 'test', revision: 1 }, session.token)).status, 200);
  assert.equal(calls.length, 3);
  assert.equal((await request('/auth/logout', {}, session.token)).status, 200);
  assert.equal((await request('/auth/me', undefined, session.token)).status, 401);
  const logged = await request('/auth/login', { ...credentials, username: 'STUDENT_1' });
  assert.equal(logged.status, 200);
  assert.notEqual((await logged.json()).token, session.token);
  const wrong = await request('/auth/login', { ...credentials, password: password + 'wrong' });
  const unknown = await request('/auth/login', { username: 'no_such_user', password });
  assert.equal(wrong.status, 401); assert.deepEqual(await wrong.json(), await unknown.json());
});
test('reject invalid credentials, duplicate usernames, oversized body and role injection', async t => {
  const { request } = await fixture(t);
  for (const input of [{ ...credentials, password: 'short' }, { ...credentials, username: "';DROP TABLE users;" }, { ...credentials, password: 'x'.repeat(129) }]) {
    assert.equal((await request('/auth/register', input)).status, 400);
  }
  assert.equal((await request('/auth/register', { ...credentials, role: 'admin' })).status, 200);
  assert.equal((await request('/auth/register', credentials)).status, 409);
  assert.equal((await request('/auth/login', { ...credentials, padding: 'x'.repeat(2048) })).status, 400);
});
test('sessions expire; per-member quota rejects before inference and recovers', async t => {
  const { request, calls, advance } = await fixture(t);
  const { token } = await (await request('/auth/register', credentials)).json();
  for (let i = 0; i < 30; i++) assert.equal((await request('/translate', { text: 'test' }, token)).status, 200);
  assert.equal((await request('/translate', {}, token)).status, 429); assert.equal(calls.length, 30);
  advance(60001);
  assert.equal((await request('/translate', {}, token)).status, 200);
  advance(7 * 24 * 3600000);
  assert.equal((await request('/translate', {}, token)).status, 401);
});
test('persistent account database stores neither plaintext password nor bearer; revocation survives reopen', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'speechbridge-auth-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const path = join(directory, 'accounts.sqlite');
  let accounts = createAccounts({ path });
  const session = await accounts.handle('/auth/register', credentials, '', 'test');
  accounts.close();
  const file = await readFile(path);
  assert.equal(file.includes(Buffer.from(password)), false);
  assert.equal(file.includes(Buffer.from(session.token)), false);
  accounts = createAccounts({ path });
  assert.equal(accounts.authenticate(`Bearer ${session.token}`).username, credentials.username);
  await accounts.handle('/auth/logout', {}, `Bearer ${session.token}`, 'test');
  accounts.close();
  accounts = createAccounts({ path });
  assert.throws(() => accounts.authenticate(`Bearer ${session.token}`), { status: 401 });
  accounts.close();
});
test('login rate limits ignore forged proxy headers; health is cheap and does not disclose runtime', async t => {
  const { request } = await fixture(t);
  const health = await request('/health');
  assert.equal(health.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await health.json(), { status: 'ok', configured: { auth: true }, authentication: 'users' });
  for (let i = 0; i < 30; i++) assert.equal((await request('/auth/login', { username: 'x', password: 'short' }, null, { 'X-Forwarded-For': `192.0.2.${i}` })).status, 400);
  assert.equal((await request('/auth/login', credentials, null, { 'X-Forwarded-For': '203.0.113.50' })).status, 429);
});
test('maximum five active device sessions; sixth login retires oldest session', async () => {
  let time = 100;
  const accounts = createAccounts({ path: ':memory:', now: () => time++ });
  try {
    const first = await accounts.handle('/auth/register', credentials, '', 'test');
    let latest;
    for (let i = 0; i < 5; i++) latest = await accounts.handle('/auth/login', credentials, '', 'test');
    assert.throws(() => accounts.authenticate(`Bearer ${first.token}`), { status: 401 });
    assert.equal(accounts.authenticate(`Bearer ${latest.token}`).username, credentials.username);
  } finally { accounts.close(); }
});
