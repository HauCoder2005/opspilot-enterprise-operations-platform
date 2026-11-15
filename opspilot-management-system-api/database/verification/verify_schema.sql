\set ON_ERROR_STOP on

\if :{?expected_database}
\else
\echo 'ERROR: pass -v expected_database=<database-name> after verifying the target connection'
\quit false
\endif
\if :{?expected_history_type}
\else
\set expected_history_type SQL
\endif

SELECT set_config('opspilot.expected_database', :'expected_database', false),
       set_config('opspilot.expected_history_type', :'expected_history_type', false);

DO $$
DECLARE
    v_count INTEGER;
    v_missing TEXT;
BEGIN
    IF current_database() <> current_setting('opspilot.expected_database') THEN
        RAISE EXCEPTION 'Wrong database: expected %, connected to %',
            current_setting('opspilot.expected_database'), current_database();
    END IF;
    SELECT count(*) INTO v_count
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p')
       AND NOT c.relispartition
       AND c.relname <> 'flyway_schema_history';
    IF v_count <> 77 THEN
        RAISE EXCEPTION 'Expected 77 main tables, found %', v_count;
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relispartition;
    IF v_count <> 18 THEN
        RAISE EXCEPTION 'Expected 18 partitions, found %', v_count;
    END IF;

    SELECT count(*) INTO v_count FROM pg_class
     WHERE relnamespace = 'public'::regnamespace AND relkind = 'p';
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

    IF EXISTS (SELECT 1 FROM rls_coverage) THEN
        RAISE EXCEPTION 'Tenant table without effective RLS policy detected';
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
     WHERE c.relnamespace = 'public'::regnamespace;
    IF v_count <> 85 THEN
        RAISE EXCEPTION 'Expected 85 RLS policies, found %', v_count;
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner
     WHERE p.pronamespace = 'public'::regnamespace AND r.rolname = 'opspilot_owner';
    IF v_count <> 21 THEN
        RAISE EXCEPTION 'Expected 21 application functions, found %', v_count;
    END IF;

    IF (SELECT count(*) FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid
         JOIN pg_class tbl ON tbl.oid = i.indrelid
         WHERE idx.relnamespace = 'public'::regnamespace
           AND tbl.relname <> 'flyway_schema_history') <> 349
       OR (SELECT count(*) FROM pg_class
            WHERE relnamespace = 'public'::regnamespace AND relkind = 'S') <> 77
       OR (SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
            WHERE c.relnamespace = 'public'::regnamespace AND a.attidentity <> '') <> 95 THEN
        RAISE EXCEPTION 'Index, sequence, or identity-column count differs from approved schema';
    END IF;

    IF (SELECT count(*) FROM pg_constraint
         WHERE connamespace = 'public'::regnamespace AND contype = 'c'
           AND conrelid <> coalesce(to_regclass('public.flyway_schema_history'), 0)) <> 290
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'f'
              AND conrelid <> coalesce(to_regclass('public.flyway_schema_history'), 0)) <> 278
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'n'
              AND conrelid <> coalesce(to_regclass('public.flyway_schema_history'), 0)) <> 883
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'p'
              AND conrelid <> coalesce(to_regclass('public.flyway_schema_history'), 0)) <> 95
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'u'
              AND conrelid <> coalesce(to_regclass('public.flyway_schema_history'), 0)) <> 91
       OR (SELECT count(*) FROM pg_constraint
            WHERE connamespace = 'public'::regnamespace AND contype = 'x'
              AND conrelid <> coalesce(to_regclass('public.flyway_schema_history'), 0)) <> 4 THEN
        RAISE EXCEPTION 'Constraint counts differ from approved schema';
    END IF;

    SELECT count(*) INTO v_count
      FROM pg_trigger t
      JOIN pg_class c ON c.oid = t.tgrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public' AND NOT t.tgisinternal;
    IF v_count <> 23 THEN
        RAISE EXCEPTION 'Expected 23 application triggers, found %', v_count;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles r ON r.oid = c.relowner
         WHERE n.nspname = 'public'
           AND c.relkind IN ('r', 'p', 'S', 'v')
           AND c.relname <> 'flyway_schema_history'
           AND r.rolname <> 'opspilot_owner'
    ) THEN
        RAISE EXCEPTION 'Business schema contains objects not owned by opspilot_owner';
    END IF;

    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'Flyway schema history is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history
         WHERE version = '1'
           AND type = current_setting('opspilot.expected_history_type')
           AND success
    ) THEN
        RAISE EXCEPTION 'Successful Flyway V1 history entry of type % is missing',
            current_setting('opspilot.expected_history_type');
    END IF;
END;
$$;

SELECT
    (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
        AND NOT c.relispartition AND c.relname <> 'flyway_schema_history') AS main_tables,
    (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relispartition) AS partitions,
    (SELECT count(*) FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public') AS policies,
    (SELECT count(*) FROM rls_coverage) AS missing_rls;

SELECT installed_rank, version, description, type, success
  FROM flyway_schema_history
 ORDER BY installed_rank;
