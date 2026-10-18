-- Minimal irreversible deletion receipts. No username, password, text or raw token.
-- Keep separately from users so a cascade cannot remove restoration protection.
CREATE TABLE account_deletions (
  user_id_hash text PRIMARY KEY CHECK(user_id_hash ~ '^[a-f0-9]{64}$'),
  username_hash text NOT NULL CHECK(username_hash ~ '^[a-f0-9]{64}$'),
  receipt_hash text NOT NULL UNIQUE CHECK(receipt_hash ~ '^[a-f0-9]{64}$'),
  deleted_at timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT, INSERT ON account_deletions TO speechbridge_auth;
