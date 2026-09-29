# Database

`db/db/migrations/` contains schema migrations.

`db/test/compose.yaml` defines a local test-only Postgres/PostGIS instance for running and validating migrations.

From the repository root, run `mise install` to install pinned dbmate, then:

- `mise run //db:testdb-up` (requires Docker and Compose)
- `mise run //db:testdb-wait` (one readiness check; retry manually if not ready)
- `mise run //db:testdb-migrate`
- `mise run //db:testdb-down` (preserves data)

These commands target only the local test database on port 55432. No automated
volume-removal task is provided.
