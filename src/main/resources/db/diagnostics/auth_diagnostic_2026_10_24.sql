-- ============================================================================
-- TeknoyCart auth diagnostic (READ-ONLY) — 2026-10-24
-- ============================================================================
-- Purpose
--   Capture the live Supabase state before W1. This script only reads
--   catalogs and row counts. It does not create, alter, drop, insert,
--   update, or delete anything. It does not select emails, names, passwords,
--   tokens, phone numbers, or storage paths.
--
-- How to run
--   1. Supabase Dashboard -> SQL Editor -> New query.
--   2. Paste this entire file and click Run.
--   3. Copy the whole result grid (or Download CSV) and paste it back.
--      If a long JSON cell is truncated in the grid, copy that cell only.
--
-- How to read `state`
--   RED   = a known critical risk is present, or a required control is missing.
--   WARN  = review; may be acceptable for the demo but should be recorded.
--   GREEN = the expected safe state is present.
--   INFO  = inventory/counts; no judgment.
--
-- Expected baseline (before W1): many RED rows. That is intentional. The
-- known baseline is: RLS off on users/chats/messages/inquiries; table SELECT
-- granted to anon+authenticated on users; both security triggers are
-- SECURITY DEFINER with a current_user/session_user postgres guard; the
-- password_hash column is not guarded; products insert/update only checks
-- auth.uid() = seller_id; no public_profiles/my_profile view.
-- ============================================================================

