\set ON_ERROR_STOP on

\if :{?expected_database}
\else
\echo 'ERROR: pass -v expected_database=<database-name> after verifying the target connection'
\quit false
\endif

SELECT set_config('opspilot.expected_database', :'expected_database', false);

DO $$
DECLARE
    v_count INTEGER;
    v_missing TEXT;
BEGIN
    IF current_database() <> current_setting('opspilot.expected_database') THEN
        RAISE EXCEPTION 'Wrong database: expected %, connected to %',
            current_setting('opspilot.expected_database'), current_database();
    END IF;
    IF current_setting('server_version_num')::INTEGER / 10000 <> 18 THEN
        RAISE EXCEPTION 'Expected PostgreSQL 18, found %', current_setting('server_version');
    END IF;
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        RAISE EXCEPTION 'Flyway history already exists; inspect it manually and do not baseline again';
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relispartition;
    IF v_count <> 77 THEN
        RAISE EXCEPTION 'Expected 77 main tables before baseline, found %', v_count;
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relispartition;
    IF v_count <> 18 THEN
        RAISE EXCEPTION 'Expected 18 partitions before baseline, found %', v_count;
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relkind = 'p';
    IF v_count <> 3 THEN
        RAISE EXCEPTION 'Expected 3 partitioned tables, found %', v_count;
    END IF;

    SELECT string_agg(required_name, ', ' ORDER BY required_name) INTO v_missing
      FROM unnest(ARRAY['ltree', 'btree_gist']) AS required_name
     WHERE NOT EXISTS (SELECT 1 FROM pg_extension e WHERE e.extname = required_name);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'Missing required extensions: %', v_missing;
    END IF;

    SELECT string_agg(required_name, ', ' ORDER BY required_name) INTO v_missing
      FROM unnest(ARRAY['opspilot_owner', 'opspilot_app', 'opspilot_job', 'opspilot_etl']) AS required_name
     WHERE NOT EXISTS (SELECT 1 FROM pg_roles r WHERE r.rolname = required_name);
    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION 'Missing required roles: %', v_missing;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_roles
         WHERE rolname = 'opspilot_app' AND (rolsuper OR rolbypassrls OR rolinherit)
    ) THEN
        RAISE EXCEPTION 'opspilot_app has unsafe role attributes';
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_proc p
      JOIN pg_roles r ON r.oid = p.proowner
     WHERE p.pronamespace = 'public'::regnamespace AND r.rolname = 'opspilot_owner';
    IF v_count <> 21 THEN
        RAISE EXCEPTION 'Expected 21 application functions, found %', v_count;
    END IF;

    IF (SELECT count(*) FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid
         JOIN pg_class tbl ON tbl.oid = i.indrelid
         WHERE idx.relnamespace = 'public'::regnamespace
           AND tbl.relname <> 'flyway_schema_history') <> 349 THEN
        RAISE EXCEPTION 'Expected 349 indexes in public schema';
    END IF;
    IF (SELECT count(*) FROM pg_class
         WHERE relnamespace = 'public'::regnamespace AND relkind = 'S') <> 77 THEN
        RAISE EXCEPTION 'Expected 77 sequences in public schema';
    END IF;
    IF (SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
         WHERE c.relnamespace = 'public'::regnamespace AND a.attidentity <> '') <> 95 THEN
        RAISE EXCEPTION 'Expected 95 identity columns';
    END IF;
    IF (SELECT count(*) FROM pg_constraint
         WHERE connamespace = 'public'::regnamespace AND contype = 'c') <> 290
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'f') <> 278
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'n') <> 883
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'p') <> 95
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'u') <> 91
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'x') <> 4 THEN
        RAISE EXCEPTION 'Constraint counts differ from approved schema';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles r ON r.oid = c.relowner
         WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'S', 'v')
           AND r.rolname <> 'opspilot_owner'
    ) THEN
        RAISE EXCEPTION 'Business schema contains objects not owned by opspilot_owner';
    END IF;

    IF EXISTS (SELECT 1 FROM rls_coverage) THEN
        RAISE EXCEPTION 'Tenant table without effective RLS policy detected';
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_policy p
      JOIN pg_class c ON c.oid = p.polrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public';
    IF v_count <> 85 THEN
        RAISE EXCEPTION 'Expected 85 RLS policies, found %', v_count;
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_trigger t
      JOIN pg_class c ON c.oid = t.tgrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND NOT t.tgisinternal;
    IF v_count <> 23 THEN
        RAISE EXCEPTION 'Expected 23 application triggers, found %', v_count;
    END IF;

    IF has_table_privilege('opspilot_app', 'public.platform_user', 'SELECT')
       OR has_table_privilege('opspilot_app', 'public.audit_log', 'DELETE')
       OR has_table_privilege('opspilot_etl', 'public.app_user', 'SELECT')
       OR NOT has_table_privilege('opspilot_app', 'public.sales_order', 'SELECT') THEN
        RAISE EXCEPTION 'Approved role grants do not match the OpsPilot security baseline';
    END IF;
END;
$$;

SELECT current_database() AS database_name,
       current_user AS verified_by,
       current_setting('server_version') AS postgres_version,
       77 AS verified_main_tables,
       18 AS verified_partitions,
       21 AS verified_application_functions,
       85 AS verified_policies,
       23 AS verified_triggers;
