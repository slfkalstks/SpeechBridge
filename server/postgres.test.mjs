import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { createDatabase } from './db/database.mjs';
import { createPostgresAccounts } from './db/accounts.mjs';
import { createServer } from './server.mjs';

// This suite requires the isolated project test database, never a production URL.
process.loadEnvFile(new URL('../.runtime/postgres/test.env', import.meta.url));
assert.equal(process.env.PGDATABASE, 'speechbridge_test');
const password = 'Synthetic-test-password-123!';
async function fixture(t) {
  let time = Date.now();
  const db = createDatabase(process.env);
  const accounts = createPostgresAccounts({ db, now: () => time });
  const username = `t_${randomUUID().replaceAll('-', '').slice(0, 20)}`;
  t.after(async () => {
    await db.auth.query('DELETE FROM users WHERE username LIKE $1', [username + '%']);
    await db.close();
  });
  const register = (suffix = '') => accounts.handle('/auth/register', { username: username + suffix, password });
  return { db, accounts, username, register, advance: ms => { time += ms; } };
}

test('PostgreSQL preserves member login, hashed credentials, expiry and revocation across connections', async t => {
  const { db, accounts, username, register, advance } = await fixture(t);
  const session = await register();
  assert.equal((await accounts.authenticate(`Bearer ${session.token}`)).id, session.user.id);
  const second = createPostgresAccounts({ db, now: Date.now });
  assert.equal((await second.authenticate(`Bearer ${session.token}`)).username, username);
  const stored = (await db.auth.query('SELECT u.*, s.token_hash FROM users u JOIN sessions s ON s.user_id=u.id WHERE u.id=$1', [session.user.id])).rows[0];
  assert.equal(JSON.stringify(stored).includes(password), false);
  assert.equal(JSON.stringify(stored).includes(session.token), false);
  await accounts.handle('/auth/logout', {}, `Bearer ${session.token}`);
  await assert.rejects(second.authenticate(`Bearer ${session.token}`), { status: 401 });
  const login = await accounts.handle('/auth/login', { username: username.toUpperCase(), password });
  advance(7 * 24 * 3600_000 + 1);
  await assert.rejects(accounts.authenticate(`Bearer ${login.token}`), { status: 401 });
});

test('PostgreSQL rejects bad credentials, duplicate registration and guest roles', async t => {
  const { accounts, username, register } = await fixture(t);
  await assert.rejects(accounts.handle('/auth/register', { username, password: 'short' }), { status: 400 });
  await register();
  await assert.rejects(register(), { status: 409 });
  await assert.rejects(accounts.handle('/auth/login', { username, password: password + 'wrong' }), { status: 401 });
  await assert.rejects(accounts.handle('/auth/login', { username: username + 'x', password }), { status: 401 });
  await assert.rejects(accounts.handle('/auth/guest', {}), { status: 404 });
});

test('concurrent PostgreSQL sessions enforce the five session cap transactionally', async t => {
  const { db, accounts, username, register } = await fixture(t);
  const first = await register();
  // Independent service instances model simultaneous requests across processes.
  await Promise.all(Array.from({ length: 6 }, () => createPostgresAccounts({ db }).handle('/auth/login', { username, password })));
  const { rows } = await db.auth.query('SELECT count(*)::int AS count FROM sessions WHERE user_id=$1', [first.user.id]);
  assert.equal(rows[0].count, 5);
  await assert.rejects(accounts.authenticate(`Bearer ${first.token}`), { status: 401 });
});

test('data role is non-owner, RLS isolates records and transaction identity never leaks through the pool', async t => {
  const { db, register } = await fixture(t);
  const a = (await register()).user.id;
  const b = (await register('b')).user.id;
  await db.forUser(a, client => client.query("INSERT INTO work_folders(user_id,id,name) VALUES($1,'folder','My folder')", [a]));
  assert.equal((await db.forUser(a, c => c.query('SELECT * FROM work_folders'))).rowCount, 1);
  assert.equal((await db.forUser(b, c => c.query('SELECT * FROM work_folders'))).rowCount, 0);
  assert.equal((await db.data.query('SELECT * FROM work_folders')).rowCount, 0);
  await assert.rejects(db.forUser(b, c => c.query("INSERT INTO work_folders(user_id,id,name) VALUES($1,'foreign','Wrong')", [a])), { code: '42501' });
  await assert.rejects(db.forUser(b, c => c.query("INSERT INTO conversations(user_id,id,title,first_language,second_language,folder_id,category) VALUES($1,'cross','Wrong','KO','EN','folder','WORK')", [b])), { code: '23503' });
  await assert.rejects(db.data.query('SELECT * FROM users'), { code: '42501' });
  const role = (await db.data.query('SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname=current_user')).rows[0];
  assert.deepEqual(role, { rolsuper: false, rolbypassrls: false });
  await assert.rejects(db.forUser(a, async c => { await c.query("UPDATE work_folders SET name='Lost'"); throw Error('rollback'); }), /rollback/);
  assert.equal((await db.forUser(a, c => c.query('SELECT name FROM work_folders'))).rows[0].name, 'My folder');
});

test('migration SQL is versioned with a checksum ledger', async t => {
  const { db } = await fixture(t);
  const { rows } = await db.auth.query('SELECT version, checksum FROM schema_migrations ORDER BY version');
  assert.ok(rows.some(row => row.version === '001'));
  assert.match(rows[0].checksum, /^[a-f0-9]{64}$/);
  const source = await readFile(new URL('./db/migrations/001_initial.sql', import.meta.url), 'utf8');
  assert.ok(source.includes('FORCE ROW LEVEL SECURITY'));
});

test('HTTP accepts asynchronous PostgreSQL authentication without a shared-token fallback', async t => {
  const { accounts, register } = await fixture(t);
  const session = await register();
  const server = createServer({ env: { AUTH_MODE: 'users' }, accounts,
    providers: { configured: {}, translate: async body => ({ text: body.text, revision: body.revision }) } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const me = await fetch(base + '/auth/me', { headers: { Authorization: `Bearer ${session.token}` } });
  assert.deepEqual((await me.json()).user, session.user);
  const translation = await fetch(base + '/translate', { method: 'POST', headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ text: 'hello', revision: 1 }) });
  assert.equal(translation.status, 200);
  assert.equal((await translation.json()).text, 'hello');
  assert.throws(() => createServer({ env: { AUTH_MODE: 'users' } }), /PostgreSQL configuration required/);
});
