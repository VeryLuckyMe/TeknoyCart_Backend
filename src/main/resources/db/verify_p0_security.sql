-- ============================================================================
-- TeknoyCart P0 hardening: READ-ONLY verification
-- Run in the Supabase SQL editor (or psql as postgres) after applying
-- db/migration_p0_security_hardening.sql. Every row must be PASS.
-- This script changes nothing.
-- ============================================================================

WITH checks AS (
    SELECT 'orders: anon has no privileges' AS check_name,
           CASE WHEN has_table_privilege('anon', 'public.orders', 'SELECT')
                  OR has_table_privilege('anon', 'public.orders', 'INSERT')
                  OR has_table_privilege('anon', 'public.orders', 'UPDATE')
                  OR has_table_privilege('anon', 'public.orders', 'DELETE')
                THEN 'FAIL' ELSE 'PASS' END AS result,
           concat_ws(',',
                     CASE WHEN has_table_privilege('anon', 'public.orders', 'SELECT') THEN 'SELECT' END,
                     CASE WHEN has_table_privilege('anon', 'public.orders', 'INSERT') THEN 'INSERT' END,
                     CASE WHEN has_table_privilege('anon', 'public.orders', 'UPDATE') THEN 'UPDATE' END,
                     CASE WHEN has_table_privilege('anon', 'public.orders', 'DELETE') THEN 'DELETE' END) AS observed,
           '(none)' AS expected

    UNION ALL
    SELECT 'orders: authenticated INSERT revoked',
           CASE WHEN has_table_privilege('authenticated', 'public.orders', 'INSERT')
                THEN 'FAIL' ELSE 'PASS' END AS result,
           has_table_privilege('authenticated', 'public.orders', 'INSERT')::text AS observed,
           'false' AS expected

    UNION ALL
    SELECT 'orders: handoff_otp hidden from authenticated',
           CASE WHEN has_column_privilege('authenticated', 'public.orders', 'handoff_otp', 'SELECT')
                THEN 'FAIL' ELSE 'PASS' END,
           has_column_privilege('authenticated', 'public.orders', 'handoff_otp', 'SELECT')::text,
           'false'

    UNION ALL
    SELECT 'orders: return_otp hidden from authenticated',
           CASE WHEN has_column_privilege('authenticated', 'public.orders', 'return_otp', 'SELECT')
                THEN 'FAIL' ELSE 'PASS' END,
           has_column_privilege('authenticated', 'public.orders', 'return_otp', 'SELECT')::text,
           'false'

    UNION ALL
    SELECT 'orders: OTP columns not updatable by authenticated',
           CASE WHEN has_column_privilege('authenticated', 'public.orders', 'handoff_otp', 'UPDATE')
                  OR has_column_privilege('authenticated', 'public.orders', 'return_otp', 'UPDATE')
                THEN 'FAIL' ELSE 'PASS' END,
           concat_ws(',',
                     CASE WHEN has_column_privilege('authenticated', 'public.orders', 'handoff_otp', 'UPDATE') THEN 'handoff_otp' END,
                     CASE WHEN has_column_privilege('authenticated', 'public.orders', 'return_otp', 'UPDATE') THEN 'return_otp' END),
           '(none)'

    UNION ALL
    SELECT 'orders: only the canonical policies remain',
           CASE WHEN count(*) FILTER (WHERE policyname NOT IN
                         ('orders_select_parties', 'orders_select_admin', 'orders_update_admin')) = 0
                     AND count(*) FILTER (WHERE policyname IN
                         ('orders_select_parties', 'orders_select_admin', 'orders_update_admin')) = 3
                THEN 'PASS' ELSE 'FAIL' END,
           COALESCE(string_agg(policyname, ', ' ORDER BY policyname), '(none)'),
           'orders_select_parties, orders_select_admin, orders_update_admin'
      FROM pg_policies
     WHERE schemaname = 'public' AND tablename = 'orders'

    UNION ALL
    SELECT 'orders: completed_at column exists',
           CASE WHEN EXISTS (
                    SELECT 1 FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'orders'
                       AND column_name = 'completed_at')
                THEN 'PASS' ELSE 'FAIL' END,
           (SELECT COALESCE(data_type, '(missing)') FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = 'orders'
               AND column_name = 'completed_at'),
           'timestamp with time zone'

    UNION ALL
    SELECT 'orders: insert-integrity trigger installed',
           CASE WHEN EXISTS (
                    SELECT 1 FROM pg_trigger
                     WHERE tgname = 'trg_enforce_order_insert_integrity' AND NOT tgisinternal)
                THEN 'PASS' ELSE 'FAIL' END,
           (SELECT COALESCE(tgenabled::text, '(missing)') FROM pg_trigger
             WHERE tgname = 'trg_enforce_order_insert_integrity' AND NOT tgisinternal),
           'trigger enabled'

    UNION ALL
    SELECT 'inventory: only the canonical policies remain',
           CASE WHEN count(*) FILTER (WHERE policyname NOT IN
                         ('inventory_select_public', 'inventory_insert_seller', 'inventory_update_seller')) = 0
                     AND count(*) FILTER (WHERE policyname IN
                         ('inventory_select_public', 'inventory_insert_seller', 'inventory_update_seller')) = 3
                THEN 'PASS' ELSE 'FAIL' END,
           COALESCE(string_agg(policyname, ', ' ORDER BY policyname), '(none)'),
           'inventory_select_public, inventory_insert_seller, inventory_update_seller'
      FROM pg_policies
     WHERE schemaname = 'public' AND tablename = 'inventory'

    UNION ALL
    SELECT 'inventory: anon cannot write stock',
           CASE WHEN has_table_privilege('anon', 'public.inventory', 'INSERT')
                  OR has_table_privilege('anon', 'public.inventory', 'UPDATE')
                  OR has_table_privilege('anon', 'public.inventory', 'DELETE')
                THEN 'FAIL' ELSE 'PASS' END,
           concat_ws(',',
                     CASE WHEN has_table_privilege('anon', 'public.inventory', 'INSERT') THEN 'INSERT' END,
                     CASE WHEN has_table_privilege('anon', 'public.inventory', 'UPDATE') THEN 'UPDATE' END,
                     CASE WHEN has_table_privilege('anon', 'public.inventory', 'DELETE') THEN 'DELETE' END),
           '(none)'

    UNION ALL
    SELECT 'reviews: product_reviews table exists',
           CASE WHEN to_regclass('public.product_reviews') IS NULL THEN 'MISSING' ELSE 'PASS' END,
           COALESCE(to_regclass('public.product_reviews')::text, '(missing)'),
           'public.product_reviews'

    UNION ALL
    SELECT 'reviews: completed-order insert policy installed',
           CASE WHEN EXISTS (
                    SELECT 1 FROM pg_policies
                     WHERE schemaname = 'public' AND tablename = 'product_reviews'
                       AND policyname = 'reviews_insert_buyer')
                THEN 'PASS' ELSE 'FAIL' END,
           COALESCE((SELECT string_agg(policyname, ', ' ORDER BY policyname)
                       FROM pg_policies
                      WHERE schemaname = 'public' AND tablename = 'product_reviews'), '(none)'),
           'reviews_insert_buyer present'

    UNION ALL
    SELECT 'reviews: column guard trigger installed',
           CASE WHEN EXISTS (
                    SELECT 1 FROM pg_trigger
                     WHERE tgname = 'trg_protect_review_columns' AND NOT tgisinternal)
                THEN 'PASS' ELSE 'FAIL' END,
           (SELECT COALESCE(tgenabled::text, '(missing)') FROM pg_trigger
             WHERE tgname = 'trg_protect_review_columns' AND NOT tgisinternal),
           'trigger enabled'

    UNION ALL
    SELECT 'view: my_order_codes exists and is granted',
           CASE WHEN to_regclass('public.my_order_codes') IS NULL THEN 'MISSING'
                WHEN NOT has_table_privilege('authenticated', 'public.my_order_codes', 'SELECT') THEN 'FAIL'
                ELSE 'PASS' END,
           CASE WHEN to_regclass('public.my_order_codes') IS NULL THEN '(missing)'
                ELSE has_table_privilege('authenticated', 'public.my_order_codes', 'SELECT')::text END,
           'true'

    UNION ALL
    SELECT 'view: my_order_codes exposes handoff_otp',
           CASE WHEN EXISTS (
                    SELECT 1 FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'my_order_codes'
                       AND column_name = 'handoff_otp')
                THEN 'PASS' ELSE 'FAIL' END,
           (SELECT COALESCE(data_type, '(missing)') FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = 'my_order_codes'
               AND column_name = 'handoff_otp'),
           'character varying'

    UNION ALL
    SELECT 'rpc: anon cannot delete accounts',
           CASE WHEN to_regprocedure('public.delete_user_account()') IS NULL THEN 'MISSING'
                WHEN has_function_privilege('anon', 'public.delete_user_account()', 'EXECUTE') THEN 'FAIL'
                ELSE 'PASS' END,
           CASE WHEN to_regprocedure('public.delete_user_account()') IS NULL THEN '(missing)'
                ELSE has_function_privilege('anon', 'public.delete_user_account()', 'EXECUTE')::text END,
           'false'

    UNION ALL
    SELECT 'rpc: anon cannot place orders',
           CASE WHEN to_regprocedure('public.place_orders_atomic(jsonb,text)') IS NULL THEN 'MISSING'
                WHEN has_function_privilege('anon', 'public.place_orders_atomic(jsonb,text)', 'EXECUTE') THEN 'FAIL'
                ELSE 'PASS' END,
           CASE WHEN to_regprocedure('public.place_orders_atomic(jsonb,text)') IS NULL THEN '(missing)'
                ELSE has_function_privilege('anon', 'public.place_orders_atomic(jsonb,text)', 'EXECUTE')::text END,
           'false'

    UNION ALL
    SELECT 'rpc: authenticated can place orders',
           CASE WHEN to_regprocedure('public.place_orders_atomic(jsonb,text)') IS NULL THEN 'MISSING'
                WHEN NOT has_function_privilege('authenticated', 'public.place_orders_atomic(jsonb,text)', 'EXECUTE') THEN 'FAIL'
                ELSE 'PASS' END,
           CASE WHEN to_regprocedure('public.place_orders_atomic(jsonb,text)') IS NULL THEN '(missing)'
                ELSE has_function_privilege('authenticated', 'public.place_orders_atomic(jsonb,text)', 'EXECUTE')::text END,
           'true'

    UNION ALL
    SELECT 'rpc: lifecycle functions are not callable by anon',
           CASE WHEN to_regprocedure('public.verify_handoff_as_seller(uuid,text)') IS NULL THEN 'MISSING'
                WHEN has_function_privilege('anon', 'public.verify_handoff_as_seller(uuid,text)', 'EXECUTE')
                  OR has_function_privilege('anon', 'public.schedule_meetup_as_seller(uuid,text,text,text)', 'EXECUTE')
                  OR has_function_privilege('anon', 'public.confirm_receipt_as_buyer(uuid)', 'EXECUTE')
                THEN 'FAIL' ELSE 'PASS' END,
           'see function ACLs',
           'false'

    UNION ALL
    SELECT 'realtime: orders is not published',
           CASE WHEN EXISTS (
                    SELECT 1 FROM pg_publication_tables
                     WHERE pubname = 'supabase_realtime'
                       AND schemaname = 'public' AND tablename = 'orders')
                THEN 'FAIL' ELSE 'PASS' END,
           CASE WHEN EXISTS (
                    SELECT 1 FROM pg_publication_tables
                     WHERE pubname = 'supabase_realtime'
                       AND schemaname = 'public' AND tablename = 'orders')
                THEN 'published' ELSE 'not published' END,
           'not published'
)
SELECT check_name, result, observed, expected
  FROM checks
 ORDER BY CASE result WHEN 'FAIL' THEN 0 WHEN 'MISSING' THEN 1 ELSE 2 END, check_name;

-- ----------------------------------------------------------------------------
-- Detail dump (run separately when a check above fails)
-- ----------------------------------------------------------------------------
-- SELECT policyname, cmd, roles, qual, with_check
--   FROM pg_policies
--  WHERE schemaname = 'public' AND tablename IN ('orders', 'inventory', 'product_reviews')
--  ORDER BY tablename, policyname;
--
-- SELECT grantee, privilege_type, column_name
--   FROM information_schema.column_privileges
--  WHERE table_schema = 'public' AND table_name = 'orders'
--    AND grantee IN ('anon', 'authenticated')
--  ORDER BY grantee, privilege_type, column_name;
