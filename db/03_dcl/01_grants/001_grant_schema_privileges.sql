-- Permissions stay within the workflow schema (Anexo J.3).
GRANT USAGE ON SCHEMA workflow TO workflow_reader, workflow_writer;
GRANT SELECT ON ALL TABLES IN SCHEMA workflow TO workflow_reader;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA workflow TO workflow_writer;

ALTER DEFAULT PRIVILEGES IN SCHEMA workflow GRANT SELECT ON TABLES TO workflow_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA workflow GRANT SELECT, INSERT, UPDATE ON TABLES TO workflow_writer;
