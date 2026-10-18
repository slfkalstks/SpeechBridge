CREATE TABLE account_deletion_sessions (
  token_hash text PRIMARY KEY CHECK(token_hash ~ '^[a-f0-9]{64}$'),
  user_id_hash text NOT NULL REFERENCES account_deletions(user_id_hash)
);
INSERT INTO account_deletion_sessions(token_hash,user_id_hash)
SELECT receipt_hash,user_id_hash FROM account_deletions;
GRANT SELECT, INSERT ON account_deletion_sessions TO speechbridge_auth;
