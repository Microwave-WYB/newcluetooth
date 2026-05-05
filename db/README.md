# Database

`db/db/migrations/` contains schema migrations.

`db/test/compose.yaml` defines a local test-only Postgres/PostGIS instance for running and validating migrations.

Use:

- `make testdb-up`
- `make testdb-migrate`
- `make testdb-down`
