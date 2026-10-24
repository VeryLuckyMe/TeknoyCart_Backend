# Auth Hardening (W1) - Apply & Verify

Date: 2026-10-24  
Project: `chmtvasbhkrvydbajnd` (demo-only)  
Branch: `fix/auth-hardening`  
Status: applied on 2026-10-24; verification **147/147 PASS**, `ZZ_SUMMARY` 0

This is a forward-only, manual Supabase migration. The SQL editor runs as
`postgres` and bypasses RLS, so catalog checks are necessary but not sufficient;
run the live escalation proof below with a real user or
`SET LOCAL ROLE authenticated` plus JWT claims.

## Preconditions (W0 exit gate)

- W0 export of `users`, `products`, `orders` is stored outside the repo.
- The four demo accounts are confirmed: buyer, approved seller, pending seller,
  current admin. The old `capstone.team45` admin was deleted; use one of the two
  live ADMIN rows.
- Supabase Auth minimum password length is set to **12**.
- Supabase Auth **Confirm email** is **disabled**. The mobile app uses the
  backend SMTP verification flow and requires `signUp()` to return a session;
  otherwise it fails with an explicit error before writing `public.users`.
- `store_profiles.seller_id` has a unique constraint. The app uses
  `upsert(..., onConflict: 'seller_id')`; without the constraint PostgREST
  rejects the upsert. The live table is expected to have it from
  `supabase_schema.sql`; confirm with the preflight below.
- App/web W1 commits are ready to deploy with this SQL.
- No writes during the migration window.

### Read-only preflight

Run this in the SQL editor and save the output with the W0 export:

```sql
-- 1. Unique seller_id for the app's store-profile upsert
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public' AND tablename = 'store_profiles';

-- 2. Store-profile columns the app/web read
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'store_profiles'
ORDER BY ordinal_position;

-- 3. KYC columns used by admin_kyc_queue
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'seller_verifications'
ORDER BY ordinal_position;

-- 4. Ledger state (V9 must be absent before apply)
SELECT version, applied_at
FROM public.schema_migrations
ORDER BY version;
```

If `store_profiles.seller_id` is not unique, stop and fix the app upsert
before applying W1 (use a select-then-insert/update path instead).

### Demo-account confirmation

The old `capstone.team45` admin was deleted. Pick the current admin from the two
live ADMIN rows and confirm the buyer, approved seller, and pending seller
credentials. Do not put passwords in the repo or in chat.

```sql
-- Admin candidates (both live admins are verified/unlocked in W0)
SELECT user_id, email, role, is_verified, is_seller_verified, is_locked
FROM public.users
WHERE role = 'ADMIN'
ORDER BY email;

-- Approved-seller candidates (role SELLER + is_seller_verified = true)
SELECT user_id, email, role, is_verified, is_seller_verified, is_locked
FROM public.users
WHERE role = 'SELLER' AND is_seller_verified = true
ORDER BY email;

-- Pending-seller candidates (role SELLER + is_seller_verified = false)
SELECT user_id, email, role, is_verified, is_seller_verified, is_locked
FROM public.users
WHERE role = 'SELLER' AND is_seller_verified = false
ORDER BY email;

-- Buyer candidates
SELECT user_id, email, role, is_verified, is_seller_verified, is_locked
FROM public.users
WHERE role = 'BUYER'
ORDER BY email;
```

### Data export

Use Supabase Dashboard -> Table Editor -> Export CSV for `users`, `products`,
and `orders`, or `COPY ... TO STDOUT` from a trusted client. Store the three CSVs
outside this repository. The W0 CSV in `C:\Users\Mikel\Downloads\W0.csv` is the
schema/count diagnostic, not the data backup.

## Apply

1. Review:
   - `migration_auth_hardening_2026_10_24.sql`
   - `rollback_auth_hardening_2026_10_24.sql`
2. In the Supabase SQL editor, run the entire migration (it is wrapped in
   `BEGIN`/`COMMIT`). Save the editor output.
