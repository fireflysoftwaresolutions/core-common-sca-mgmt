\set ON_ERROR_STOP on
-- Read-only catalog inspection. It neither installs nor repairs privileges.
BEGIN READ ONLY;
SELECT current_database(), current_user, :'recovery_owner' AS reviewed_owner,
       :'recovery_runtime' AS reviewed_runtime;
SELECT tablename, tableowner FROM pg_tables
WHERE schemaname = 'public' AND tablename IN
    ('sca_recovery_operation', 'sca_recovery_challenge', 'sca_recovery_audit')
ORDER BY tablename;
SELECT object_name, privilege,
       has_table_privilege(:'recovery_runtime', 'public.' || object_name, privilege) AS allowed
FROM (VALUES ('sca_recovery_operation'), ('sca_recovery_challenge'), ('sca_recovery_audit')) objects(object_name)
CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) privileges(privilege)
ORDER BY object_name, privilege;
SELECT c.relname, t.tgname, t.tgenabled, pg_get_triggerdef(t.oid)
FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relname IN
    ('sca_recovery_operation', 'sca_recovery_challenge', 'sca_recovery_audit')
    AND NOT t.tgisinternal
ORDER BY c.relname, t.tgname;
SELECT tablename, indexname, indexdef FROM pg_indexes
WHERE schemaname = 'public' AND tablename IN
    ('sca_recovery_operation', 'sca_recovery_challenge', 'sca_recovery_audit')
ORDER BY tablename, indexname;
COMMIT;
