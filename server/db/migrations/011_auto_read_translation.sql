-- Additive preference; existing accounts opt into the user-requested automatic reading.
ALTER TABLE user_settings ADD COLUMN auto_read_translation boolean NOT NULL DEFAULT true;
