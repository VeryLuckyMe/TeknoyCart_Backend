-- ============================================================================
-- W1 auth verification — READ-ONLY — expected RED before the W1 migration
-- ============================================================================
-- Run this before and after the W1 forward migration. Each row is PASS/FAIL.
-- The last row is ZZ_SUMMARY. Before W1, many rows are expected to FAIL.
-- This script only reads catalogs, policies, grants, and definitions.
-- It does not select emails, names, tokens, phone numbers, or storage paths.
-- It does not create, alter, drop, insert, update, or delete anything.
-- Target ledger version: V9__auth_hardening
-- ============================================================================

WITH
protect AS (
    SELECT pg_get_functiondef(p.oid) AS def, p.prosecdef
    FROM pg_proc p
    WHERE p.oid = to_regprocedure('public.protect_user_security_columns()')
),
ins AS (
    SELECT pg_get_functiondef(p.oid) AS def, p.prosecdef
    FROM pg_proc p
    WHERE p.oid = to_regprocedure('public.enforce_default_role_on_insert()')
),
admin_fn AS (
    SELECT pg_get_functiondef(p.oid) AS def
    FROM pg_proc p
    WHERE p.oid = to_regprocedure('public.admin_verify_seller(uuid,boolean,text)')
),
my_profile AS (
    SELECT definition FROM pg_views
    WHERE schemaname = 'public' AND viewname = 'my_profile'
),
public_profiles AS (
    SELECT definition FROM pg_views
    WHERE schemaname = 'public' AND viewname = 'public_profiles'
),
admin_queue AS (
    SELECT definition FROM pg_views
    WHERE schemaname = 'public' AND viewname = 'admin_kyc_queue'
),
my_store AS (
    SELECT definition FROM pg_views
    WHERE schemaname = 'public' AND viewname = 'my_store_profile'
),
public_store AS (
    SELECT definition FROM pg_views
    WHERE schemaname = 'public' AND viewname = 'public_store_profiles'
),
checks AS (
    -- RLS -------------------------------------------------------------------
    SELECT 'R01' AS check_id, 'RLS' AS area, 'true' AS expected,
           COALESCE((SELECT relrowsecurity::text FROM pg_class WHERE oid = 'public.users'::regclass), 'missing') AS actual
    UNION ALL
    SELECT 'R02', 'RLS', 'true',
           COALESCE((SELECT relrowsecurity::text FROM pg_class WHERE oid = 'public.chats'::regclass), 'missing')
    UNION ALL
    SELECT 'R03', 'RLS', 'true',
           COALESCE((SELECT relrowsecurity::text FROM pg_class WHERE oid = 'public.messages'::regclass), 'missing')
    UNION ALL
    SELECT 'R04', 'RLS', 'true',
           COALESCE((SELECT relrowsecurity::text FROM pg_class WHERE oid = 'public.inquiries'::regclass), 'missing')

    -- TABLE-LEVEL GRANTS ----------------------------------------------------
    UNION ALL
    SELECT 'G01', 'Grants', 'false', has_table_privilege('anon', 'public.users', 'SELECT')::text
    UNION ALL
    SELECT 'G02', 'Grants', 'false', has_table_privilege('authenticated', 'public.users', 'SELECT')::text
    UNION ALL
    SELECT 'G03', 'Grants', 'false', has_table_privilege('anon', 'public.users', 'INSERT')::text
    UNION ALL
    SELECT 'G04', 'Grants', 'false', has_table_privilege('authenticated', 'public.users', 'INSERT')::text
    UNION ALL
    SELECT 'G05', 'Grants', 'false', has_table_privilege('anon', 'public.users', 'UPDATE')::text
    UNION ALL
    SELECT 'G06', 'Grants', 'false', has_table_privilege('authenticated', 'public.users', 'UPDATE')::text
    UNION ALL
    SELECT 'G07', 'Grants', 'false', has_table_privilege('anon', 'public.users', 'DELETE')::text
    UNION ALL
    SELECT 'G08', 'Grants', 'false', has_table_privilege('authenticated', 'public.users', 'DELETE')::text

    -- AUTHENTICATED COLUMN SELECT -------------------------------------------
    UNION ALL
    SELECT 'G10', 'Grants', 'true', has_column_privilege('authenticated', 'public.users', 'user_id', 'SELECT')::text
    UNION ALL
    SELECT 'G11', 'Grants', 'true', has_column_privilege('authenticated', 'public.users', 'full_name', 'SELECT')::text
    UNION ALL
    SELECT 'G12', 'Grants', 'true', has_column_privilege('authenticated', 'public.users', 'role', 'SELECT')::text
    UNION ALL
    SELECT 'G13', 'Grants', 'true', has_column_privilege('authenticated', 'public.users', 'is_seller_verified', 'SELECT')::text
    UNION ALL
    SELECT 'G14', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'email', 'SELECT')::text
    UNION ALL
    SELECT 'G15', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'password_hash', 'SELECT')::text
    UNION ALL
    SELECT 'G16', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'verification_token', 'SELECT')::text
    UNION ALL
    SELECT 'G17', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'is_verified', 'SELECT')::text
    UNION ALL
    SELECT 'G18', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'is_locked', 'SELECT')::text
    UNION ALL
    SELECT 'G19', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'lock_until', 'SELECT')::text
    UNION ALL
    SELECT 'G20', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'failed_attempts', 'SELECT')::text
    UNION ALL
    SELECT 'G21', 'Grants', 'false',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'student_id')
                THEN 'false'
                ELSE has_column_privilege('authenticated', 'public.users', 'student_id', 'SELECT')::text END
    UNION ALL
    SELECT 'G22', 'Grants', 'false',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'contact')
                THEN 'false'
                ELSE has_column_privilege('authenticated', 'public.users', 'contact', 'SELECT')::text END
    UNION ALL
    SELECT 'G23', 'Grants', 'false',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'gcash_number')
                THEN 'false'
                ELSE has_column_privilege('authenticated', 'public.users', 'gcash_number', 'SELECT')::text END
    UNION ALL
    SELECT 'G24', 'Grants', 'true',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'last_seen_at')
                THEN 'true'
                ELSE has_column_privilege('authenticated', 'public.users', 'last_seen_at', 'SELECT')::text END

    -- ANONYMOUS COLUMN SELECT -----------------------------------------------
    UNION ALL
    SELECT 'G30', 'Grants', 'false', has_column_privilege('anon', 'public.users', 'user_id', 'SELECT')::text
    UNION ALL
    SELECT 'G31', 'Grants', 'false', has_column_privilege('anon', 'public.users', 'email', 'SELECT')::text
    UNION ALL
    SELECT 'G32', 'Grants', 'false', has_column_privilege('anon', 'public.users', 'password_hash', 'SELECT')::text

    -- AUTHENTICATED COLUMN INSERT -------------------------------------------
    UNION ALL
    SELECT 'G40', 'Grants', 'true', has_column_privilege('authenticated', 'public.users', 'user_id', 'INSERT')::text
    UNION ALL
    SELECT 'G41', 'Grants', 'true', has_column_privilege('authenticated', 'public.users', 'full_name', 'INSERT')::text
    UNION ALL
    SELECT 'G42', 'Grants', 'true', has_column_privilege('authenticated', 'public.users', 'role', 'INSERT')::text
    UNION ALL
    SELECT 'G43', 'Grants', 'true',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'student_id')
                THEN 'true'
                ELSE has_column_privilege('authenticated', 'public.users', 'student_id', 'INSERT')::text END
    UNION ALL
    SELECT 'G44', 'Grants', 'true',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'seller_type')
                THEN 'true'
                ELSE has_column_privilege('authenticated', 'public.users', 'seller_type', 'INSERT')::text END
    UNION ALL
    SELECT 'G45', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'email', 'INSERT')::text
    UNION ALL
    SELECT 'G46', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'password_hash', 'INSERT')::text
    UNION ALL
    SELECT 'G47', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'is_verified', 'INSERT')::text
    UNION ALL
    SELECT 'G48', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'is_seller_verified', 'INSERT')::text
    UNION ALL
    SELECT 'G49', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'verification_token', 'INSERT')::text
    UNION ALL
    SELECT 'G50', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'is_locked', 'INSERT')::text
    UNION ALL
    SELECT 'G51', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'failed_attempts', 'INSERT')::text
    UNION ALL
    SELECT 'G52', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'lock_until', 'INSERT')::text

    -- AUTHENTICATED COLUMN UPDATE -------------------------------------------
    UNION ALL
    SELECT 'G60', 'Grants', 'true', has_column_privilege('authenticated', 'public.users', 'full_name', 'UPDATE')::text
    UNION ALL
    SELECT 'G61', 'Grants', 'true',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'contact')
                THEN 'true'
                ELSE has_column_privilege('authenticated', 'public.users', 'contact', 'UPDATE')::text END
    UNION ALL
    SELECT 'G62', 'Grants', 'true',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'gcash_number')
                THEN 'true'
                ELSE has_column_privilege('authenticated', 'public.users', 'gcash_number', 'UPDATE')::text END
    UNION ALL
    SELECT 'G63', 'Grants', 'true',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'last_seen_at')
                THEN 'true'
                ELSE has_column_privilege('authenticated', 'public.users', 'last_seen_at', 'UPDATE')::text END
    UNION ALL
    SELECT 'G64', 'Grants', 'false',
           CASE WHEN NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'student_id')
                THEN 'false'
                ELSE has_column_privilege('authenticated', 'public.users', 'student_id', 'UPDATE')::text END
    UNION ALL
    SELECT 'G65', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'role', 'UPDATE')::text
    UNION ALL
    SELECT 'G66', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'email', 'UPDATE')::text
    UNION ALL
    SELECT 'G67', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'password_hash', 'UPDATE')::text
    UNION ALL
    SELECT 'G68', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'is_verified', 'UPDATE')::text
    UNION ALL
    SELECT 'G69', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'is_seller_verified', 'UPDATE')::text
    UNION ALL
    SELECT 'G70', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'verification_token', 'UPDATE')::text
    UNION ALL
    SELECT 'G71', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'is_locked', 'UPDATE')::text
    UNION ALL
    SELECT 'G72', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'failed_attempts', 'UPDATE')::text
    UNION ALL
    SELECT 'G73', 'Grants', 'false', has_column_privilege('authenticated', 'public.users', 'lock_until', 'UPDATE')::text

    -- PROTECT USER SECURITY COLUMNS -----------------------------------------
    UNION ALL
    SELECT 'T01', 'Trigger fn', '1',
           (SELECT count(*)::text FROM pg_proc WHERE oid = to_regprocedure('public.protect_user_security_columns()'))
    UNION ALL
    SELECT 'T02', 'Trigger fn', 'false', COALESCE((SELECT prosecdef::text FROM protect), 'missing')
    UNION ALL
    SELECT 'T03', 'Trigger fn', 'false',
           COALESCE((SELECT position('current_user' in def) > 0 FROM protect), false)::text
    UNION ALL
    SELECT 'T04', 'Trigger fn', 'false',
           COALESCE((SELECT position('session_user' in def) > 0 FROM protect), false)::text
    UNION ALL
    SELECT 'T05', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM protect) LIKE '%NEW.role%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T06', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM protect) LIKE '%NEW.is_verified%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T07', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM protect) LIKE '%NEW.is_seller_verified%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T08', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM protect) LIKE '%NEW.email%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T09', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM protect) LIKE '%password_hash%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T10', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM protect) LIKE '%verification_token%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T11', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM protect) LIKE '%NEW.is_locked%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T12', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM protect) LIKE '%NEW.failed_attempts%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T13', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM protect) LIKE '%NEW.lock_until%' THEN 'present' ELSE 'missing' END

    -- ENFORCE ROLE ON INSERT ------------------------------------------------
    UNION ALL
    SELECT 'T20', 'Trigger fn', '1',
           (SELECT count(*)::text FROM pg_proc WHERE oid = to_regprocedure('public.enforce_default_role_on_insert()'))
    UNION ALL
    SELECT 'T21', 'Trigger fn', 'false', COALESCE((SELECT prosecdef::text FROM ins), 'missing')
    UNION ALL
    SELECT 'T22', 'Trigger fn', 'false',
           COALESCE((SELECT position('current_user' in def) > 0 FROM ins), false)::text
    UNION ALL
    SELECT 'T23', 'Trigger fn', 'false',
           COALESCE((SELECT position('session_user' in def) > 0 FROM ins), false)::text
    UNION ALL
    SELECT 'T24', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM ins) LIKE '%''BUYER''%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T25', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM ins) LIKE '%''SELLER''%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T26', 'Trigger fn', 'absent',
           CASE WHEN (SELECT def FROM ins) LIKE '%NEW.role := ''ADMIN''%' THEN 'present' ELSE 'absent' END
    UNION ALL
    SELECT 'T27', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM ins) LIKE '%email%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T28', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM ins) LIKE '%password_hash%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T29', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM ins) LIKE '%is_verified%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T30', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM ins) LIKE '%is_seller_verified%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T31', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM ins) LIKE '%verification_token%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T32', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM ins) LIKE '%failed_attempts%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'T33', 'Trigger fn', 'present',
           CASE WHEN (SELECT def FROM ins) LIKE '%lock_until%' THEN 'present' ELSE 'missing' END

    -- TRIGGERS --------------------------------------------------------------
    UNION ALL
    SELECT 'T40', 'Triggers', 'O',
           COALESCE((SELECT tgenabled::text FROM pg_trigger
                     WHERE tgrelid = 'public.users'::regclass
                       AND tgname = 'trg_protect_user_security_columns'
                       AND NOT tgisinternal), 'missing')
    UNION ALL
    SELECT 'T41', 'Triggers', 'O',
           COALESCE((SELECT tgenabled::text FROM pg_trigger
                     WHERE tgrelid = 'public.users'::regclass
                       AND tgname = 'trg_enforce_default_role_on_insert'
                       AND NOT tgisinternal), 'missing')

    -- USERS POLICIES --------------------------------------------------------
    UNION ALL
    SELECT 'P01', 'Policies', '1',
           (SELECT count(*)::text FROM pg_policies WHERE schemaname = 'public' AND tablename = 'users' AND policyname = 'users_select_authenticated')
    UNION ALL
    SELECT 'P02', 'Policies', '1',
           (SELECT count(*)::text FROM pg_policies WHERE schemaname = 'public' AND tablename = 'users' AND policyname = 'users_insert_own_profile')
    UNION ALL
    SELECT 'P03', 'Policies', '1',
           (SELECT count(*)::text FROM pg_policies WHERE schemaname = 'public' AND tablename = 'users' AND policyname = 'users_update_own_profile')
    UNION ALL
    SELECT 'P04', 'Policies', 'absent',
           CASE WHEN EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'users' AND 'anon'::name = ANY(roles))
                THEN 'present' ELSE 'absent' END

    -- PRODUCTS --------------------------------------------------------------
    UNION ALL
    SELECT 'P10', 'Policies', 'present',
           CASE WHEN (SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'products' AND policyname = 'products_insert_seller') LIKE '%is_seller_verified%'
                  AND (SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'products' AND policyname = 'products_insert_seller') LIKE '%SELLER%'
                THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'P11', 'Policies', 'present',
           CASE WHEN COALESCE((SELECT qual FROM pg_policies WHERE schemaname = 'public' AND tablename = 'products' AND policyname = 'products_update_seller'), '') LIKE '%is_seller_verified%'
                  AND COALESCE((SELECT qual FROM pg_policies WHERE schemaname = 'public' AND tablename = 'products' AND policyname = 'products_update_seller'), '') LIKE '%SELLER%'
                  AND COALESCE((SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'products' AND policyname = 'products_update_seller'), '') LIKE '%is_seller_verified%'
                  AND COALESCE((SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'products' AND policyname = 'products_update_seller'), '') LIKE '%SELLER%'
                THEN 'present' ELSE 'missing' END

    -- KYC -------------------------------------------------------------------
    UNION ALL
    SELECT 'P20', 'Policies', 'present',
           CASE WHEN COALESCE((SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'seller_verifications' AND policyname = 'Users can update own rejected verification'), '') LIKE '%PENDING%'
                  AND COALESCE((SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'seller_verifications' AND policyname = 'Users can update own rejected verification'), '') LIKE '%REJECTED%'
                  AND COALESCE((SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'seller_verifications' AND policyname = 'Users can update own rejected verification'), '') NOT LIKE '%APPROVED%'
                THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'P21', 'Policies', 'present',
           CASE WHEN COALESCE((SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'seller_verifications' AND policyname = 'Users can submit own verification'), '') LIKE '%PENDING%'
                  AND COALESCE((SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = 'seller_verifications' AND policyname = 'Users can submit own verification'), '') NOT LIKE '%APPROVED%'
                THEN 'present' ELSE 'missing' END

    -- ADMIN FUNCTION --------------------------------------------------------
    UNION ALL
    SELECT 'P30', 'Admin', '1',
           (SELECT count(*)::text FROM pg_proc WHERE oid = to_regprocedure('public.admin_verify_seller(uuid,boolean,text)'))
    UNION ALL
    SELECT 'P31', 'Admin', '0',
           (SELECT count(*)::text FROM pg_proc WHERE oid = to_regprocedure('public.admin_verify_seller(uuid,boolean)'))
    UNION ALL
    SELECT 'P32', 'Admin', 'true',
           COALESCE((SELECT position('mikeljosh.nicer@cit.edu' in def) = 0 FROM admin_fn), false)::text

    -- MY_PROFILE VIEW -------------------------------------------------------
    UNION ALL
    SELECT 'V01', 'Views', 'true', (to_regclass('public.my_profile') IS NOT NULL)::text
    UNION ALL
    SELECT 'V02', 'Views', 'false',
           CASE WHEN to_regclass('public.my_profile') IS NULL THEN 'missing'
                ELSE has_table_privilege('anon', 'public.my_profile', 'SELECT')::text END
    UNION ALL
    SELECT 'V03', 'Views', 'true',
           CASE WHEN to_regclass('public.my_profile') IS NULL THEN 'missing'
                ELSE has_table_privilege('authenticated', 'public.my_profile', 'SELECT')::text END
    UNION ALL
    SELECT 'V04', 'Views', 'true',
           CASE WHEN to_regclass('public.my_profile') IS NULL THEN 'missing'
                WHEN COALESCE((SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = to_regclass('public.my_profile')), '') LIKE '%security_invoker=true%'
                THEN 'false' ELSE 'true' END
    UNION ALL
    SELECT 'V05', 'Views', 'present',
           CASE WHEN (SELECT definition FROM my_profile) LIKE '%auth.uid()%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'V06', 'Views', 'absent',
           CASE WHEN (SELECT definition FROM my_profile) IS NULL THEN 'missing'
                WHEN (SELECT definition FROM my_profile) LIKE '%password_hash%' THEN 'present' ELSE 'absent' END
    UNION ALL
    SELECT 'V07', 'Views', 'absent',
           CASE WHEN (SELECT definition FROM my_profile) IS NULL THEN 'missing'
                WHEN (SELECT definition FROM my_profile) LIKE '%verification_token%' THEN 'present' ELSE 'absent' END
    UNION ALL
    SELECT 'V08', 'Views', 'absent',
           CASE WHEN (SELECT definition FROM my_profile) IS NULL THEN 'missing'
                WHEN (SELECT definition FROM my_profile) LIKE '%is_locked%' THEN 'present' ELSE 'absent' END

    -- PUBLIC_PROFILES VIEW --------------------------------------------------
    UNION ALL
    SELECT 'V10', 'Views', 'true', (to_regclass('public.public_profiles') IS NOT NULL)::text
    UNION ALL
    SELECT 'V11', 'Views', 'true',
           CASE WHEN to_regclass('public.public_profiles') IS NULL THEN 'missing'
                ELSE has_table_privilege('anon', 'public.public_profiles', 'SELECT')::text END
    UNION ALL
    SELECT 'V12', 'Views', 'true',
           CASE WHEN to_regclass('public.public_profiles') IS NULL THEN 'missing'
                ELSE has_table_privilege('authenticated', 'public.public_profiles', 'SELECT')::text END
    UNION ALL
    SELECT 'V13', 'Views', 'true',
           CASE WHEN to_regclass('public.public_profiles') IS NULL THEN 'missing'
                WHEN COALESCE((SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = to_regclass('public.public_profiles')), '') LIKE '%security_invoker=true%'
                THEN 'false' ELSE 'true' END
    UNION ALL
    SELECT 'V14', 'Views', 'absent',
           CASE WHEN (SELECT definition FROM public_profiles) IS NULL THEN 'missing'
                WHEN (SELECT definition FROM public_profiles) LIKE '%email%' THEN 'present' ELSE 'absent' END
    UNION ALL
    SELECT 'V15', 'Views', 'absent',
           CASE WHEN (SELECT definition FROM public_profiles) IS NULL THEN 'missing'
                WHEN (SELECT definition FROM public_profiles) LIKE '%student_id%' THEN 'present' ELSE 'absent' END
    UNION ALL
    SELECT 'V16', 'Views', 'absent',
           CASE WHEN (SELECT definition FROM public_profiles) IS NULL THEN 'missing'
                WHEN (SELECT definition FROM public_profiles) LIKE '%contact%' THEN 'present' ELSE 'absent' END
    UNION ALL
    SELECT 'V17', 'Views', 'absent',
           CASE WHEN (SELECT definition FROM public_profiles) IS NULL THEN 'missing'
                WHEN (SELECT definition FROM public_profiles) LIKE '%gcash%' THEN 'present' ELSE 'absent' END
    UNION ALL
    SELECT 'V18', 'Views', 'present',
           CASE WHEN (SELECT definition FROM public_profiles) LIKE '%is_seller_verified%' THEN 'present' ELSE 'missing' END

    -- ADMIN_KYC_QUEUE VIEW --------------------------------------------------
    UNION ALL
    SELECT 'V20', 'Views', 'true', (to_regclass('public.admin_kyc_queue') IS NOT NULL)::text
    UNION ALL
    SELECT 'V21', 'Views', 'false',
           CASE WHEN to_regclass('public.admin_kyc_queue') IS NULL THEN 'missing'
                ELSE has_table_privilege('anon', 'public.admin_kyc_queue', 'SELECT')::text END
    UNION ALL
    SELECT 'V22', 'Views', 'true',
           CASE WHEN to_regclass('public.admin_kyc_queue') IS NULL THEN 'missing'
                ELSE has_table_privilege('authenticated', 'public.admin_kyc_queue', 'SELECT')::text END
    UNION ALL
    SELECT 'V23', 'Views', 'true',
           CASE WHEN to_regclass('public.admin_kyc_queue') IS NULL THEN 'missing'
                WHEN COALESCE((SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = to_regclass('public.admin_kyc_queue')), '') LIKE '%security_invoker=true%'
                THEN 'false' ELSE 'true' END
    UNION ALL
    SELECT 'V24', 'Views', 'present',
           CASE WHEN (SELECT definition FROM admin_queue) LIKE '%ADMIN%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'V25', 'Views', 'present',
           CASE WHEN (SELECT definition FROM admin_queue) LIKE '%email%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'V26', 'Views', 'present',
           CASE WHEN (SELECT definition FROM admin_queue) LIKE '%student_id%' THEN 'present' ELSE 'missing' END

    -- STUDENT ID AVAILABILITY RPC -------------------------------------------
    UNION ALL
    SELECT 'V30', 'RPC', '1',
           (SELECT count(*)::text FROM pg_proc WHERE oid = to_regprocedure('public.is_student_id_available(text)'))
    UNION ALL
    SELECT 'V31', 'RPC', 'true',
           COALESCE((SELECT p.prosecdef::text FROM pg_proc p WHERE p.oid = to_regprocedure('public.is_student_id_available(text)')), 'missing')
    UNION ALL
    SELECT 'V32', 'RPC', 'true',
           COALESCE((SELECT (pg_get_function_result(p.oid) = 'boolean')::text FROM pg_proc p WHERE p.oid = to_regprocedure('public.is_student_id_available(text)')), 'missing')
    UNION ALL
    SELECT 'V33', 'RPC', 'true',
           CASE WHEN to_regprocedure('public.is_student_id_available(text)') IS NULL THEN 'missing'
                ELSE has_function_privilege('anon', 'public.is_student_id_available(text)', 'EXECUTE')::text END
    UNION ALL
    SELECT 'V34', 'RPC', 'true',
           CASE WHEN to_regprocedure('public.is_student_id_available(text)') IS NULL THEN 'missing'
                ELSE has_function_privilege('authenticated', 'public.is_student_id_available(text)', 'EXECUTE')::text END

    -- STORE PROFILES BASE TABLE ---------------------------------------------
    UNION ALL
    SELECT 'V40', 'Store', 'false', has_table_privilege('anon', 'public.store_profiles', 'SELECT')::text
    UNION ALL
    SELECT 'V41', 'Store', 'false', has_table_privilege('authenticated', 'public.store_profiles', 'SELECT')::text
    UNION ALL
    SELECT 'V42', 'Store', '0',
           (SELECT count(*)::text FROM information_schema.column_privileges
            WHERE table_schema = 'public' AND table_name = 'store_profiles'
              AND grantee IN ('anon', 'authenticated')
              AND (column_name ILIKE '%phone%' OR column_name ILIKE '%address%'
                   OR column_name ILIKE '%contact%' OR column_name ILIKE '%gcash%'))

    -- STORE PROFILE VIEWS ---------------------------------------------------
    UNION ALL
    SELECT 'V70', 'Store', 'true', (to_regclass('public.my_store_profile') IS NOT NULL)::text
    UNION ALL
    SELECT 'V71', 'Store', 'false',
           CASE WHEN to_regclass('public.my_store_profile') IS NULL THEN 'missing'
                ELSE has_table_privilege('anon', 'public.my_store_profile', 'SELECT')::text END
    UNION ALL
    SELECT 'V72', 'Store', 'true',
           CASE WHEN to_regclass('public.my_store_profile') IS NULL THEN 'missing'
                ELSE has_table_privilege('authenticated', 'public.my_store_profile', 'SELECT')::text END
    UNION ALL
    SELECT 'V73', 'Store', 'true',
           CASE WHEN to_regclass('public.my_store_profile') IS NULL THEN 'missing'
                WHEN COALESCE((SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = to_regclass('public.my_store_profile')), '') LIKE '%security_invoker=true%'
                THEN 'false' ELSE 'true' END
    UNION ALL
    SELECT 'V74', 'Store', 'present',
           CASE WHEN (SELECT definition FROM my_store) LIKE '%auth.uid()%' THEN 'present' ELSE 'missing' END
    UNION ALL
    SELECT 'V75', 'Store', 'true', (to_regclass('public.public_store_profiles') IS NOT NULL)::text
    UNION ALL
    SELECT 'V76', 'Store', 'true',
           CASE WHEN to_regclass('public.public_store_profiles') IS NULL THEN 'missing'
                ELSE has_table_privilege('anon', 'public.public_store_profiles', 'SELECT')::text END
    UNION ALL
    SELECT 'V77', 'Store', 'true',
           CASE WHEN to_regclass('public.public_store_profiles') IS NULL THEN 'missing'
                ELSE has_table_privilege('authenticated', 'public.public_store_profiles', 'SELECT')::text END
    UNION ALL
    SELECT 'V78', 'Store', 'true',
           CASE WHEN to_regclass('public.public_store_profiles') IS NULL THEN 'missing'
                WHEN COALESCE((SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = to_regclass('public.public_store_profiles')), '') LIKE '%security_invoker=true%'
                THEN 'false' ELSE 'true' END
    UNION ALL
    SELECT 'V79', 'Store', 'absent',
           CASE WHEN (SELECT definition FROM public_store) IS NULL THEN 'missing'
                WHEN (SELECT definition FROM public_store) ~* '(phone|address|contact|gcash)' THEN 'present'
                ELSE 'absent' END
    UNION ALL
    SELECT 'V80', 'Store', 'present',
           CASE WHEN (SELECT definition FROM public_store) LIKE '%store_name%' THEN 'present' ELSE 'missing' END

    -- REALTIME --------------------------------------------------------------
    UNION ALL
    SELECT 'V50', 'Realtime', 'true',
           (EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'chats'))::text
    UNION ALL
    SELECT 'V51', 'Realtime', 'true',
           (EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'messages'))::text
    UNION ALL
    SELECT 'V52', 'Realtime', 'false',
           (EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'users'))::text

    -- LEDGER ----------------------------------------------------------------
    UNION ALL
    SELECT 'V60', 'Ledger', 'true',
           (EXISTS (SELECT 1 FROM public.schema_migrations WHERE version = 'V9__auth_hardening'))::text
)
SELECT check_id, area, expected, actual,
       CASE WHEN actual = expected THEN 'PASS' ELSE 'FAIL' END AS status
FROM checks
UNION ALL
SELECT 'ZZ_SUMMARY', 'Summary', '0',
       (count(*) FILTER (WHERE actual <> expected))::text,
       CASE WHEN count(*) FILTER (WHERE actual <> expected) = 0 THEN 'PASS' ELSE 'FAIL' END
FROM checks
ORDER BY check_id;
