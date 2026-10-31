-- migrate:up
-- Failed blobs stay pending, but continuous sync defers each retry with
-- exponential backoff so one permanently bad object cannot be re-read every cycle.
-- Constant defaults on a small ledger table: metadata-only, no table rewrite.
alter table blobs
  add column attempts integer not null default 0,
  add column next_attempt_at timestamptz;

-- migrate:down
alter table blobs
  drop column if exists next_attempt_at,
  drop column if exists attempts;