3. Run `diagnostics/auth_w1_verify_2026_10_24.sql`. It is read-only. It must
   return **0 failures**. The script has 147 checks after the W0 review. The
   pre-migration baseline is **98 failures**; the other 49 checks are already
   green (RLS, publication, existing policies) and must stay green.
4. **Known repair for an earlier draft:** if only `V02`, `V21`, and `V71` fail
   (plus the `ZZ_SUMMARY` row; actual `true` for `anon` SELECT on `my_profile`,
   `admin_kyc_queue`, and `my_store_profile`), run
   `patch_auth_hardening_view_grants_2026_10_24.sql`, then rerun the
   verification. This is Supabase's default `anon` grant on newly created
   views, not a data or RLS failure. Do not roll back for this case. The current
   migration includes the explicit revokes, so a fresh apply does not need the
   patch.
5. For any other failure, run the rollback in a transaction, then restore the
   W0 export if data was affected. Do not leave a half-applied migration.
6. After GREEN, commit the migration and run the live proof and two-device
   Realtime smoke test.

## Ledger

The migration inserts exactly one row into the existing ledger:

```sql
INSERT INTO public.schema_migrations (version, applied_at)
VALUES ('V9__auth_hardening', now())
ON CONFLICT (version) DO NOTHING;
```

Do not create a second ledger.

## What the migration changes

- `protect_user_security_columns` and `enforce_default_role_on_insert` become
  `SECURITY INVOKER` and keep the `current_user`/`session_user`/`service_role`
  guard. The previous `SECURITY DEFINER` + guard was the C1 escalation.
- Signup clients can insert only `user_id, full_name, role, student_id,
  seller_type`; the trigger owns JWT email, password hash, verification flags,
  token fields, and lockout state. `ADMIN` registration is rejected.
- `users` loses table-level grants. `authenticated` gets public-column `SELECT`
  (`user_id, full_name, role, is_seller_verified, last_seen_at`), safe signup
  `INSERT`, and own-row `UPDATE` (`full_name, contact, gcash_number,
  last_seen_at`). `anon` gets no base-table grant.
- `my_profile` exposes own PII to authenticated users only.
  `public_profiles` exposes only public fields to anon/authenticated.
  `admin_kyc_queue` is gated by an active, verified, unlocked ADMIN.
- The authenticated-only views explicitly revoke the inherited `anon`/`PUBLIC`
  grant that Supabase applies to new public views; `public_profiles` and
  `public_store_profiles` keep their intentional `anon` grant.
- `store_profiles` loses table-level grants but keeps safe column `SELECT`
  (including `usual_active_hours`, `preferred_meetup_spots`,
  `welcome_message_template`) and own-row `INSERT`/`UPDATE` including
  `contact_number` and `seller_id` (needed by
  `upsert(..., onConflict: 'seller_id')`). `contact_number` is never exposed to
  anon/authenticated through the base table; `my_store_profile` is the owner
  view.
- `public_store_profiles` is the public storefront view.
- `is_student_id_available(text)` is a `SECURITY DEFINER` boolean RPC granted to
  `anon, authenticated`; it replaces the anon `users.student_id` pre-check.
- The legacy `seller_verifications` policy `Admins can manage all verifications`
  (`ALL`, `public`, `USING (true)`) is dropped. The admin policy with the
  ADMIN/service_role predicate remains, and the user PENDING/REJECTED policies
  remain.
- `products_insert_seller` and `products_update_seller` require
  `auth.uid() = seller_id` **and** an existing
  `role = 'SELLER' AND is_seller_verified = true` row, in both `USING` and
  `WITH CHECK` where applicable.
- RLS is re-enabled idempotently on `users`, `chats`, `messages`, `inquiries`.
  The Realtime publication is **not** touched: it already contains
  `chats`/`messages` and excludes `users`.

Do **not** re-run `comprehensive_rls_policies.sql`, `enable_realtime.sql`, or
the P6 KYC migration. They are already applied; the archived files are
historical.

## App/web coupling (must ship together)

- `auth_service.dart`: signup pre-check uses `is_student_id_available`;
  signup inserts safe columns with `ignoreDuplicates`; store-profile upsert uses
  `onConflict: 'seller_id'`; `getEnrichedProfile` reads `my_profile`.
