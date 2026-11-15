-- HU-TXN-001 · E2a: the workflow schema and its permissions (ADR-013, Anexo J.3, J.7). Run by
-- db-ci after the first update; nothing is changed.
\set ON_ERROR_STOP on

CREATE FUNCTION pg_temp.expect(ok boolean, what text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF ok IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL: %', what;
    END IF;
    RAISE NOTICE 'ok: %', what;
END $$;

-- 1. The state of the transversal workflow lives in its own schema, never in public (ADR-013).
SELECT pg_temp.expect(EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'workflow'),
    'the workflow schema exists');

-- 2. Two NOLOGIN roles carry the permissions; workflow_app, created by infra, gets the writer.
SELECT pg_temp.expect(
    (SELECT count(*) = 2 AND bool_and(NOT rolcanlogin) FROM pg_roles
     WHERE rolname IN ('workflow_reader', 'workflow_writer')),
    'workflow_reader and workflow_writer exist and cannot log in');
SELECT pg_temp.expect(pg_has_role('workflow_app', 'workflow_writer', 'MEMBER'),
    'workflow_app is a member of workflow_writer');

-- 3. Every future table of the schema: the writer reads, inserts and updates, never deletes.
SELECT pg_temp.expect(has_schema_privilege('workflow_app', 'workflow', 'USAGE'),
    'workflow_app can use the workflow schema');
SELECT pg_temp.expect(
    (SELECT bool_or(a.privilege_type = 'INSERT') AND bool_or(a.privilege_type = 'UPDATE')
            AND NOT bool_or(a.privilege_type = 'DELETE')
     FROM pg_default_acl d
     JOIN pg_namespace n ON n.oid = d.defaclnamespace AND n.nspname = 'workflow'
     CROSS JOIN LATERAL aclexplode(d.defaclacl) a
     WHERE d.defaclobjtype = 'r' AND a.grantee = 'workflow_writer'::regrole),
    'new tables give workflow_writer INSERT and UPDATE, without DELETE');

\echo 'workflow-schema: all checks passed'