WITH
critical_tables AS (
    SELECT
        c.oid,
        c.relname AS table_name,
        c.relrowsecurity,
        c.relforcerowsecurity,
        pg_get_userbyid(c.relowner) AS owner,
        COALESCE(s.n_live_tup, 0) AS approx_rows
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
    WHERE n.nspname = 'public'
      AND c.relkind IN ('r', 'p')
      AND c.relname IN (
          'users', 'chats', 'messages', 'inquiries', 'products',
          'store_profiles', 'orders', 'seller_verifications',
          'payment_proofs', 'order_returns', 'product_reviews'
      )
),
user_policies AS (
    SELECT policyname, cmd, roles, qual, with_check
    FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'users'
),
chat_policies AS (
    SELECT tablename, policyname, cmd, roles, qual, with_check
    FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename IN ('chats', 'messages', 'inquiries')
),
kyc_policies AS (
    SELECT policyname, cmd, roles, qual, with_check
    FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'seller_verifications'
),
checks AS (

    -- A. Environment ---------------------------------------------------------
    SELECT
        'A01'::text AS check_id,
        'Environment'::text AS area,
        'INFO'::text AS state,
        jsonb_build_object(
            'database', current_database(),
            'current_user', current_user,
            'session_user', session_user,
            'server_version', current_setting('server_version'),
            'now_utc', now(),
            'jwt_role', current_setting('request.jwt.claim.role', true)
        ) AS detail

    UNION ALL

    -- B. RLS and publication -------------------------------------------------
    SELECT
        'B01',
        'RLS',
        CASE
            WHEN COALESCE((SELECT bool_and(relrowsecurity) FROM critical_tables), false)
             AND NOT EXISTS (
                 SELECT 1
                 FROM unnest(ARRAY[
                     'users', 'chats', 'messages', 'inquiries', 'products',
                     'store_profiles', 'orders', 'seller_verifications',
                     'payment_proofs', 'order_returns', 'product_reviews'
                 ]) AS t(name)
                 WHERE to_regclass('public.' || t.name) IS NULL
             )
            THEN 'GREEN' ELSE 'RED'
        END,
        jsonb_build_object(
            'tables', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'table', table_name,
                        'rls', relrowsecurity,
                        'force_rls', relforcerowsecurity,
                        'owner', owner,
                        'approx_rows', approx_rows
                    ) ORDER BY table_name
                )
                FROM critical_tables
            ), '[]'::jsonb),
            'missing_tables', COALESCE((
                SELECT jsonb_agg(t.name)
                FROM unnest(ARRAY[
                    'users', 'chats', 'messages', 'inquiries', 'products',
                    'store_profiles', 'orders', 'seller_verifications',
                    'payment_proofs', 'order_returns', 'product_reviews'
                ]) AS t(name)
                WHERE to_regclass('public.' || t.name) IS NULL
            ), '[]'::jsonb)
        )

    UNION ALL

    SELECT
        'B02',
        'Realtime',
        CASE
            WHEN NOT EXISTS (
                SELECT 1 FROM pg_publication_tables
                WHERE pubname = 'supabase_realtime'
                  AND schemaname = 'public'
                  AND tablename = 'chats'
            )
            OR NOT EXISTS (
                SELECT 1 FROM pg_publication_tables
                WHERE pubname = 'supabase_realtime'
                  AND schemaname = 'public'
                  AND tablename = 'messages'
            )
            THEN 'WARN'
            WHEN EXISTS (
                SELECT 1 FROM pg_publication_tables
                WHERE pubname = 'supabase_realtime'
                  AND schemaname = 'public'
                  AND tablename = 'users'
            )
            THEN 'WARN'
            ELSE 'INFO'
        END,
        jsonb_build_object(
            'publication_exists', EXISTS (
                SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime'
            ),
            'tables', COALESCE((
                SELECT jsonb_agg(tablename ORDER BY tablename)
                FROM pg_publication_tables
                WHERE pubname = 'supabase_realtime' AND schemaname = 'public'
            ), '[]'::jsonb),
            'chats_in_publication', EXISTS (
                SELECT 1 FROM pg_publication_tables
                WHERE pubname = 'supabase_realtime'
                  AND schemaname = 'public'
                  AND tablename = 'chats'
            ),
            'messages_in_publication', EXISTS (
                SELECT 1 FROM pg_publication_tables
                WHERE pubname = 'supabase_realtime'
                  AND schemaname = 'public'
                  AND tablename = 'messages'
            ),
            'inquiries_in_publication', EXISTS (
                SELECT 1 FROM pg_publication_tables
                WHERE pubname = 'supabase_realtime'
                  AND schemaname = 'public'
                  AND tablename = 'inquiries'
            ),
            'users_in_publication', EXISTS (
                SELECT 1 FROM pg_publication_tables
                WHERE pubname = 'supabase_realtime'
                  AND schemaname = 'public'
                  AND tablename = 'users'
            )
        )

    UNION ALL

    SELECT
        'B03',
        'Ledger',
        CASE
            WHEN EXISTS (
                SELECT 1 FROM public.schema_migrations
                WHERE version = 'V2__comprehensive_rls_lockdown'
            )
            THEN 'INFO' ELSE 'WARN'
        END,
        jsonb_build_object(
            'rows', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object('version', version, 'applied_at', applied_at)
                    ORDER BY applied_at, version
                )
                FROM public.schema_migrations
            ), '[]'::jsonb),
            'v2_applied', EXISTS (
                SELECT 1 FROM public.schema_migrations
                WHERE version = 'V2__comprehensive_rls_lockdown'
            ),
            'v8_applied', EXISTS (
                SELECT 1 FROM public.schema_migrations
                WHERE version = 'V8__admin_backdoor_hardening'
            )
        )

    UNION ALL

    -- C. Policies ------------------------------------------------------------
    SELECT
        'C01',
        'Policies',
        'INFO',
        jsonb_build_object(
            'policies', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'name', policyname,
                        'cmd', cmd,
                        'roles', array_to_string(roles, ','),
                        'using', qual,
                        'check', with_check
                    ) ORDER BY policyname
                )
                FROM user_policies
            ), '[]'::jsonb),
            'select_authenticated_true', EXISTS (
                SELECT 1 FROM user_policies
                WHERE policyname = 'users_select_authenticated' AND qual = 'true'
            ),
            'anon_policy_exists', EXISTS (
                SELECT 1 FROM user_policies WHERE 'anon'::name = ANY(roles)
            ),
            'authenticated_policy_exists', EXISTS (
                SELECT 1 FROM user_policies WHERE 'authenticated'::name = ANY(roles)
            )
        )

    UNION ALL

    SELECT
        'C02',
        'Policies',
        CASE
            WHEN (SELECT count(DISTINCT tablename) FROM chat_policies) < 3
            THEN 'WARN' ELSE 'INFO'
        END,
        jsonb_build_object(
            'tables_with_policies', COALESCE((
                SELECT jsonb_agg(DISTINCT tablename) FROM chat_policies
            ), '[]'::jsonb),
            'policies', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'table', tablename,
                        'name', policyname,
                        'cmd', cmd,
                        'roles', array_to_string(roles, ','),
                        'using', qual,
                        'check', with_check
                    ) ORDER BY tablename, policyname
                )
                FROM chat_policies
            ), '[]'::jsonb)
        )

    UNION ALL

    SELECT
        'C03',
        'Policies',
        CASE
            WHEN NOT COALESCE((
                SELECT with_check LIKE '%is_seller_verified%'
                   AND with_check LIKE '%SELLER%'
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'products'
                  AND policyname = 'products_insert_seller'
            ), false)
            THEN 'RED' ELSE 'GREEN'
        END,
        jsonb_build_object(
            'products_insert_seller', (
                SELECT jsonb_build_object(
                    'roles', array_to_string(roles, ','),
                    'check', with_check
                )
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'products'
                  AND policyname = 'products_insert_seller'
            ),
            'products_update_seller', (
                SELECT jsonb_build_object(
                    'roles', array_to_string(roles, ','),
                    'using', qual,
                    'check', with_check
                )
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'products'
                  AND policyname = 'products_update_seller'
            ),
            'store_profiles_insert_owner', (
                SELECT jsonb_build_object(
                    'roles', array_to_string(roles, ','),
                    'check', with_check
                )
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'store_profiles'
                  AND policyname = 'store_profiles_insert_owner'
            ),
            'store_profiles_update_owner', (
                SELECT jsonb_build_object(
                    'roles', array_to_string(roles, ','),
                    'using', qual,
                    'check', with_check
                )
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'store_profiles'
                  AND policyname = 'store_profiles_update_owner'
            ),
            'products_insert_requires_verified_seller', COALESCE((
                SELECT with_check LIKE '%is_seller_verified%'
                   AND with_check LIKE '%SELLER%'
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'products'
                  AND policyname = 'products_insert_seller'
            ), false)
        )

    UNION ALL

    SELECT
        'C04',
        'Policies',
        CASE
            WHEN COALESCE((
                SELECT with_check LIKE '%PENDING%' AND with_check LIKE '%REJECTED%'
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'seller_verifications'
                  AND policyname = 'Users can update own rejected verification'
            ), false)
            AND COALESCE((
                SELECT with_check LIKE '%PENDING%'
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'seller_verifications'
                  AND policyname = 'Users can submit own verification'
            ), false)
            THEN 'GREEN' ELSE 'RED'
        END,
        jsonb_build_object(
            'policies', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'name', policyname,
                        'cmd', cmd,
                        'roles', array_to_string(roles, ','),
                        'using', qual,
                        'check', with_check
                    ) ORDER BY policyname
                )
                FROM kyc_policies
            ), '[]'::jsonb),
            'update_requires_pending_or_rejected', COALESCE((
                SELECT with_check LIKE '%PENDING%' AND with_check LIKE '%REJECTED%'
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'seller_verifications'
                  AND policyname = 'Users can update own rejected verification'
            ), false),
            'insert_requires_pending', COALESCE((
                SELECT with_check LIKE '%PENDING%'
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'seller_verifications'
                  AND policyname = 'Users can submit own verification'
            ), false)
        )

    UNION ALL

    -- D. Grants, views, functions -------------------------------------------
    SELECT
        'D01',
        'Grants',
        CASE
            WHEN has_table_privilege('anon', 'public.users', 'SELECT')
              OR has_table_privilege('authenticated', 'public.users', 'SELECT')
            THEN 'RED' ELSE 'GREEN'
        END,
        jsonb_build_object(
            'anon', jsonb_build_object(
                'select', has_table_privilege('anon', 'public.users', 'SELECT'),
                'insert', has_table_privilege('anon', 'public.users', 'INSERT'),
                'update', has_table_privilege('anon', 'public.users', 'UPDATE'),
                'delete', has_table_privilege('anon', 'public.users', 'DELETE')
            ),
            'authenticated', jsonb_build_object(
                'select', has_table_privilege('authenticated', 'public.users', 'SELECT'),
                'insert', has_table_privilege('authenticated', 'public.users', 'INSERT'),
                'update', has_table_privilege('authenticated', 'public.users', 'UPDATE'),
                'delete', has_table_privilege('authenticated', 'public.users', 'DELETE')
            ),
            'service_role', jsonb_build_object(
                'select', has_table_privilege('service_role', 'public.users', 'SELECT'),
                'insert', has_table_privilege('service_role', 'public.users', 'INSERT'),
                'update', has_table_privilege('service_role', 'public.users', 'UPDATE'),
                'delete', has_table_privilege('service_role', 'public.users', 'DELETE')
            )
        )

    UNION ALL

    SELECT
        'D02',
        'Grants',
        CASE
            WHEN has_column_privilege('anon', 'public.users', 'email', 'SELECT')
              OR has_column_privilege('anon', 'public.users', 'password_hash', 'SELECT')
              OR has_column_privilege('anon', 'public.users', 'role', 'SELECT')
              OR has_column_privilege('anon', 'public.users', 'is_seller_verified', 'SELECT')
              OR has_column_privilege('authenticated', 'public.users', 'email', 'SELECT')
              OR has_column_privilege('authenticated', 'public.users', 'password_hash', 'SELECT')
            THEN 'RED' ELSE 'GREEN'
        END,
        jsonb_build_object(
            'column_grants', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'grantee', grantee,
                        'column', column_name,
                        'privilege', privilege_type,
                        'grantable', is_grantable
                    ) ORDER BY grantee, column_name, privilege_type
                )
                FROM information_schema.column_privileges
                WHERE table_schema = 'public'
                  AND table_name = 'users'
                  AND grantee IN ('anon', 'authenticated')
            ), '[]'::jsonb),
            'sensitive_has_column_privilege', jsonb_build_object(
                'anon_email', has_column_privilege('anon', 'public.users', 'email', 'SELECT'),
                'anon_password_hash', has_column_privilege('anon', 'public.users', 'password_hash', 'SELECT'),
                'anon_role', has_column_privilege('anon', 'public.users', 'role', 'SELECT'),
                'anon_is_seller_verified', has_column_privilege('anon', 'public.users', 'is_seller_verified', 'SELECT'),
                'authenticated_email', has_column_privilege('authenticated', 'public.users', 'email', 'SELECT'),
                'authenticated_password_hash', has_column_privilege('authenticated', 'public.users', 'password_hash', 'SELECT'),
                'authenticated_role', has_column_privilege('authenticated', 'public.users', 'role', 'SELECT'),
                'authenticated_is_seller_verified', has_column_privilege('authenticated', 'public.users', 'is_seller_verified', 'SELECT')
            )
        )

    UNION ALL

    SELECT
        'D03',
        'Views',
        CASE
            WHEN to_regclass('public.public_profiles') IS NOT NULL
             AND to_regclass('public.my_profile') IS NOT NULL
             AND to_regclass('public.admin_kyc_queue') IS NOT NULL
            THEN 'GREEN' ELSE 'RED'
        END,
        jsonb_build_object(
            'public_profiles_exists', to_regclass('public.public_profiles') IS NOT NULL,
            'public_profiles_definition', (
                SELECT definition FROM pg_views
                WHERE schemaname = 'public' AND viewname = 'public_profiles'
            ),
            'public_profiles_options', (
                SELECT array_to_string(reloptions, ',') FROM pg_class
                WHERE oid = to_regclass('public.public_profiles')
            ),
            'public_profiles_security_invoker', COALESCE((
                SELECT array_to_string(reloptions, ',') LIKE '%security_invoker=true%'
                FROM pg_class
                WHERE oid = to_regclass('public.public_profiles')
            ), false),
            'public_profiles_grants', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'grantee', grantee,
                        'privilege', privilege_type,
                        'grantable', is_grantable
                    ) ORDER BY grantee, privilege_type
                )
                FROM information_schema.role_table_grants
                WHERE table_schema = 'public'
                  AND table_name = 'public_profiles'
                  AND grantee IN ('anon', 'authenticated')
            ), '[]'::jsonb),
            'my_profile_exists', to_regclass('public.my_profile') IS NOT NULL,
            'my_profile_definition', (
                SELECT definition FROM pg_views
                WHERE schemaname = 'public' AND viewname = 'my_profile'
            ),
            'my_profile_options', (
                SELECT array_to_string(reloptions, ',') FROM pg_class
                WHERE oid = to_regclass('public.my_profile')
            ),
            'my_profile_grants', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'grantee', grantee,
                        'privilege', privilege_type,
                        'grantable', is_grantable
                    ) ORDER BY grantee, privilege_type
                )
                FROM information_schema.role_table_grants
                WHERE table_schema = 'public'
                  AND table_name = 'my_profile'
                  AND grantee IN ('anon', 'authenticated')
            ), '[]'::jsonb),
            'admin_kyc_queue_exists', to_regclass('public.admin_kyc_queue') IS NOT NULL,
            'admin_kyc_queue_definition', (
                SELECT definition FROM pg_views
                WHERE schemaname = 'public' AND viewname = 'admin_kyc_queue'
            ),
            'admin_kyc_queue_options', (
                SELECT array_to_string(reloptions, ',') FROM pg_class
                WHERE oid = to_regclass('public.admin_kyc_queue')
            ),
            'admin_kyc_queue_grants', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'grantee', grantee,
                        'privilege', privilege_type,
                        'grantable', is_grantable
                    ) ORDER BY grantee, privilege_type
                )
                FROM information_schema.role_table_grants
                WHERE table_schema = 'public'
                  AND table_name = 'admin_kyc_queue'
                  AND grantee IN ('anon', 'authenticated')
            ), '[]'::jsonb)
        )

    UNION ALL

    SELECT
        'D04',
        'Functions',
        CASE
            WHEN to_regprocedure('public.admin_verify_seller(uuid,boolean)') IS NOT NULL
              OR NOT COALESCE((
                    SELECT position('mikeljosh.nicer@cit.edu' IN pg_get_functiondef(p.oid)) = 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.admin_verify_seller(uuid,boolean,text)')
                 ), false)
            THEN 'WARN' ELSE 'INFO'
        END,
        jsonb_build_object(
            'protect_user_security_columns', COALESCE((
                SELECT jsonb_build_object(
                    'exists', true,
                    'security_definer', p.prosecdef,
                    'owner', pg_get_userbyid(p.proowner),
                    'config', p.proconfig
                )
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
            ), '{"exists": false}'::jsonb),
            'enforce_default_role_on_insert', COALESCE((
                SELECT jsonb_build_object(
                    'exists', true,
                    'security_definer', p.prosecdef,
                    'owner', pg_get_userbyid(p.proowner),
                    'config', p.proconfig
                )
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), '{"exists": false}'::jsonb),
            'admin_verify_seller_3arg', COALESCE((
                SELECT jsonb_build_object(
                    'exists', true,
                    'security_definer', p.prosecdef,
                    'owner', pg_get_userbyid(p.proowner),
                    'definition', pg_get_functiondef(p.oid)
                )
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.admin_verify_seller(uuid,boolean,text)')
            ), '{"exists": false}'::jsonb),
            'admin_verify_seller_2arg_exists', to_regprocedure('public.admin_verify_seller(uuid,boolean)') IS NOT NULL,
            'admin_verify_seller_hardened', COALESCE((
                SELECT position('mikeljosh.nicer@cit.edu' IN pg_get_functiondef(p.oid)) = 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.admin_verify_seller(uuid,boolean,text)')
            ), false)
        )

    UNION ALL

    -- E. Triggers ------------------------------------------------------------
    SELECT
        'E01',
        'Triggers',
        'INFO',
        jsonb_build_object(
            'users_triggers', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'name', t.tgname,
                        'enabled', t.tgenabled,
                        'definition', pg_get_triggerdef(t.oid),
                        'function', p.proname,
                        'security_definer', p.prosecdef,
                        'owner', pg_get_userbyid(p.proowner)
                    ) ORDER BY t.tgname
                )
                FROM pg_trigger t
                JOIN pg_proc p ON p.oid = t.tgfoid
                WHERE t.tgrelid = 'public.users'::regclass
                  AND NOT t.tgisinternal
            ), '[]'::jsonb)
        )

    UNION ALL

    SELECT
        'E02',
        'Triggers',
        CASE
            WHEN COALESCE((
                    SELECT p.prosecdef FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
                 ), false)
              OR COALESCE((
                    SELECT p.prosecdef FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
                 ), false)
              OR COALESCE((
                    SELECT position('current_user' IN pg_get_functiondef(p.oid)) > 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
                 ), false)
              OR COALESCE((
                    SELECT position('current_user' IN pg_get_functiondef(p.oid)) > 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
                 ), false)
              OR NOT COALESCE((
                    SELECT position('password_hash' IN pg_get_functiondef(p.oid)) > 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
                 ), false)
              OR NOT COALESCE((
                    SELECT position('''SELLER''' IN pg_get_functiondef(p.oid)) > 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
                 ), false)
              OR NOT COALESCE((
                    SELECT position('''ADMIN''' IN pg_get_functiondef(p.oid)) > 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
                 ), false)
            THEN 'RED' ELSE 'GREEN'
        END,
        jsonb_build_object(
            'protect_security_definer', COALESCE((
                SELECT p.prosecdef FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
            ), false),
            'protect_current_user_guard', COALESCE((
                SELECT position('current_user' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
            ), false),
            'protect_session_user_guard', COALESCE((
                SELECT position('session_user' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
            ), false),
            'protect_guards_password_hash', COALESCE((
                SELECT position('password_hash' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
            ), false),
            'protect_definition', (
                SELECT pg_get_functiondef(p.oid)
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
            ),
            'insert_security_definer', COALESCE((
                SELECT p.prosecdef FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), false),
            'insert_current_user_guard', COALESCE((
                SELECT position('current_user' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), false),
            'insert_session_user_guard', COALESCE((
                SELECT position('session_user' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), false),
            'insert_forces_buyer', COALESCE((
                SELECT position('NEW.role := ''BUYER''' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), false),
            'insert_allows_seller', COALESCE((
                SELECT position('''SELLER''' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), false),
            'insert_blocks_admin', COALESCE((
                SELECT position('''ADMIN''' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), false),
            'insert_definition', (
                SELECT pg_get_functiondef(p.oid)
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            )
        )

    UNION ALL

    -- F. Counts and inventory ------------------------------------------------
    SELECT
        'F01',
        'Counts',
        'INFO',
        jsonb_build_object(
            'users_total', (SELECT count(*) FROM public.users),
            'users_verified', (SELECT count(*) FROM public.users WHERE is_verified),
            'users_seller_verified', (SELECT count(*) FROM public.users WHERE is_seller_verified),
            'users_locked', (SELECT count(*) FROM public.users WHERE is_locked),
            'by_role', COALESCE((
                SELECT jsonb_object_agg(role::text, cnt)
                FROM (
                    SELECT role, count(*) AS cnt
                    FROM public.users
                    GROUP BY role
                ) r
            ), '{}'::jsonb),
            'admins', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'user_id', user_id,
                        'role', role,
                        'is_verified', is_verified,
                        'is_seller_verified', is_seller_verified,
                        'is_locked', is_locked,
                        'created_at', created_at
                    ) ORDER BY created_at
                )
                FROM public.users
                WHERE role = 'ADMIN'
            ), '[]'::jsonb),
            'sellers', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'user_id', user_id,
                        'role', role,
                        'is_verified', is_verified,
                        'is_seller_verified', is_seller_verified,
                        'is_locked', is_locked,
                        'created_at', created_at
                    ) ORDER BY created_at
                )
                FROM public.users
                WHERE role = 'SELLER'
            ), '[]'::jsonb)
        )

    UNION ALL

    SELECT
        'F02',
        'Counts',
        CASE
            WHEN (SELECT count(*) FROM auth.users) =
                 (SELECT count(*) FROM public.users)
            THEN 'INFO' ELSE 'WARN'
        END,
        jsonb_build_object(
            'auth_users', (SELECT count(*) FROM auth.users),
            'public_users', (SELECT count(*) FROM public.users),
            'auth_without_public_profile', (
                SELECT count(*)
                FROM auth.users au
                WHERE NOT EXISTS (
                    SELECT 1 FROM public.users u WHERE u.user_id = au.id
                )
            ),
            'public_without_auth_identity', (
                SELECT count(*)
                FROM public.users u
                WHERE NOT EXISTS (
                    SELECT 1 FROM auth.users au WHERE au.id = u.user_id
                )
            )
        )

    UNION ALL

    SELECT
        'F03',
        'Counts',
        'INFO',
        jsonb_build_object(
            'seller_verifications', (SELECT count(*) FROM public.seller_verifications),
            'seller_verifications_by_status', COALESCE((
                SELECT jsonb_object_agg(status, cnt)
                FROM (
                    SELECT status, count(*) AS cnt
                    FROM public.seller_verifications
                    GROUP BY status
                ) s
            ), '{}'::jsonb),
            'seller_verifications_latest_by_status', COALESCE((
                SELECT jsonb_object_agg(status, cnt)
                FROM (
                    SELECT status, count(*) AS cnt
                    FROM (
                        SELECT DISTINCT ON (user_id) user_id, status
                        FROM public.seller_verifications
                        ORDER BY user_id, created_at DESC NULLS LAST, id DESC
                    ) latest
                    GROUP BY status
                ) s
            ), '{}'::jsonb),
            'products', (SELECT count(*) FROM public.products),
            'orders', (SELECT count(*) FROM public.orders),
            'chats', (SELECT count(*) FROM public.chats),
            'messages', (SELECT count(*) FROM public.messages),
            'inquiries', (SELECT count(*) FROM public.inquiries)
        )

    UNION ALL

    SELECT
        'F04',
        'Inventory',
        'INFO',
        jsonb_build_object(
            'users_columns', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'column', column_name,
                        'type', data_type,
                        'nullable', is_nullable,
                        'default', column_default
                    ) ORDER BY ordinal_position
                )
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users'
            ), '[]'::jsonb)
        )

    UNION ALL

    SELECT
        'F05',
        'Inventory',
        CASE
            WHEN COALESCE((
                SELECT (length(pg_get_constraintdef(oid))
                        - length(replace(pg_get_constraintdef(oid), '%@cit.edu', '')))
                       / length('%@cit.edu') > 1
                FROM pg_constraint
                WHERE conrelid = 'public.users'::regclass
                  AND contype = 'c'
                  AND pg_get_constraintdef(oid) LIKE '%@cit.edu%'
                LIMIT 1
            ), false)
            THEN 'WARN' ELSE 'INFO'
        END,
        jsonb_build_object(
            'constraints', COALESCE((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'name', conname,
                        'type', contype,
                        'definition', pg_get_constraintdef(oid)
                    ) ORDER BY conname
                )
                FROM pg_constraint
                WHERE conrelid = 'public.users'::regclass
            ), '[]'::jsonb)
        )

    UNION ALL

    SELECT
        'F06',
        'Inventory',
        'INFO',
        jsonb_build_object(
            'public_users_columns', jsonb_build_object(
                'avatar_url', EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'users'
                      AND column_name = 'avatar_url'
                ),
                'student_id', EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'users'
                      AND column_name = 'student_id'
                ),
                'contact', EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'users'
                      AND column_name = 'contact'
                ),
                'gcash_number', EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'users'
                      AND column_name = 'gcash_number'
                ),
                'seller_type', EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'users'
                      AND column_name = 'seller_type'
                ),
                'last_seen_at', EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'users'
                      AND column_name = 'last_seen_at'
                )
            ),
            'auth_avatar_metadata_present', (
                SELECT count(*)
                FROM auth.users
                WHERE raw_user_meta_data ? 'avatar_url'
            )
        )

    UNION ALL

    -- G. Executive summary ---------------------------------------------------
    SELECT
        'G01',
        'Summary',
        CASE
            WHEN NOT COALESCE((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.users'::regclass), false)
              OR NOT COALESCE((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.chats'::regclass), false)
              OR NOT COALESCE((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.messages'::regclass), false)
              OR NOT COALESCE((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.inquiries'::regclass), false)
              OR has_table_privilege('anon', 'public.users', 'SELECT')
              OR has_table_privilege('authenticated', 'public.users', 'SELECT')
              OR has_column_privilege('anon', 'public.users', 'email', 'SELECT')
              OR has_column_privilege('anon', 'public.users', 'password_hash', 'SELECT')
              OR has_column_privilege('authenticated', 'public.users', 'email', 'SELECT')
              OR has_column_privilege('authenticated', 'public.users', 'password_hash', 'SELECT')
              OR COALESCE((
                    SELECT p.prosecdef FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
                 ), false)
              OR COALESCE((
                    SELECT p.prosecdef FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
                 ), false)
              OR COALESCE((
                    SELECT position('current_user' IN pg_get_functiondef(p.oid)) > 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
                 ), false)
              OR COALESCE((
                    SELECT position('current_user' IN pg_get_functiondef(p.oid)) > 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
                 ), false)
              OR NOT COALESCE((
                    SELECT position('password_hash' IN pg_get_functiondef(p.oid)) > 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
                 ), false)
              OR NOT COALESCE((
                    SELECT with_check LIKE '%is_seller_verified%' AND with_check LIKE '%SELLER%'
                    FROM pg_policies
                    WHERE schemaname = 'public'
                      AND tablename = 'products'
                      AND policyname = 'products_insert_seller'
                 ), false)
              OR to_regclass('public.public_profiles') IS NULL
              OR to_regclass('public.my_profile') IS NULL
              OR to_regclass('public.admin_kyc_queue') IS NULL
              OR NOT COALESCE((
                    SELECT with_check LIKE '%PENDING%' AND with_check LIKE '%REJECTED%'
                    FROM pg_policies
                    WHERE schemaname = 'public'
                      AND tablename = 'seller_verifications'
                      AND policyname = 'Users can update own rejected verification'
                 ), false)
              OR NOT COALESCE((
                    SELECT position('mikeljosh.nicer@cit.edu' IN pg_get_functiondef(p.oid)) = 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.admin_verify_seller(uuid,boolean,text)')
                 ), false)
              OR NOT COALESCE((
                    SELECT position('''SELLER''' IN pg_get_functiondef(p.oid)) > 0
                    FROM pg_proc p
                    WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
                 ), false)
              OR NOT EXISTS (
                    SELECT 1 FROM public.users
                    WHERE role = 'ADMIN' AND is_verified AND NOT is_locked
                 )
            THEN 'RED' ELSE 'GREEN'
        END,
        jsonb_build_object(
            'users_rls_enabled', COALESCE((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.users'::regclass), false),
            'chats_rls_enabled', COALESCE((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.chats'::regclass), false),
            'messages_rls_enabled', COALESCE((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.messages'::regclass), false),
            'inquiries_rls_enabled', COALESCE((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.inquiries'::regclass), false),
            'users_table_select_anon', has_table_privilege('anon', 'public.users', 'SELECT'),
            'users_table_select_authenticated', has_table_privilege('authenticated', 'public.users', 'SELECT'),
            'users_sensitive_select_anon', (
                has_column_privilege('anon', 'public.users', 'email', 'SELECT')
                OR has_column_privilege('anon', 'public.users', 'password_hash', 'SELECT')
            ),
            'users_sensitive_select_authenticated', (
                has_column_privilege('authenticated', 'public.users', 'email', 'SELECT')
                OR has_column_privilege('authenticated', 'public.users', 'password_hash', 'SELECT')
            ),
            'protect_trigger_security_definer', COALESCE((
                SELECT p.prosecdef FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
            ), false),
            'protect_trigger_current_user_guard', COALESCE((
                SELECT position('current_user' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
            ), false),
            'insert_trigger_security_definer', COALESCE((
                SELECT p.prosecdef FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), false),
            'insert_trigger_current_user_guard', COALESCE((
                SELECT position('current_user' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), false),
            'password_hash_guarded', COALESCE((
                SELECT position('password_hash' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
            ), false),
            'insert_allows_seller', COALESCE((
                SELECT position('''SELLER''' IN pg_get_functiondef(p.oid)) > 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
            ), false),
            'products_insert_requires_verified_seller', COALESCE((
                SELECT with_check LIKE '%is_seller_verified%' AND with_check LIKE '%SELLER%'
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'products'
                  AND policyname = 'products_insert_seller'
            ), false),
            'public_profiles_exists', to_regclass('public.public_profiles') IS NOT NULL,
            'my_profile_exists', to_regclass('public.my_profile') IS NOT NULL,
            'admin_kyc_queue_exists', to_regclass('public.admin_kyc_queue') IS NOT NULL,
            'kyc_self_approval_blocked', COALESCE((
                SELECT with_check LIKE '%PENDING%' AND with_check LIKE '%REJECTED%'
                FROM pg_policies
                WHERE schemaname = 'public'
                  AND tablename = 'seller_verifications'
                  AND policyname = 'Users can update own rejected verification'
            ), false),
            'admin_verify_seller_hardened', COALESCE((
                SELECT position('mikeljosh.nicer@cit.edu' IN pg_get_functiondef(p.oid)) = 0
                FROM pg_proc p
                WHERE p.oid = to_regprocedure('public.admin_verify_seller(uuid,boolean,text)')
            ), false),
            'verified_admin_exists', EXISTS (
                SELECT 1 FROM public.users
                WHERE role = 'ADMIN' AND is_verified AND NOT is_locked
            )
        )
)

SELECT check_id, area, state, detail
FROM checks
ORDER BY check_id;
