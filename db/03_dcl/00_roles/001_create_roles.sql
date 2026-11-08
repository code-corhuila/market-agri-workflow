-- NOLOGIN roles carry permissions. The workflow_app login and password belong to infra.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_reader') THEN
        CREATE ROLE workflow_reader NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'workflow_writer') THEN
        CREATE ROLE workflow_writer NOLOGIN;
    END IF;
END
$$;
