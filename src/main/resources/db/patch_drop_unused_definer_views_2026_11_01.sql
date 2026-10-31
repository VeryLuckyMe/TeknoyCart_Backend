-- ============================================================================
-- TeknoyCart: drop two unused SECURITY DEFINER views
-- Apply manually in the Supabase SQL editor. Safe to re-run.
-- ============================================================================
--
-- Supabase Security Advisor reports five `security_definer_view` findings. Two
-- of them point at views that nothing reads:
--
--   public.my_store_profile
--   public.public_store_profiles
--
-- No caller exists. A grep for `.from('my_store_profile')` and
-- `.from('public_store_profiles')` across TeknoyCart (Flutter), TeknoyCartWeb
-- (admin React) and TeknoyCart_Backend (Java) returns zero hits. Both names
-- appear only in documentation and in the W1 diagnostics. The app reads
-- `public.store_profiles` directly, using the safe column-level SELECT grants.
--
-- Coverage check before dropping:
--   * `my_store_profile` was the only path to an owner's own `contact_number`.
--     `contact_number` is written by `auth_service.dart` and
--     `profile_tab.dart` via upsert, but never read back by any screen, so
--     removing the view loses no feature.
--   * `public_store_profiles` was a storefront projection of
--     `store_profiles` + `users`. The storefront reads `store_profiles`
--     (`store_name`, ...) and `public_profiles` (`full_name`,
--     `is_seller_verified`) directly instead.
--
-- Neither view is created by `DatabaseMigrationRunner`, so the drop survives
-- backend restarts. `migration_auth_hardening_2026_10_24.sql` still contains
-- their original CREATE statements; if that migration is ever re-applied to a
-- fresh project, run this patch afterwards.
--
-- The remaining three advisories (`my_profile`, `admin_kyc_queue`,
-- `my_order_codes`) must stay SECURITY DEFINER and are NOT touched here.
-- `public.users` uses `USING (true)` for authenticated, so RLS filters no rows
-- and column grants are the only boundary. `security_invoker = true` would
-- force granting `email`, `student_id`, `contact` and `gcash_number` to every
-- signed-in student; `my_order_codes` would expose buyer handoff OTPs to
-- sellers through `orders_select_parties`.
-- ============================================================================

BEGIN;

DROP VIEW IF EXISTS public.my_store_profile;
DROP VIEW IF EXISTS public.public_store_profiles;

COMMIT;
