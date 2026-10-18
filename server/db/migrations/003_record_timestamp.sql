-- Preserve the original client timestamp string (including sub-microsecond precision).
ALTER TABLE conversations ADD COLUMN created_at_text text NOT NULL DEFAULT '';
ALTER TABLE conversation_drafts ADD COLUMN deleted_at timestamptz;
