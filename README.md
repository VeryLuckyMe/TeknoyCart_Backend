# TeknoyCart Backend

Spring Boot REST API for the TeknoyCart campus marketplace, backed by Supabase Postgres.

## Run and test

```bash
mvn spring-boot:run   # serves on http://localhost:8080/api
mvn test
```

Health check: `GET /api/actuator/health` (this is also the uptime-monitor URL on Render).

## Database migrations

Versioned SQL files live in `src/main/resources/db/migration_vNN_*.sql`. They are
applied once on boot by `DatabaseMigrationRunner` and recorded in
`public.schema_migrations`. Add new tables through SQL, not JPA entities
(`ddl-auto: validate` runs before migrations).

## Payments

PayMongo (GCash) webhooks go to `POST /api/webhooks/paymongo`. A reconciliation
job re-checks unpaid orders every 10 minutes in case a webhook is missed.
Payments that cannot be applied are queued in `payment_exceptions` for the
admin portal.

## Backups

`.github/workflows/nightly-db-backup.yml` writes an encrypted `pg_dump` every
night at 02:00 PHT (secrets: `SUPABASE_DB_URL`, `BACKUP_PASSPHRASE`).
