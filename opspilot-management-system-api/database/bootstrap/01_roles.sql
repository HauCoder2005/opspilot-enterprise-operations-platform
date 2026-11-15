BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper
    ) THEN
        RAISE EXCEPTION 'OpsPilot all-in-one bootstrap must run as PostgreSQL superuser; current_user=%', current_user;
    END IF;
END;
$$;

-- ========================= 1 / 3: ROLE SETUP ================================
-- =============================================================================
-- OpsPilot — db/roles_setup.sql (initial bootstrap, PostgreSQL 18)
-- Run ONCE on a DEDICATED OpsPilot database using a PostgreSQL superuser.
-- Safe to rerun for role attributes and schema grants, but does not erase data.
-- NO PASSWORDS ARE STORED HERE. Configure login secrets outside source control.
--
-- Example (after creating database opspilot, connected to that database):
--   psql -U postgres -d opspilot -v ON_ERROR_STOP=1 -f db/roles_setup.sql
--   psql -U opspilot_owner -d opspilot -v ON_ERROR_STOP=1 -f db/OpsPilot_schema_V1-V14_hardened.sql
--
-- IMPORTANT: This script does NOT create the database itself. Connect to the
-- correct database before running; do not run it in a shared application DB.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opspilot_owner') THEN
        CREATE ROLE opspilot_owner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opspilot_app') THEN
        CREATE ROLE opspilot_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opspilot_job') THEN
        CREATE ROLE opspilot_job LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opspilot_etl') THEN
        CREATE ROLE opspilot_etl LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS;
    END IF;
END;
$$;

-- Normalize flags even if roles existed. Do NOT overwrite existing passwords.
ALTER ROLE opspilot_owner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE opspilot_app   LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE opspilot_job   LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS;
ALTER ROLE opspilot_etl   LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS;

-- Reject dangerous role graphs; NOINHERIT alone is not enough because a member
-- with SET ROLE privilege may switch to another role.
DO $$
BEGIN
    IF pg_has_role('opspilot_app', 'opspilot_owner', 'MEMBER')
       OR pg_has_role('opspilot_app', 'opspilot_job', 'MEMBER')
       OR pg_has_role('opspilot_app', 'opspilot_etl', 'MEMBER') THEN
        RAISE EXCEPTION 'Unsafe role membership: opspilot_app must not be member of owner/job/etl';
    END IF;
    IF pg_has_role('opspilot_job', 'opspilot_owner', 'MEMBER')
       OR pg_has_role('opspilot_etl', 'opspilot_owner', 'MEMBER')
       OR pg_has_role('opspilot_etl', 'opspilot_job', 'MEMBER') THEN
        RAISE EXCEPTION 'Unsafe role membership between privileged OpsPilot roles';
    END IF;
END;
$$;

-- Public schema is not writable by API/job/ETL. Required for SECURITY DEFINER
-- functions using SET search_path=public,pg_temp.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM opspilot_app, opspilot_job, opspilot_etl;
GRANT USAGE ON SCHEMA public TO opspilot_app, opspilot_job, opspilot_etl;
GRANT USAGE, CREATE ON SCHEMA public TO opspilot_owner;

-- Allow owner to install trusted extensions (ltree, btree_gist) in this DB.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO opspilot_owner, opspilot_app, opspilot_job, opspilot_etl',
                   current_database());
    EXECUTE format('GRANT CREATE ON DATABASE %I TO opspilot_owner', current_database());
END;
$$;

-- Prevent app/job/ETL from installing new extensions or creating other schemas.
DO $$
BEGIN
    EXECUTE format('REVOKE CREATE ON DATABASE %I FROM PUBLIC, opspilot_app, opspilot_job, opspilot_etl',
                   current_database());
END;
$$;

-- Verify attributes before running the schema as opspilot_owner.
SELECT rolname, rolcanlogin, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb
  FROM pg_roles
 WHERE rolname IN ('opspilot_owner','opspilot_app','opspilot_job','opspilot_etl')
 ORDER BY rolname;

-- CREDENTIAL SETUP (outside this file and outside Git):
-- Set strong, different passwords for each LOGIN role using a secret manager.
-- Ensure Spring Boot uses opspilot_app, not opspilot_owner or opspilot_job.
-- Do not reuse opspilot_job or opspilot_etl credentials for REST requests.
-- Keep owner/job/ETL connections separate from the API datasource/pool.

COMMIT;
