CREATE TABLE users (
  id text PRIMARY KEY CHECK(length(id) BETWEEN 1 AND 128),
  username text NOT NULL UNIQUE CHECK(username ~ '^[a-z0-9_]{3,32}$'),
  salt text NOT NULL CHECK(salt ~ '^[a-f0-9]{32}$'),
  password_hash text NOT NULL CHECK(password_hash ~ '^[a-f0-9]{128}$'),
  password_algorithm text NOT NULL DEFAULT 'scrypt' CHECK(password_algorithm='scrypt'),
  password_params jsonb NOT NULL DEFAULT '{"N":131072,"r":8,"p":1,"length":64}',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sessions (
  token_hash text PRIMARY KEY CHECK(token_hash ~ '^[a-f0-9]{64}$'),
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  issued_order bigint GENERATED ALWAYS AS IDENTITY,
  issued_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id, issued_order DESC);
CREATE INDEX sessions_expiry ON sessions(expires_at);

CREATE TABLE user_settings (
  user_id text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  appearance text NOT NULL DEFAULT 'SYSTEM' CHECK(appearance IN ('SYSTEM','LIGHT','DARK')),
  version bigint NOT NULL DEFAULT 1 CHECK(version>0), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE devices (
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE, id text NOT NULL,
  platform text NOT NULL CHECK(platform IN ('android','ios','test')),
  last_seen timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,id)
);
CREATE TABLE work_folders (
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE, id text NOT NULL,
  name text NOT NULL CHECK(length(trim(name)) BETWEEN 1 AND 80),
  version bigint NOT NULL DEFAULT 1 CHECK(version>0), deleted_at timestamptz,
  PRIMARY KEY(user_id,id)
);
CREATE UNIQUE INDEX folder_name ON work_folders(user_id,lower(trim(name))) WHERE deleted_at IS NULL;
CREATE TABLE conversations (
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE, id text NOT NULL,
  title text NOT NULL, summary text CHECK(length(summary)<=2000),
  created_at timestamptz NOT NULL DEFAULT now(), ended boolean NOT NULL DEFAULT false,
  first_language text NOT NULL CHECK(first_language IN ('KO','EN','JA')),
  second_language text NOT NULL CHECK(second_language IN ('KO','EN','JA')),
  translate boolean NOT NULL DEFAULT true, category text CHECK(category IN ('DAILY','WORK')),
  work_type text CHECK(work_type IN ('MEETING','TASK','IDEA','OTHER')), folder_id text,
  metadata_revision integer NOT NULL DEFAULT 0 CHECK(metadata_revision>=0),
  organized boolean NOT NULL DEFAULT false, organization_attempted boolean NOT NULL DEFAULT false,
  organization_sources jsonb NOT NULL DEFAULT '{}',
  version bigint NOT NULL DEFAULT 1 CHECK(version>0), deleted_at timestamptz,
  PRIMARY KEY(user_id,id), FOREIGN KEY(user_id,folder_id) REFERENCES work_folders(user_id,id),
  CHECK(first_language<>second_language),
  CHECK((work_type IS NULL AND folder_id IS NULL) OR category IS NOT DISTINCT FROM 'WORK')
);
CREATE INDEX conversation_history ON conversations(user_id,created_at DESC,id) WHERE deleted_at IS NULL;
CREATE INDEX conversation_category ON conversations(user_id,category,work_type,created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX conversation_folder ON conversations(user_id,folder_id);
CREATE TABLE utterances (
  user_id text NOT NULL, conversation_id text NOT NULL, id text NOT NULL,
  position integer NOT NULL CHECK(position>=0), language text NOT NULL CHECK(language IN ('KO','EN','JA')),
  original_text text NOT NULL, current_text text NOT NULL CHECK(length(trim(current_text))>0),
  current_revision integer NOT NULL CHECK(current_revision>0), legacy_speaker text NOT NULL DEFAULT '',
  PRIMARY KEY(user_id,conversation_id,id), UNIQUE(user_id,conversation_id,position),
  FOREIGN KEY(user_id,conversation_id) REFERENCES conversations(user_id,id) ON DELETE CASCADE
);
CREATE TABLE utterance_revisions (
  user_id text NOT NULL, conversation_id text NOT NULL, utterance_id text NOT NULL,
  revision integer NOT NULL CHECK(revision>0), text text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id,conversation_id,utterance_id,revision),
  FOREIGN KEY(user_id,conversation_id,utterance_id) REFERENCES utterances(user_id,conversation_id,id) ON DELETE CASCADE
);
CREATE TABLE translations (
  user_id text NOT NULL, conversation_id text NOT NULL, utterance_id text NOT NULL,
  revision integer NOT NULL, target_language text NOT NULL CHECK(target_language IN ('KO','EN','JA')), text text NOT NULL,
  PRIMARY KEY(user_id,conversation_id,utterance_id,revision,target_language),
  FOREIGN KEY(user_id,conversation_id,utterance_id,revision) REFERENCES utterance_revisions(user_id,conversation_id,utterance_id,revision) ON DELETE CASCADE
);
CREATE TABLE action_items (
  user_id text NOT NULL, conversation_id text NOT NULL, id text NOT NULL,
  source_id text NOT NULL, source_revision integer NOT NULL CHECK(source_revision>0),
  kind text NOT NULL CHECK(kind IN ('REQUEST','DECISION','NOTE')), text text NOT NULL,
  owner_text text, due_text text, reviewed boolean NOT NULL DEFAULT false,
  status text NOT NULL CHECK(status IN ('CANDIDATE','TODO','DONE')),
  origin text NOT NULL CHECK(origin IN ('MANUAL','SERVICE')), evidence_quote text,
  context_revisions jsonb NOT NULL DEFAULT '{}', omitted_by_latest_extraction boolean NOT NULL DEFAULT false,
  PRIMARY KEY(user_id,conversation_id,id),
  FOREIGN KEY(user_id,conversation_id,source_id) REFERENCES utterances(user_id,conversation_id,id) ON DELETE CASCADE
);
-- Legacy archives can reference a revision whose historical text was never stored.
-- Preserve that revision number, without inventing missing text in utterance_revisions.
CREATE INDEX action_status ON action_items(user_id,status);
CREATE INDEX action_source ON action_items(user_id,conversation_id,source_id);
CREATE TABLE conversation_drafts (
  user_id text NOT NULL, device_id text NOT NULL, scope_id text NOT NULL,
  payload jsonb NOT NULL, version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,device_id,scope_id),
  FOREIGN KEY(user_id,device_id) REFERENCES devices(user_id,id) ON DELETE CASCADE
);
CREATE TABLE ai_jobs (
  user_id text NOT NULL, id text NOT NULL, conversation_id text NOT NULL,
  kind text NOT NULL CHECK(kind IN ('translate','extract','organize')),
  source_revisions jsonb NOT NULL, metadata_revision integer NOT NULL,
  state text NOT NULL CHECK(state IN ('pending','running','succeeded','failed','cancelled')),
  error_code text, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id,id), FOREIGN KEY(user_id,conversation_id) REFERENCES conversations(user_id,id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX organize_once ON ai_jobs(user_id,conversation_id) WHERE kind='organize';
CREATE INDEX jobs_pending ON ai_jobs(state,created_at) WHERE state='pending';
CREATE TABLE sync_heads (
  user_id text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  sequence bigint NOT NULL DEFAULT 0 CHECK(sequence>=0)
);
CREATE TABLE sync_changes (
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE, sequence bigint NOT NULL,
  entity_type text NOT NULL, entity_id text NOT NULL, version bigint NOT NULL,
  deleted boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id,sequence)
);
CREATE TABLE sync_receipts (
  user_id text NOT NULL REFERENCES users(id) ON DELETE CASCADE, device_id text NOT NULL, mutation_id text NOT NULL,
  request_hash text NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id,device_id,mutation_id)
);
DO $$ DECLARE tbl text; BEGIN
  FOREACH tbl IN ARRAY ARRAY['user_settings','devices','work_folders','conversations','utterances','utterance_revisions',
    'translations','action_items','conversation_drafts','ai_jobs','sync_heads','sync_changes','sync_receipts'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tbl);
    EXECUTE format('CREATE POLICY account_rows ON %I TO speechbridge_app USING (user_id = nullif(current_setting(''speechbridge.user_id'', true), '''')) WITH CHECK (user_id = nullif(current_setting(''speechbridge.user_id'', true), ''''))', tbl);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO speechbridge_app', tbl);
  END LOOP;
END $$;
GRANT USAGE ON SCHEMA public TO speechbridge_auth, speechbridge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON users, sessions TO speechbridge_auth;
GRANT USAGE ON SEQUENCE sessions_issued_order_seq TO speechbridge_auth;
GRANT SELECT ON schema_migrations TO speechbridge_auth;
