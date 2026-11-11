-- ============================================================================
-- Supabase pg_cron: release expired stock reservations inside the database
-- ============================================================================
-- WHY: the Spring backend on Render's free plan sleeps after ~15 min without
-- traffic. While it sleeps, OrderTimeoutService.sweepExpiredReservations()
-- does not run and reserved stock is never released. pg_cron runs inside
-- Supabase, so expiry keeps working even when Render is asleep. The Spring
-- sweep stays as a backup; release_expired_reservations() is idempotent, so
-- both running is safe.
--
-- HOW TO APPLY (once, manually; NOT applied by DatabaseMigrationRunner):
--   1. Supabase Dashboard -> Database -> Extensions -> enable "pg_cron".
--   2. Supabase Dashboard -> SQL Editor -> paste this file -> Run.
--   3. Verify with the queries at the bottom.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Re-running this file replaces the job instead of duplicating it.
DO $$
BEGIN
    PERFORM cron.unschedule(jobid)
       FROM cron.job
      WHERE jobname = 'release-expired-reservations';
END $$;

SELECT cron.schedule(
    'release-expired-reservations',
    '*/5 * * * *',
    $$ SELECT public.release_expired_reservations(); $$
);

-- ----------------------------------------------------------------------------
-- Verify
-- ----------------------------------------------------------------------------
-- Job is registered:
--   SELECT jobid, jobname, schedule, active FROM cron.job;
-- Recent runs (status should be 'succeeded'):
--   SELECT jobid, status, return_message, start_time
--     FROM cron.job_run_details ORDER BY start_time DESC LIMIT 10;
