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
- Backend `JWT_SECRET` is configured with at least 32 bytes and is persisted
  across deploys. Backend startup now fails when it is missing or too short;
  do not paste the value into this repository or chat.
- Supabase Auth redirect allowlist includes `teknoycart://login-callback` for
  mobile password recovery. Confirm the web recovery redirect separately if
  the web client is enabled.
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
   return **0 failures**. The script has 138 checks after the W0 review. The
   pre-migration baseline is **89 failures**; the other 49 checks are already
   green (RLS, publication, existing policies) and must stay green.
4. **Known repair for an earlier draft:** if only `V02` and `V21` fail
   (plus the `ZZ_SUMMARY` row; actual `true` for `anon` SELECT on `my_profile`
   and `admin_kyc_queue`), run
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
  `public_profiles` exposes only public fields to authenticated users (the
  2026-10-31 hardening revoked `anon`).
  `admin_kyc_queue` is gated by an active, verified, unlocked ADMIN.
- The authenticated-only views explicitly revoke the inherited `anon`/`PUBLIC`
  grant that Supabase applies to new public views.
- `store_profiles` loses table-level grants but keeps safe column `SELECT`
  (including `usual_active_hours`, `preferred_meetup_spots`,
  `welcome_message_template`) and own-row `INSERT`/`UPDATE` including
  `contact_number` and `seller_id` (needed by
  `upsert(..., onConflict: 'seller_id')`). `contact_number` is never exposed to
  anon/authenticated through the base table.
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
  W1 product policy requires an approved seller as the row owner.
- Backend `/auth/register` rejects passwords shorter than 12 characters. The
  endpoint is legacy and slated for removal in W3; this keeps the policy
  server-side in the meantime.
- Password minimum is 12 in signup, reset sheets, and the Supabase dashboard.
- Registration has an explicit consent checkbox that opens the shared
  Terms/Privacy sheet. Consent persistence is post-demo.

## Live escalation proof (after GREEN)

Run `scripts/auth_w1_live_proof.ps1` from the backend root with real Supabase
Auth sessions. It uses PostgREST, so `session_user` is the PostgREST
authenticator and the C1 trigger guard is exercised.

Do **not** use `SET LOCAL ROLE authenticated` in the SQL editor for the
trigger-guard checks: the SQL editor's `session_user` remains `postgres`, which
makes the guard return early. The SQL editor is still correct for the catalog
verification.

Set the non-secret values, then run the script; it prompts for any missing
passwords and never prints them:

```powershell
$env:SUPABASE_URL = "https://chmtvasbhkrvydbajnd.supabase.co"
$env:SUPABASE_ANON_KEY = "<anon-key>"
$env:W1_BUYER_EMAIL = "liam@cit.edu"
$env:W1_SELLER_EMAIL = "harveyrodchristian.valmera@cit.edu"
$env:W1_PENDING_EMAIL = "clark.kent@cit.edu"
# Optional: $env:W1_ADMIN_EMAIL = "admin@cit.edu"
.\scripts\auth_w1_live_proof.ps1
```

The script checks:

- buyer `users.email` denied; safe `users` columns and `my_profile` allowed;
- buyer `PATCH role=ADMIN` and `POST role=ADMIN` denied, role unchanged;
- anon `users.email` denied; `public_profiles` denied;
- anon `my_profile` and `admin_kyc_queue` denied; `public_store_profiles` and
  `my_store_profile` are dropped, so PostgREST answers `404`;
- buyer `store_profiles.contact_number` denied;
- buyer/pending seller product insert denied; approved seller insert allowed;
- buyer gets 0 `admin_kyc_queue` rows; admin gets the queue;
- pending seller cannot set own `seller_verifications.status` to `APPROVED`.

Every row must read PASS. The script creates a temporary seller product and
deletes it. If a denied write unexpectedly succeeds, the result prints the row
id to delete via the SQL editor. Do not put passwords in the repo or in chat.

Then run the two-device Realtime smoke test (buyer + seller chat + presence).
A broken subscription blocks the migration.

## 2026-10-31 follow-up: public_profiles and store_profiles

Supabase Security Advisor flagged `public.public_profiles` for
`security_definer_view` and `auth_users_exposed`. The view joined `auth.users`
for avatar metadata and was readable by `anon`, so every user's name, seller
flag and `last_seen_at` could be enumerated without signing in.

Apply:

- `db/patch_public_profiles_hardening_2026_10_31.sql`

It rebuilds the view with `security_invoker = true`, removes the `auth.users`
join, keeps `avatar_url` as `NULL` for column compatibility, and revokes
`anon`/`PUBLIC`. All current `public_profiles` reads happen after sign-in:
order history, inbox, storefront and presence. No app code change is required.

