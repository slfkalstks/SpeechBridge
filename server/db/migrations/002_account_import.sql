CREATE TABLE legacy_account_imports (
  user_id text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  imported_at timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT ON legacy_account_imports TO speechbridge_auth;
