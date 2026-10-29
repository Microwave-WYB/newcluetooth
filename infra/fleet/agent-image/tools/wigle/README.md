# wigle

CLI for the WiGLE search API that archives **every call** in Postgres (`wigle.responses`). One ES module
(`wigle.mjs`) plus `pg`; wrapper `bin/wigle`.

It is an archive, not a cache. The tool always calls the API when asked, stores every outcome (success, HTTP
error, 429, network failure) as a row, never retries, and never skips a call because of the archive. The root
agent schedules and budgets. When the same params plus cursor were archived before, it prints a one-line notice
to stderr (id and date) and calls anyway.

## Commands

```
wigle search <endpoint> key=value ... [--page-after <cursor>] [--caller <label>]
wigle pages  <endpoint> key=value ... --max-pages N [--page-after <cursor>] [--caller <label>]
wigle quota
wigle import-collector <run-dir>
```

Endpoints: `bluetooth/search`, `network/search` (Wi-Fi), `cell/search`; the `/api/v2/` prefix is optional.
`resultsPerPage` is always forced to the endpoint maximum (any caller value is dropped). The maxima live in one
table, `MAX_RESULTS_PER_PAGE`: 100 for all three, from WiGLE's swagger ("Bounded at 1000 for COMMAPI, 100 for
site"; API-token accounts are site accounts).

`search` prints one JSON line: `id, http_status, total, results_count, next_search_after, done` (plus `error`
when the call failed). `pages` follows `searchAfter` until it is null or N pages, prints one line per page and a
final `{"summary":true,...}` line, and stops at the first error.

Exit codes: 0 ok, 1 usage, 2 HTTP/API/network failure, 3 daily limit (HTTP 429, or a 200 body with
`success:false` mentioning "too many", "daily limit" or "throttl", as the old collector detected it).

## What a row holds

- `params`: the query without the cursor, all values strings, canonical sorted-key JSON (includes the forced
  `resultsPerPage`); `params_hash` is its sha256. `search_after` is the cursor sent (as `searchAfter`).
- `total` is `totalResults` of that page; `next_search_after` is the body's `searchAfter` (null ends the query).
- `error` is prefixed: `http 429: ...`, `http 502: ...`, `daily limit: ...`, `api: ...`, `network: ...`.
  `http_status` is null when no response arrived. `response` is the body when it was JSON; jsonb cannot hold U+0000, so each NUL in a string is stored as the six
  characters `\u0000`.

## quota

Derived only from the table. The day is the Pacific day, reset at 00:05 America/Los_Angeles. Prints JSON:
`used_today` (successful calls), `rate_limited_today`, `last_rate_limited_at`, `estimated_daily_limit` (successful
calls on the most recent day that hit a limit), `estimated_daily_limit_max_14d`, and `remaining`
(`estimated - used`, or `"unknown"`).

## import-collector

Imports the request archive of the old cron collector (`<run-dir>/requests/*/{metadata.json,response.raw}`) with
`caller='cron:us-osm-hospitals'` and the original `started_utc` as `called_at`. Idempotent: a row with the same
`params_hash`, `search_after` and `called_at` is skipped. Prints `imported, skipped, errors_kept, unreadable`.
Collector params are normalized to strings, so hashes match live calls with the same query.

## Configuration

- DSN: `WIGLE_DB_URL` or `~/.config/wigle/db-url` (role `wigle_writer`: insert and select).
- API key: `WIGLE_API_KEY` or `~/.config/wigle/api-key` (the base64 "Encoded for use" value, sent as
  `Authorization: Basic ...`). Neither is ever printed.
- `RESEARCH_AGENT_HOME` locates `tools/node_modules/pg`, as for `wiki-sql`. `WIGLE_BASE_URL` overrides the API
  host (tests). `WIGLE_CALLER` sets the default `--caller` (default `cli`).

## Tests

`node --test tools/wigle/test/*.test.mjs` (no network). A fake WiGLE server covers paging, 429, payload limits,
network failure and the forced page size; the store is tested with an in-memory fake. One integration test starts
a throwaway `postgres:17-alpine` container and applies the migration SQL (`WIGLE_MIGRATION_SQL` overrides its
path); it is skipped without docker, `pg` or the migration file.
