-- Resumability ledger for `cluetooth backfill`; dropped together with the
-- *_old tables once the owner approves.
create table if not exists backfill_progress (
  phase text primary key,
  through_id bigint not null default 0,
  rows bigint not null default 0,
  done boolean not null default false,
  updated_at timestamptz not null default now()
)
