// Project-scoped PostgreSQL management: no global install, service, PATH or DB edits.
import { mkdir, readFile, writeFile, access, unlink, chmod } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import pg from 'pg';
import { migrate } from './migrate.mjs';

const root = fileURLToPath(new URL('../../.runtime/postgres/', import.meta.url));
const bin = join(root, 'install/bin');
const data = join(root, 'data');
const socket = join(root, 'socket');
const exists = async path => access(path).then(() => true, () => false);
const run = (name, args) => execFileSync(join(bin, name), args, { stdio: 'pipe', env: { ...process.env, TMPDIR: join(root, 'tmp') } });
const quote = value => "'" + value.replaceAll("'", "''") + "'";
const cmd = process.argv[2] || 'status';
if (!['init', 'start', 'stop', 'status', 'migrate', 'backup'].includes(cmd)) throw Error('Unknown project DB action');
try {
  await mkdir(root, { recursive: true, mode: 0o700 });
  await chmod(root, 0o700);
  for (const path of [socket, join(root, 'tmp')]) await mkdir(path, { recursive: true, mode: 0o700 });
  if (cmd === 'init') {
    const secretsPath = join(root, 'credentials.json');
    if (!await exists(secretsPath)) {
      if (await exists(join(data, 'PG_VERSION'))) throw Error('Existing DB has no credentials file; refusing to replace it');
      await writeFile(secretsPath, JSON.stringify(Object.fromEntries(['admin', 'auth', 'app'].map(role => [role, randomBytes(32).toString('hex')]))), { flag: 'wx', mode: 0o600 });
    }
    const secrets = JSON.parse(await readFile(secretsPath, 'utf8'));
    if (!await exists(join(data, 'PG_VERSION'))) {
      const pwfile = join(root, 'init-password');
      await writeFile(pwfile, secrets.admin, { mode: 0o600 });
      try { run('initdb', ['-D', data, '-U', 'speechbridge_admin', '--encoding=UTF8', '--locale-provider=builtin', '--builtin-locale=C.UTF-8', '--auth-local=scram-sha-256', '--auth-host=reject', `--pwfile=${pwfile}`]); }
      finally { await unlink(pwfile); }
      await writeFile(join(data, 'speechbridge.conf'), [
        "listen_addresses = ''", 'port = 5433', `unix_socket_directories = ${quote(socket)}`,
        'unix_socket_permissions = 0700', 'shared_buffers = 64MB', 'max_connections = 24',
        'log_statement = none', 'log_min_error_statement = panic', 'log_error_verbosity = terse',
        "timezone = 'UTC'", "password_encryption = 'scram-sha-256'", '',
      ].join('\n'), { mode: 0o600 });
      await writeFile(join(data, 'postgresql.conf'), "include = 'speechbridge.conf'\n", { mode: 0o600 });
    }
  }
  if (cmd === 'stop') { run('pg_ctl', ['-D', data, '-m', 'fast', '-w', 'stop']); console.log('Project PostgreSQL stopped.'); }
  else if (cmd === 'status') {
    try { run('pg_ctl', ['-D', data, 'status']); console.log('Project PostgreSQL is running.'); }
    catch { console.log('Project PostgreSQL is stopped.'); process.exitCode = 1; }
  } else {
    try { run('pg_ctl', ['-D', data, 'status']); }
    catch { run('pg_ctl', ['-D', data, '-l', join(root, 'postgres.log'), '-w', 'start']); }
    if (cmd === 'start') { console.log('Project PostgreSQL started (private Unix socket only).'); }
    else {
      const secrets = JSON.parse(await readFile(join(root, 'credentials.json'), 'utf8'));
      const config = { host: socket, port: 5433, user: 'speechbridge_admin', password: secrets.admin, database: 'postgres' };
      const admin = new pg.Client(config);
      await admin.connect();
      try {
        if (cmd === 'init') {
          for (const role of ['owner', 'auth', 'app']) {
            const name = `speechbridge_${role}`;
            if (!(await admin.query('SELECT 1 FROM pg_roles WHERE rolname=$1', [name])).rowCount) {
              await admin.query(`CREATE ROLE ${name} ${role === 'owner' ? 'NOLOGIN' : `LOGIN PASSWORD ${quote(secrets[role])}`} NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE`);
            }
          }
          for (const database of ['speechbridge', 'speechbridge_test']) {
            if (!(await admin.query('SELECT 1 FROM pg_database WHERE datname=$1', [database])).rowCount) await admin.query(`CREATE DATABASE ${database} OWNER speechbridge_owner`);
            await admin.query(`REVOKE ALL ON DATABASE ${database} FROM PUBLIC`);
            await admin.query(`GRANT CONNECT ON DATABASE ${database} TO speechbridge_auth, speechbridge_app`);
            const content = [`PGHOST=${socket}`, 'PGPORT=5433', `PGDATABASE=${database}`,
              `PG_AUTH_PASSWORD=${secrets.auth}`, `PG_APP_PASSWORD=${secrets.app}`, ''].join('\n');
            await writeFile(join(root, database.endsWith('_test') ? 'test.env' : 'server.env'), content, { mode: 0o600 });
          }
        }
        if (cmd === 'init' || cmd === 'migrate') {
          for (const database of ['speechbridge', 'speechbridge_test']) {
            const pool = new pg.Pool({ ...config, database });
            try {
              await pool.query('REVOKE ALL ON SCHEMA public FROM PUBLIC');
              await migrate(pool);
            } finally { await pool.end(); }
          }
          console.log('Project production and test schemas ready; credentials kept private.');
        }
        if (cmd === 'backup') {
          const backups = fileURLToPath(new URL('../../.runtime/backups/', import.meta.url));
          await mkdir(backups, { recursive: true, mode: 0o700 });
          const name = `speechbridge-${new Date().toISOString().replaceAll(':', '-')}.dump`;
          const path = join(backups, name);
          execFileSync(join(bin, 'pg_dump'), ['-h', socket, '-p', '5433', '-U', 'speechbridge_admin', '-d', 'speechbridge', '-Fc', '-f', path], { stdio: 'pipe', env: { ...process.env, PGPASSWORD: secrets.admin } });
          await chmod(path, 0o600);
          console.log(`Project backup created: .runtime/backups/${name}`);
        }
      } finally { await admin.end(); }
    }
  }
} catch (error) {
  // Command errors can include connection details; never echo their raw stdout/stderr.
  if (error.stderr) await writeFile(join(root, 'command-error.log'), error.stderr, { mode: 0o600 });
  console.error(`Project PostgreSQL ${cmd} failed. Inspect .runtime/postgres logs locally.`);
  process.exitCode = 1;
}