- `email_verification_dialog.dart`: polls `my_profile.is_verified`.
- `order_history_view.dart`: counterparty names from `public_profiles`; contact
  is intentionally absent until the post-demo RPC.
- `reserved_orders_sheet.dart`: buyer name from `public_profiles`; no email.
- `seller_storefront_view.dart`, `inbox_view.dart`: `public_profiles`.
- `presence_service.dart`: reads `public_profiles.last_seen_at`; writes own
  `users.last_seen_at` (granted).
- `matchingStoresProvider`: uses `is_seller_verified`, not `is_verified`.
- `chat_service.dart`: no longer reads counterparty `users.gcash_number`; the
  assistant omits the payment hint until the post-demo counterparty RPC.
- `profile_tab.dart`: store-profile upsert uses `onConflict: 'seller_id'`.
- Web `Login.jsx`: reads `my_profile`, requires ADMIN + `is_verified`, and signs
  out non-admin/unverified sessions.
- Web `App.jsx`: boot revalidation reads `my_profile` and requires ADMIN +
  `is_verified`; the presence heartbeat only writes `last_seen_at`.
- Web `adminAccess.js`: one pure verified-admin predicate shared by login and
  boot, covered by `adminAccess.test.js`.
- Web `Moderation.jsx`: reads `admin_kyc_queue` for email/student_id.
- Web `Dashboard.jsx` / `Inventory.jsx` only read granted `users` columns; the
  admin product-add path is intentionally outside the demo story because the
  W1 product policy requires an approved seller as the row owner.\n- Backend `/auth/register` rejects passwords shorter than 12 characters. The\n  endpoint is legacy and slated for removal in W3; this keeps the policy\n  server-side in the meantime.
- Password minimum is 12 in signup, reset sheets, and the Supabase dashboard.
- Registration has an explicit consent checkbox that opens the shared
  Terms/Privacy sheet. Consent persistence is post-demo.

## Live escalation proof (after GREEN)

Use a real test user through PostgREST, or in the SQL editor:

```sql
BEGIN;
SELECT set_config('request.jwt.claim.sub', '<test-user-uuid>', true);
SELECT set_config('request.jwt.claim.role', 'authenticated', true);
SELECT set_config('request.jwt.claims',
  '{"sub":"<test-user-uuid>","role":"authenticated","email":"<test-email>"}', true);
SET LOCAL ROLE authenticated;
-- run the checks
ROLLBACK;
```

Verify at minimum:

- `SELECT * FROM public.users` fails with a column-permission error; a
  `SELECT user_id, full_name, role, is_seller_verified, last_seen_at` succeeds.
  `email`, `student_id`, `contact`, `gcash_number`, `password_hash`,
  `is_verified`, `is_locked`, `verification_token` are denied.
- `SELECT * FROM public.my_profile` returns exactly the caller's row.
- `SELECT * FROM public.public_profiles` returns only public fields.
- `SELECT contact_number FROM public.store_profiles` is denied; the owner's
  `my_store_profile` returns the own row; `public_store_profiles` has no contact.
- A non-admin gets 0 rows from `admin_kyc_queue`.
- A buyer / pending seller cannot insert a product; an approved seller can.
- A user cannot update `users.role` / `is_verified` / `is_seller_verified` /
  `password_hash` / `is_locked`; the backend (JDBC) and `admin_verify_seller`
  still can.
- A user cannot set their own `seller_verifications.status` to `APPROVED`; the
  admin RPC can approve.
- `is_student_id_available` returns a boolean and does not expose another row.

Then run the two-device Realtime smoke test (buyer + seller chat + presence).
A broken subscription blocks the migration.

## Rollback

Run `rollback_auth_hardening_2026_10_24.sql` inside a transaction. It restores
the pre-W1 trigger bodies, broad grants, product policies, and the legacy KYC
policy, and removes the V9 ledger row. The app/web W1 commits must be rolled
back too. Restore the W0 export if data was affected. RLS stays enabled; only
use an "RLS off" emergency state with a written risk acceptance.
