import { randomBytes, createHash, scrypt, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, chmodSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ApiError } from './provider.mjs';

const derive = promisify(scrypt);
const digest = token => createHash('sha256').update(token).digest('hex');
const fail = () => new ApiError(401, 'unauthorized', '로그인이 필요합니다.');
const lifetime = 7 * 24 * 60 * 60 * 1000;
export const capabilities = () => ['translate', 'organize', 'extract'];

export function createAccounts({ path = fileURLToPath(new URL('../.runtime/accounts.sqlite', import.meta.url)), now = Date.now } = {}) {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(path);
  if (path !== ':memory:') chmodSync(path, 0o600);
  db.exec(`PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, role TEXT NOT NULL CHECK(role='member'), salt TEXT NOT NULL, password_hash TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions (hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, expires INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
    CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires);`);
  let hashing = 0;
  const limits = new Map();
  function limit(key, maximum, duration = 60000) {
    const time = now();
    if (limits.size >= 10000) for (const [k, v] of limits) if (v.until <= time) limits.delete(k);
    if (!limits.has(key) && limits.size >= 10000) throw new ApiError(429, 'busy', '잠시 후 다시 시도해 주세요.');
    const previous = limits.get(key);
    const entry = previous?.until > time ? previous : { count: 0, until: time + duration };
    limits.set(key, entry);
    if (++entry.count > maximum) throw new ApiError(429, 'busy', '잠시 후 다시 시도해 주세요.');
  }
  const profile = user => ({ id: user.id, username: user.username, role: user.role, capabilities: capabilities(user.role) });
  function cleanup() {
    db.prepare('DELETE FROM sessions WHERE expires <= ?').run(now());
  }
  function issue(user) {
    cleanup();
    // Keep at most five device sessions per account.
    db.prepare('DELETE FROM sessions WHERE user_id=? AND hash NOT IN (SELECT hash FROM sessions WHERE user_id=? ORDER BY expires DESC LIMIT 4)').run(user.id, user.id);
    const token = randomBytes(32).toString('base64url');
    const expiresAt = now() + lifetime;
    db.prepare('INSERT INTO sessions VALUES(?,?,?)').run(digest(token), user.id, expiresAt);
    return { token, expiresAt, user: profile(user) };
  }
  function authenticate(header = '') {
    if (!/^Bearer [A-Za-z0-9_-]{43}$/.test(header)) throw fail();
    const hash = digest(header.slice(7));
    const user = db.prepare('SELECT users.*, sessions.expires FROM sessions JOIN users ON user_id=users.id WHERE hash=? AND expires>?').get(hash, now());
    if (!user) throw fail();
    return { ...profile(user), hash };
  }
  async function passwordHash(password, salt) {
    if (hashing >= 2) throw new ApiError(429, 'busy', '잠시 후 다시 시도해 주세요.');
    hashing++;
    try { return await derive(password, salt, 64, { N: 131072, r: 8, p: 1, maxmem: 160 * 1024 * 1024 }); }
    finally { hashing--; }
  }
  function credentials(body) {
    const username = typeof body.username === 'string' ? body.username.toLowerCase().trim() : '';
    if (!/^[a-z0-9_]{3,32}$/.test(username) || typeof body.password !== 'string' || body.password.length < 12 || body.password.length > 128) {
      throw new ApiError(400, 'invalid_credentials', '아이디는 영문·숫자·밑줄 3~32자, 비밀번호는 12~128자로 입력해 주세요.');
    }
    return { username, password: body.password };
  }
  return {
    authenticate,
    authorize(user, path) {
      if (path.startsWith("/speech/")) throw new ApiError(503, "speech_pending", "음성 기능은 아직 연결 전입니다.");
      if (!capabilities(user.role).includes(path.slice(1))) throw new ApiError(403, 'member_required', '회원 로그인 후 사용할 수 있습니다.');
      limit(`use:${user.id}`, 30);
    },
    admit(ip) { limit(`auth:${ip}`, 30, 15 * 60000); },
    async handle(path, body, header, ip) {
      if (path === '/auth/logout') {
        const user = authenticate(header);
        db.prepare('DELETE FROM sessions WHERE hash=?').run(user.hash);
        cleanup(); return { ok: true };
      }
      if (!['/auth/register', '/auth/login'].includes(path)) throw new ApiError(404, 'not_found', '경로를 찾을 수 없습니다.');
      const { username, password } = credentials(body);
      limit(`login:${username}`, 10, 15 * 60000);
      if (path === '/auth/register') {
        const salt = randomBytes(16).toString('hex');
        const hash = await passwordHash(password, salt);
        const user = { id: randomBytes(16).toString('hex'), username, role: 'member' };
        try {
          db.prepare('INSERT INTO users VALUES(?,?,?,?,?)').run(user.id, username, user.role, salt, hash.toString('hex'));
        } catch (error) {
          if (db.prepare('SELECT id FROM users WHERE username=?').get(username)) throw new ApiError(409, 'username_unavailable', '사용할 수 없는 아이디입니다.');
          throw error;
        }
        return issue(user);
      }
      const user = db.prepare("SELECT * FROM users WHERE username=? AND role='member'").get(username);
      // Same expensive derivation even for unknown usernames.
      const hash = await passwordHash(password, user?.salt ?? '00000000000000000000000000000000');
      const expected = user ? Buffer.from(user.password_hash, 'hex') : Buffer.alloc(64);
      if (!timingSafeEqual(hash, expected) || !user) throw new ApiError(401, 'login_failed', '아이디 또는 비밀번호를 확인해 주세요.');
      return issue(user);
    },
    close() { db.close(); },
  };
}
