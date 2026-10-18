CREATE TABLE restore_activation (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 source_database text NOT NULL,
 activated boolean NOT NULL DEFAULT false,
 activated_at timestamptz
);
REVOKE ALL ON restore_activation FROM PUBLIC;
GRANT SELECT ON restore_activation TO speechbridge_auth;
