import pg from 'pg';

export function poolConfig(env, role) {
  if (!env.PGHOST || !env.PGDATABASE || !env[`PG_${role}_PASSWORD`]) throw new Error('PostgreSQL configuration required');
  return { host: env.PGHOST, port: Number(env.PGPORT || 5433), database: env.PGDATABASE,
    user: `speechbridge_${role.toLowerCase()}`, password: env[`PG_${role}_PASSWORD`],
    max: 4, connectionTimeoutMillis: 5000, idleTimeoutMillis: 10000,
    statement_timeout: 10000, idle_in_transaction_session_timeout: 15000,
    application_name: 'speechbridge' };
}

export async function transaction(pool, operation) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await operation(client);
    await client.query('COMMIT');
    return result;
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally { client.release(); }
}

export function createDatabase(env) {
  const auth = new pg.Pool(poolConfig(env, 'AUTH'));
  const data = new pg.Pool(poolConfig(env, 'APP'));
  // A disconnected idle client must not terminate the API or print credentials.
  auth.on('error', () => {}); data.on('error', () => {});
  return {
    auth, data,
    forUser(userId, operation) {
      if (typeof userId !== 'string' || !userId || userId.length > 128) throw new Error('Invalid account identity');
      return transaction(data, async client => {
        // Transaction-local identity is reset on COMMIT/ROLLBACK before pool reuse.
        await client.query("SELECT set_config('speechbridge.user_id', $1, true)", [userId]);
        return operation(client);
      });
    },
    async ready() {
      // A restore is a historical snapshot. Deletions can arrive after it was
      // copied; never serve it merely because someone edits PGDATABASE.
      if(/^speechbridge_restore_/.test(env.PGDATABASE))throw new Error('Restored database activation requires cutover reconciliation');
      await auth.query('SELECT 1 FROM schema_migrations LIMIT 1');
    },
    async close() { await Promise.all([auth.end(), data.end()]); },
  };
}
