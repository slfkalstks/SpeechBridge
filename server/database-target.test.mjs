import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { activeDatabase, databaseFromConfig, migrationTargets } from './db/target.mjs';

const socket=fileURLToPath(new URL('../.runtime/postgres/socket',import.meta.url));
const config={PGHOST:socket,PGPORT:'5433',PGDATABASE:'speechbridge'};
test('backup and migration target the selected project database after recovery',async()=>{
  assert.equal(databaseFromConfig(config),'speechbridge');
  assert.deepEqual(migrationTargets(config),['speechbridge','speechbridge_test']);
  const restored='speechbridge_restore_0123456789abcdef01234567';
  assert.equal(databaseFromConfig({...config,PGDATABASE:restored}),restored);
  assert.deepEqual(migrationTargets({...config,PGDATABASE:restored}),[restored,'speechbridge_test']);
  assert.match(await activeDatabase(),/^speechbridge(?:_restore_[a-f0-9]{24})?$/);
});
test('operational configuration cannot target another project or silently fall back',()=>{
  for(const bad of [
    {...config,PGDATABASE:'other_project'}, {...config,PGDATABASE:'speechbridge_test'},
    {...config,PGDATABASE:'speechbridge; DROP DATABASE postgres'}, {...config,PGDATABASE:''},
    {...config,PGHOST:'localhost'}, {...config,PGHOST:'/tmp/other-project/socket'},
    {...config,PGPORT:'5432'}, {},
  ]) assert.throws(()=>databaseFromConfig(bad),/project database configuration/);
});
