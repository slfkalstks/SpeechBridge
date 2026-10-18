-- The same first-organization job survives API restarts. Lease tokens fence late workers.
ALTER TABLE ai_jobs ADD COLUMN input jsonb;
ALTER TABLE ai_jobs ADD COLUMN guard jsonb;
ALTER TABLE ai_jobs ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK(attempts >= 0);
ALTER TABLE ai_jobs ADD COLUMN available_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE ai_jobs ADD COLUMN lease_token text;
ALTER TABLE ai_jobs ADD COLUMN lease_until timestamptz;
ALTER TABLE ai_jobs ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX jobs_recoverable ON ai_jobs(user_id,available_at,created_at) WHERE state IN ('pending','running');
