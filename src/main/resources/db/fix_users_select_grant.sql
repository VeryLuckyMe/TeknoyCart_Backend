-- ====================================================================
-- TeknoyCart: Fix Table Permissions for public.users
-- Platform: Supabase / PostgreSQL
-- Resolves: PostgrestException 42501 (insufficient privilege on public.users)
-- ====================================================================

-- 1. Grant SELECT privilege to authenticated and anon roles
GRANT USAGE ON SCHEMA public TO anon, authenticated;
GRANT SELECT ON TABLE public.users TO anon, authenticated;

-- 2. Ensure authenticated users can select, insert, and update their profile
GRANT SELECT, INSERT, UPDATE ON TABLE public.users TO authenticated;

-- 3. Confirm RLS policy allows authenticated users to read profiles
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies 
        WHERE tablename = 'users' AND policyname = 'Allow authenticated users to read profiles'
    ) THEN
        CREATE POLICY "Allow authenticated users to read profiles"
            ON public.users FOR SELECT
            TO authenticated
            USING (true);
    END IF;
END $$;
