import { readdir, readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { transaction } from './database.mjs';

export async function migrate(pool) {
  await transaction(pool, async client => {
    await client.query("SELECT pg_advisory_xact_lock(83917402)");
    await client.query('SET LOCAL ROLE speechbridge_owner');
    await client.query('CREATE TABLE IF NOT EXISTS schema_migrations(version text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())');
    const directory = new URL('./migrations/', import.meta.url);
    for (const name of (await readdir(directory)).filter(n => /^\d{3}_.*\.sql$/.test(n)).sort()) {
      const version = name.slice(0, 3);
      const sql = await readFile(new URL(name, directory), 'utf8');
      const checksum = createHash('sha256').update(sql).digest('hex');
      const { rows: [existing] } = await client.query('SELECT checksum FROM schema_migrations WHERE version=$1', [version]);
      if (existing) {
        if (existing.checksum !== checksum) throw new Error(`Migration ${version} checksum mismatch`);
        continue;
      }
      await client.query(sql);
      await client.query('INSERT INTO schema_migrations(version,checksum) VALUES($1,$2)', [version, checksum]);
    }
  });
}
