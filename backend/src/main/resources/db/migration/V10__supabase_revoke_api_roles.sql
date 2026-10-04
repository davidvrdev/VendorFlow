-- Defense in depth for Supabase (ADR-0010). Supabase exposes the `public` schema through its auto-generated REST/GraphQL
-- "Data API" to the roles anon / authenticated / service_role. VendorFlow never uses that API (all access goes through the
-- Spring backend with tenant checks), so those roles must have NO privileges on our tables, even if someone forgets to
-- switch the Data API off. On plain PostgreSQL (local dev, CI, tests) the roles do not exist and this is a no-op.
DO $$
DECLARE
    api_role text;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM %I', api_role);
            -- Tables created by later migrations (same migration role) must not get the Supabase default grants either.
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM %I', api_role);
        END IF;
    END LOOP;
END
$$;
