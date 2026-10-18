ALTER TABLE action_items ADD COLUMN position integer NOT NULL DEFAULT 0 CHECK(position>=0);
