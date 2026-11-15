\set ON_ERROR_STOP on

-- Stable, data-independent fingerprints. flyway_schema_history is deliberately
-- excluded so the result must remain identical immediately before/after baseline.
WITH definitions(category, definition) AS (
    SELECT 'columns', format('%s.%s|%s|%s|%s|%s|%s|%s',
               n.nspname, c.relname, a.attnum, a.attname,
               format_type(a.atttypid, a.atttypmod), a.attnotnull,
               a.attidentity, coalesce(pg_get_expr(d.adbin, d.adrelid), ''))
      FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
       AND c.relname <> 'flyway_schema_history'
       AND a.attnum > 0 AND NOT a.attisdropped
    UNION ALL
    SELECT 'constraints', format('%s|%s|%s', c.conname, c.contype, pg_get_constraintdef(c.oid, true))
      FROM pg_constraint c
      JOIN pg_namespace n ON n.oid = c.connamespace
     WHERE n.nspname = 'public'
       AND c.conrelid <> coalesce(to_regclass('public.flyway_schema_history'), 0)
    UNION ALL
    SELECT 'indexes', pg_get_indexdef(i.indexrelid)
      FROM pg_index i
      JOIN pg_class idx ON idx.oid = i.indexrelid
      JOIN pg_class tbl ON tbl.oid = i.indrelid
     WHERE idx.relnamespace = 'public'::regnamespace
       AND tbl.relname <> 'flyway_schema_history'
    UNION ALL
    SELECT 'functions', pg_get_functiondef(p.oid)
      FROM pg_proc p
      JOIN pg_roles r ON r.oid = p.proowner
     WHERE p.pronamespace = 'public'::regnamespace AND r.rolname = 'opspilot_owner'
    UNION ALL
    SELECT 'triggers', pg_get_triggerdef(t.oid, true)
      FROM pg_trigger t
      JOIN pg_class c ON c.oid = t.tgrelid
     WHERE c.relnamespace = 'public'::regnamespace AND NOT t.tgisinternal
    UNION ALL
    SELECT 'policies', format('%s|%s|%s|%s|%s|%s', c.relname, p.polname,
               p.polcmd, p.polpermissive, coalesce(pg_get_expr(p.polqual, p.polrelid), ''),
               coalesce(pg_get_expr(p.polwithcheck, p.polrelid), ''))
      FROM pg_policy p
      JOIN pg_class c ON c.oid = p.polrelid
     WHERE c.relnamespace = 'public'::regnamespace
    UNION ALL
    SELECT 'partitions', format('%s|%s', c.relname, pg_get_expr(c.relpartbound, c.oid))
      FROM pg_class c
     WHERE c.relnamespace = 'public'::regnamespace
       AND c.relkind = 'r' AND c.relispartition
    UNION ALL
    SELECT 'table_grants', format('%s|%s|%s|%s', table_name, grantee, privilege_type, is_grantable)
      FROM information_schema.role_table_grants
     WHERE table_schema = 'public'
       AND grantee IN ('opspilot_owner', 'opspilot_app', 'opspilot_job', 'opspilot_etl')
       AND table_name <> 'flyway_schema_history'
    UNION ALL
    SELECT 'column_grants', format('%s|%s|%s|%s', table_name, column_name, grantee, privilege_type)
      FROM information_schema.role_column_grants
     WHERE table_schema = 'public'
       AND grantee IN ('opspilot_owner', 'opspilot_app', 'opspilot_job', 'opspilot_etl')
       AND table_name <> 'flyway_schema_history'
)
SELECT category,
       count(*) AS definition_count,
       md5(string_agg(definition, E'\n' ORDER BY definition)) AS fingerprint
  FROM definitions
 GROUP BY category
 ORDER BY category;
