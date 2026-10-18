import { DatabaseSync, backup } from 'node:sqlite';
import { mkdir, chmod } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { createDatabase, transaction } from './database.mjs';

export async function importSqliteAccounts({ source, backupDirectory, db }) {
  // Open read-only: never create an empty source or modify the old account store.
  const sqlite = new DatabaseSync(source, { readOnly: true });
  let backupPath;
  try {
    await mkdir(backupDirectory, { recursive: true, mode: 0o700 });
    backupPath = join(backupDirectory, `accounts-before-postgres-${randomUUID()}.sqlite`);
    await backup(sqlite, backupPath);
    await chmod(backupPath, 0o600);
  } finally { sqlite.close(); }
  // Read exactly the consistent backup, even if the original was concurrently open.
  const snapshot = new DatabaseSync(backupPath, { readOnly: true });
  let users, sessions;
  try {
    users = snapshot.prepare('SELECT id,username,role,salt,password_hash FROM users ORDER BY id').all();
    sessions = snapshot.prepare('SELECT hash,user_id,expires FROM sessions ORDER BY expires,hash').all();
  } finally { snapshot.close(); }
  if (users.some(u => u.role !== 'member')) throw Error('Unsupported legacy account role');
  await transaction(db.auth, async client => {
    await client.query('SELECT pg_advisory_xact_lock(83917403)');
    for (const user of users) {
      const { rows } = await client.query('SELECT id,username,salt,password_hash FROM users WHERE id=$1 OR username=$2 FOR UPDATE', [user.id, user.username]);
      if (rows.length && (rows.length !== 1 || ['id', 'username', 'salt', 'password_hash'].some(k => rows[0][k] !== user[k]))) throw Error('Account migration collision; originals preserved');
      if (!rows.length) await client.query('INSERT INTO users(id,username,salt,password_hash) VALUES($1,$2,$3,$4)', [user.id, user.username, user.salt, user.password_hash]);
      const claimed = await client.query('INSERT INTO legacy_account_imports(user_id) VALUES($1) ON CONFLICT DO NOTHING RETURNING user_id', [user.id]);
      if (!claimed.rowCount) continue; // Never resurrect sessions revoked after a prior import.
      for (const session of sessions.filter(s => s.user_id === user.id).slice(-5)) {
        if (!Number.isSafeInteger(session.expires)) throw Error('Invalid legacy expiry');
        if (session.expires <= Date.now()) continue;
        await client.query('INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,$3)', [session.hash, session.user_id, new Date(session.expires)]);
      }
    }
  });
  return { users: users.length, sessions: sessions.length, backup: backupPath };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const db = createDatabase(process.env);
  try {
    const result = await importSqliteAccounts({ db,
      source: fileURLToPath(new URL('../../.runtime/accounts.sqlite', import.meta.url)),
      backupDirectory: fileURLToPath(new URL('../../.runtime/backups/', import.meta.url)) });
    console.log(`Account import verified: ${result.users} users, ${result.sessions} source sessions; original preserved.`);
  } catch { console.error('Account import stopped; originals preserved. Inspect configuration and account collisions.'); process.exitCode = 1; }
  finally { await db.close(); }
}