The same patch repairs `store_profiles`. `DatabaseMigrationRunner` used to run
`GRANT SELECT ON TABLE public.store_profiles TO anon, authenticated` on every
boot, which overrode W1's column-level grants and exposed `contact_number`.
The runner now only recreates the public storefront policy; the patch revokes
the table-level grant and restores the safe-column grants. The Java change must
be deployed with the next backend deploy, otherwise the next boot will
re-introduce the table-level grant. If the service boots with the old runner,
re-run the patch (or at least its `store_profiles` section) after the new
deploy.

Verify with:

- `db/diagnostics/verify_public_profiles_hardening_2026_10_31.sql` - every row
  must be `PASS`.
- `db/diagnostics/auth_w1_verify_2026_10_24.sql` - V11 and V13 now expect the
  hardened state (anon denied, `security_invoker = true`); the other checks are
  unchanged.
- `scripts/auth_w1_live_proof.ps1` - R12 now expects anon to be denied and
  R12b proves a signed-in buyer can still read the view.

## 2026-11-01 follow-up: unused definer views dropped

Two of the five `security_definer_view` advisories pointed at views with no
consumer:

- `public.my_store_profile`
- `public.public_store_profiles`

Grepping for `.from('my_store_profile')` and `.from('public_store_profiles')`
across `TeknoyCart` (Flutter), `TeknoyCartWeb` (admin React) and
`TeknoyCart_Backend` (Java) returns no call site. The app reads
`public.store_profiles` directly. `contact_number` is written by
`auth_service.dart` / `profile_tab.dart` on upsert but never read back, so
`my_store_profile` was not the owner view its name implied.

Apply:

- `db/patch_drop_unused_definer_views_2026_11_01.sql`

Neither view is created by `DatabaseMigrationRunner`, so the drop survives
restarts. If `migration_auth_hardening_2026_10_24.sql` is re-applied to a fresh
project, run the drop patch afterwards.

Updated expectations:

- `auth_w1_verify_2026_10_24.sql`: `V70` and `V71` now expect `false` (both
  views absent); `V72`-`V80` are retired.
- `verify_public_profiles_hardening_2026_10_31.sql`: check 22 asserts
  `public_store_profiles` is absent.
- `scripts/auth_w1_live_proof.ps1`: `R13` and `R16` expect a non-2xx response.

Three advisories remain and are intentional: `my_profile` (own PII),
`admin_kyc_queue` (verified admin queue) and `my_order_codes` (buyer OTPs).
They need definer semantics because `public.users` grants `SELECT ... USING
(true)` to `authenticated`, so RLS filters no rows and column grants are the
only boundary. Converting them to `security_invoker` would require granting
`email`, `student_id`, `contact` and `gcash_number` to every signed-in student,
and would expose buyer handoff OTPs to sellers through `orders_select_parties`.
Do not convert them without a broader grants/RLS redesign.

## 2026-11-01 follow-up: users grants restored to W1

`auth_w1_verify_2026_10_24.sql` reported 35 grant failures, all "expected
false, actual true". One fact caused all of them:

```sql
has_table_privilege('anon',          'public.users', 'SELECT')  -- true
has_table_privilege('authenticated', 'public.users', 'SELECT')  -- true
has_table_privilege('authenticated', 'public.users', 'INSERT')  -- true
has_table_privilege('authenticated', 'public.users', 'UPDATE')  -- true
```

A table-level grant implies every column, so G14-G23, G30-G32, G45-G52 and
G64-G73 all read true. The signature matches `fix_users_select_grant.sql`,
written to clear a PostgREST 42501. Because `users_select_authenticated` is
`FOR SELECT TO authenticated USING (true)`, that grant let any signed-in
student read every user's `email`, `student_id`, `contact`, `gcash_number`
and `password_hash`.

Apply:

- `db/patch_users_grants_restore_w1_2026_11_01.sql`

It reproduces section 2 of `migration_auth_hardening_2026_10_24.sql` exactly:
revoke table-level and residual column grants, then grant only

| privilege | columns |
| :--- | :--- |
| `SELECT` | `user_id, full_name, role, is_seller_verified, last_seen_at` |
| `INSERT` | `user_id, full_name, role, student_id, seller_type` |
| `UPDATE` | `full_name, contact, gcash_number, last_seen_at` |

`anon` keeps no base-table grant on `users`; public reads go through the
authenticated-only `public_profiles` view, and own PII goes through
`my_profile`.

`fix_users_select_grant.sql` is now a documented no-op. Do not restore its
table-level grants.

Verify with `auth_w1_verify_2026_10_24.sql`: 138 checks, `ZZ_SUMMARY` = 0.

## Rollback

Run `rollback_auth_hardening_2026_10_24.sql` inside a transaction. It restores
the pre-W1 trigger bodies, broad grants, product policies, and the legacy KYC
policy, and removes the V9 ledger row. The app/web W1 commits must be rolled
back too. Restore the W0 export if data was affected. RLS stays enabled; only
use an "RLS off" emergency state with a written risk acceptance.
