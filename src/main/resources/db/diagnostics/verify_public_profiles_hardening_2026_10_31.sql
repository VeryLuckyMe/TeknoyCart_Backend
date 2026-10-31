-- ============================================================================
-- Read-only verification for patch_public_profiles_hardening_2026_10_31.sql
-- Run in the Supabase SQL editor (or psql as postgres) after applying the patch.
-- Every row must be PASS. This script changes nothing.
-- ============================================================================

WITH v AS (
    SELECT to_regclass('public.public_profiles') AS oid,
           COALESCE((
               SELECT array_to_string(reloptions, ',')
               FROM pg_class
               WHERE oid = to_regclass('public.public_profiles')
           ), '') AS options,
           COALESCE((
               SELECT pg_get_viewdef(to_regclass('public.public_profiles'), true)
           ), '') AS definition
),
checks AS (
    SELECT 1 AS ord, 'public_profiles exists' AS check_name,
           CASE WHEN oid IS NOT NULL THEN 'PASS' ELSE 'FAIL' END AS result,
           (oid IS NOT NULL)::text AS observed,
           'true' AS expected
    FROM v

    UNION ALL
    SELECT 2, 'public_profiles security_invoker=true',
           CASE WHEN options LIKE '%security_invoker=true%' THEN 'PASS' ELSE 'FAIL' END,
           options,
           'contains security_invoker=true'
    FROM v

    UNION ALL
    SELECT 3, 'anon cannot SELECT public_profiles',
           CASE WHEN oid IS NULL THEN 'FAIL'
                WHEN has_table_privilege('anon', oid::oid, 'SELECT') THEN 'FAIL'
                ELSE 'PASS' END,
           COALESCE(has_table_privilege('anon', oid::oid, 'SELECT')::text, 'missing'),
           'false'
    FROM v

    UNION ALL
    SELECT 4, 'authenticated can SELECT public_profiles',
           CASE WHEN oid IS NULL THEN 'FAIL'
                WHEN has_table_privilege('authenticated', oid::oid, 'SELECT') THEN 'PASS'
                ELSE 'FAIL' END,
           COALESCE(has_table_privilege('authenticated', oid::oid, 'SELECT')::text, 'missing'),
           'true'
    FROM v

    UNION ALL
    SELECT 5, 'public_profiles has no auth.users dependency',
           CASE WHEN definition LIKE '%auth.users%' THEN 'FAIL' ELSE 'PASS' END,
           CASE WHEN definition LIKE '%auth.users%' THEN 'references auth.users' ELSE 'no reference' END,
           'no reference'
    FROM v

    UNION ALL
    SELECT 6, 'public_profiles has no raw_user_meta_data',
           CASE WHEN definition LIKE '%raw_user_meta_data%' THEN 'FAIL' ELSE 'PASS' END,
           CASE WHEN definition LIKE '%raw_user_meta_data%' THEN 'present' ELSE 'absent' END,
           'absent'
    FROM v

    UNION ALL
    SELECT 7, 'public_profiles has no email',
           CASE WHEN definition LIKE '%email%' THEN 'FAIL' ELSE 'PASS' END,
           CASE WHEN definition LIKE '%email%' THEN 'present' ELSE 'absent' END,
           'absent'
    FROM v

    UNION ALL
    SELECT 8, 'public_profiles has no student_id',
           CASE WHEN definition LIKE '%student_id%' THEN 'FAIL' ELSE 'PASS' END,
           CASE WHEN definition LIKE '%student_id%' THEN 'present' ELSE 'absent' END,
           'absent'
    FROM v

    UNION ALL
    SELECT 9, 'public_profiles has no contact',
           CASE WHEN definition LIKE '%contact%' THEN 'FAIL' ELSE 'PASS' END,
           CASE WHEN definition LIKE '%contact%' THEN 'present' ELSE 'absent' END,
           'absent'
    FROM v

    UNION ALL
    SELECT 10, 'public_profiles has no gcash',
           CASE WHEN definition LIKE '%gcash%' THEN 'FAIL' ELSE 'PASS' END,
           CASE WHEN definition LIKE '%gcash%' THEN 'present' ELSE 'absent' END,
           'absent'
    FROM v

    UNION ALL
    SELECT 11, 'public_profiles keeps full_name',
           CASE WHEN definition LIKE '%full_name%' THEN 'PASS' ELSE 'FAIL' END,
           CASE WHEN definition LIKE '%full_name%' THEN 'present' ELSE 'missing' END,
           'present'
    FROM v

    UNION ALL
    SELECT 12, 'public_profiles keeps is_seller_verified',
           CASE WHEN definition LIKE '%is_seller_verified%' THEN 'PASS' ELSE 'FAIL' END,
           CASE WHEN definition LIKE '%is_seller_verified%' THEN 'present' ELSE 'missing' END,
           'present'
    FROM v

    UNION ALL
    SELECT 13, 'public_profiles keeps last_seen_at',
           CASE WHEN definition LIKE '%last_seen_at%' THEN 'PASS' ELSE 'FAIL' END,
           CASE WHEN definition LIKE '%last_seen_at%' THEN 'present' ELSE 'missing' END,
           'present'
    FROM v

    UNION ALL
    SELECT 14, 'store_profiles has no table-level SELECT for anon',
           CASE WHEN has_table_privilege('anon', 'public.store_profiles', 'SELECT') THEN 'FAIL' ELSE 'PASS' END,
           has_table_privilege('anon', 'public.store_profiles', 'SELECT')::text,
           'false'

    UNION ALL
    SELECT 15, 'store_profiles has no table-level SELECT for authenticated',
           CASE WHEN has_table_privilege('authenticated', 'public.store_profiles', 'SELECT') THEN 'FAIL' ELSE 'PASS' END,
           has_table_privilege('authenticated', 'public.store_profiles', 'SELECT')::text,
           'false'

    UNION ALL
    SELECT 16, 'anon cannot SELECT store_profiles.contact_number',
           CASE WHEN has_column_privilege('anon', 'public.store_profiles', 'contact_number', 'SELECT') THEN 'FAIL' ELSE 'PASS' END,
           has_column_privilege('anon', 'public.store_profiles', 'contact_number', 'SELECT')::text,
           'false'

    UNION ALL
    SELECT 17, 'authenticated cannot SELECT store_profiles.contact_number',
           CASE WHEN has_column_privilege('authenticated', 'public.store_profiles', 'contact_number', 'SELECT') THEN 'FAIL' ELSE 'PASS' END,
           has_column_privilege('authenticated', 'public.store_profiles', 'contact_number', 'SELECT')::text,
           'false'

    UNION ALL
    SELECT 18, 'anon can SELECT store_profiles.store_name',
           CASE WHEN has_column_privilege('anon', 'public.store_profiles', 'store_name', 'SELECT') THEN 'PASS' ELSE 'FAIL' END,
           has_column_privilege('anon', 'public.store_profiles', 'store_name', 'SELECT')::text,
           'true'

    UNION ALL
    SELECT 19, 'anonymous users still cannot read my_profile',
           CASE WHEN to_regclass('public.my_profile') IS NULL THEN 'FAIL'
                WHEN has_table_privilege('anon', 'public.my_profile', 'SELECT') THEN 'FAIL'
                ELSE 'PASS' END,
           COALESCE(has_table_privilege('anon', 'public.my_profile', 'SELECT')::text, 'missing'),
           'false'

    UNION ALL
    SELECT 20, 'anonymous users still cannot read admin_kyc_queue',
           CASE WHEN to_regclass('public.admin_kyc_queue') IS NULL THEN 'FAIL'
                WHEN has_table_privilege('anon', 'public.admin_kyc_queue', 'SELECT') THEN 'FAIL'
                ELSE 'PASS' END,
           COALESCE(has_table_privilege('anon', 'public.admin_kyc_queue', 'SELECT')::text, 'missing'),
           'false'

    UNION ALL
    SELECT 21, 'anonymous users still cannot read my_order_codes',
           CASE WHEN to_regclass('public.my_order_codes') IS NULL THEN 'FAIL'
                WHEN has_table_privilege('anon', 'public.my_order_codes', 'SELECT') THEN 'FAIL'
                ELSE 'PASS' END,
           COALESCE(has_table_privilege('anon', 'public.my_order_codes', 'SELECT')::text, 'missing'),
           'false'

    UNION ALL
    SELECT 22, 'unused public_store_profiles definer view is dropped',
           CASE WHEN to_regclass('public.public_store_profiles') IS NULL THEN 'PASS' ELSE 'FAIL' END,
           CASE WHEN to_regclass('public.public_store_profiles') IS NULL THEN 'absent' ELSE 'present' END,
           'absent'

    UNION ALL
    SELECT 23, 'public_profiles keeps user_id',
           CASE WHEN definition LIKE '%user_id%' THEN 'PASS' ELSE 'FAIL' END,
           CASE WHEN definition LIKE '%user_id%' THEN 'present' ELSE 'missing' END,
           'present'
    FROM v

    UNION ALL
    SELECT 24, 'anon has no column-level SELECT on public_profiles.full_name',
           CASE WHEN has_column_privilege('anon', 'public.public_profiles', 'full_name', 'SELECT') THEN 'FAIL' ELSE 'PASS' END,
           has_column_privilege('anon', 'public.public_profiles', 'full_name', 'SELECT')::text,
           'false'
)
SELECT check_name, result, observed, expected
FROM (
    SELECT ord, check_name, result, observed, expected FROM checks
    UNION ALL
    SELECT 999, 'ZZ_SUMMARY',
           CASE WHEN count(*) FILTER (WHERE result = 'FAIL') = 0 THEN 'PASS' ELSE 'FAIL' END,
           (count(*) FILTER (WHERE result = 'FAIL'))::text || ' failures',
           '0 failures'
    FROM checks
) t
ORDER BY ord;
