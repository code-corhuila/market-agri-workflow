-- Anexo J.10: an _app account may write only inside its own domain schema.
SELECT r.rolname AS usuario, n.nspname AS esquema,
       bool_or(has_table_privilege(r.rolname, c.oid, 'SELECT')) AS lee,
       bool_or(has_table_privilege(r.rolname, c.oid, 'INSERT')
            OR has_table_privilege(r.rolname, c.oid, 'UPDATE')
            OR has_table_privilege(r.rolname, c.oid, 'DELETE')) AS escribe
FROM pg_roles r
JOIN pg_class c ON c.relkind = 'r'
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE r.rolname LIKE '%\_app'
  AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
  AND has_schema_privilege(r.rolname, n.nspname, 'USAGE')
GROUP BY 1, 2
HAVING bool_or(has_table_privilege(r.rolname, c.oid, 'SELECT'))
ORDER BY 1, 2;
