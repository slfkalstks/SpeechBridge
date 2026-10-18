import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { parseEnv } from 'node:util';

const socket=fileURLToPath(new URL('../../.runtime/postgres/socket',import.meta.url));
export function databaseFromConfig(config) {
  if(config.PGHOST!==socket || config.PGPORT!=='5433' || !/^speechbridge(?:_restore_[a-f0-9]{24})?$/.test(config.PGDATABASE ?? '')) {
    throw new Error('Invalid project database configuration; no fallback applied');
  }
  return config.PGDATABASE;
}
export function migrationTargets(config) {
  return [databaseFromConfig(config),'speechbridge_test'];
}
const readConfig = async () => parseEnv(await readFile(new URL('../../.runtime/postgres/server.env',import.meta.url),'utf8'));
export async function activeDatabase() {
  return databaseFromConfig(await readConfig());
}
export async function activeMigrationTargets() {
  return migrationTargets(await readConfig());
}
