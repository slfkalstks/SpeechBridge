import { randomBytes, createHash, scrypt, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { ApiError } from '../errors.mjs';
import { transaction } from './database.mjs';

const derive = promisify(scrypt);
const digest = token => createHash('sha256').update(token).digest('hex');
const lifetime = 7 * 24 * 60 * 60 * 1000;
const fail = () => new ApiError(401, 'unauthorized', '로그인이 필요합니다.');
export const capabilities = () => ['translate', 'organize', 'extract', 'speech/transcribe', 'speech/synthesize'];

export function createPostgresAccounts({ db, now = Date.now }) {
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
  const profile = user => ({ id: user.id, username: user.username, role: 'member', capabilities: capabilities() });
  async function issue(user) {
    return transaction(db.auth, async client => {
      // Serialize per account before pruning/inserting, even across API processes.
      if(!(await client.query('SELECT id FROM users WHERE id=$1 FOR UPDATE', [user.id])).rowCount)throw fail();
      await client.query('DELETE FROM sessions WHERE user_id=$1 AND expires_at <= $2', [user.id, new Date(now())]);
      await client.query('DELETE FROM sessions WHERE user_id=$1 AND token_hash NOT IN (SELECT token_hash FROM sessions WHERE user_id=$1 ORDER BY issued_order DESC LIMIT 4)', [user.id]);
      const token = randomBytes(32).toString('base64url');
      const expiresAt = now() + lifetime;
      await client.query('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,$3)', [digest(token), user.id, new Date(expiresAt)]);
      return { token, expiresAt, user: profile(user) };
    });
  }
  async function authenticate(header = '') {
    if (!/^Bearer [A-Za-z0-9_-]{43}$/.test(header)) throw fail();
    const hash = digest(header.slice(7));
    const { rows: [user] } = await db.auth.query('SELECT u.id,u.username FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=$1 AND s.expires_at>$2', [hash, new Date(now())]);
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
      if (['/data/snapshot', '/data/mutate', '/data/changes'].includes(path)) { limit(`data:${user.id}`, 120); return; }
      if (path === '/speech/token') throw new ApiError(503, 'speech_pending', '음성 기능은 아직 연결 전입니다.');
      if (!capabilities().includes(path.slice(1))) throw new ApiError(403, 'member_required', '회원 로그인 후 사용할 수 있습니다.');
      limit(`use:${user.id}`, 30);
    },
    admit(ip) { limit(`auth:${ip}`, 30, 15 * 60000); },
    async handle(path, body, header) {
      if(path === '/auth/delete') {
        const {username,password}=credentials(body);
        if(body.confirm!==true)throw new ApiError(400,'confirmation_required','계정과 기록 삭제를 확인해 주세요.');
        if(!/^Bearer [A-Za-z0-9_-]{43}$/.test(header || ''))throw fail();
        const receipt=digest(header.slice(7));
        // Retrying a lost acknowledgement cannot delete a newly created account.
        if((await db.auth.query('SELECT 1 FROM account_deletions WHERE receipt_hash=$1 AND username_hash=$2',[receipt,digest(username)])).rowCount)return {deleted:true};
        const authenticated=await authenticate(header);
        if(authenticated.username!==username)throw new ApiError(400,'account_mismatch','로그인한 계정의 아이디를 확인해 주세요.');
        limit(`delete:${authenticated.id}`,5,15*60000);
        return transaction(db.auth,async client=>{
          const {rows:[user]}=await client.query('SELECT * FROM users WHERE id=$1 FOR UPDATE',[authenticated.id]);
          if(!user)throw fail();
          const hash=await passwordHash(password,user.salt);
          if(!timingSafeEqual(hash,Buffer.from(user.password_hash,'hex')))throw new ApiError(403,'password_incorrect','현재 비밀번호를 확인해 주세요.');
          // A concurrent logout/expiry must win before destructive work commits.
          if(!(await client.query('SELECT 1 FROM sessions WHERE token_hash=$1 AND user_id=$2 AND expires_at>$3 FOR UPDATE',[receipt,user.id,new Date(now())])).rowCount)throw fail();
          await client.query('INSERT INTO account_deletions(user_id_hash,username_hash,receipt_hash) VALUES($1,$2,$3)',[digest(user.id),digest(username),receipt]);
          await client.query('DELETE FROM users WHERE id=$1',[user.id]);
          return {deleted:true};
        });
      }
      if (path === '/auth/logout') {
        const user = await authenticate(header);
        await db.auth.query('DELETE FROM sessions WHERE token_hash=$1', [user.hash]);
        return { ok: true };
      }
      if (!['/auth/register', '/auth/login'].includes(path)) throw new ApiError(404, 'not_found', '경로를 찾을 수 없습니다.');
      const { username, password } = credentials(body);
      limit(`login:${username}`, 10, 15 * 60000);
      if (path === '/auth/register') {
        const salt = randomBytes(16).toString('hex');
        const hash = await passwordHash(password, salt);
        const user = { id: randomBytes(16).toString('hex'), username };
        try {
          await db.auth.query('INSERT INTO users(id,username,salt,password_hash) VALUES($1,$2,$3,$4)', [user.id, username, salt, hash.toString('hex')]);
        } catch (error) {
          if (error.code === '23505') throw new ApiError(409, 'username_unavailable', '사용할 수 없는 아이디입니다.');
          throw error;
        }
        return issue(user);
      }
      const { rows: [user] } = await db.auth.query('SELECT * FROM users WHERE username=$1', [username]);
      const hash = await passwordHash(password, user?.salt ?? '00000000000000000000000000000000');
      const expected = user ? Buffer.from(user.password_hash, 'hex') : Buffer.alloc(64);
      if (!timingSafeEqual(hash, expected) || !user) throw new ApiError(401, 'login_failed', '아이디 또는 비밀번호를 확인해 주세요.');
      return issue(user);
    },
    close() {}, // The server owns the shared pools.
  };
}
