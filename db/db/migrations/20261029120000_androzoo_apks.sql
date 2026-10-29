-- migrate:up
-- The AndroZoo APK list (https://androzoo.uni.lu/lists, latest.csv.gz), one row per APK, so agents
-- query it instead of reading the multi-GB CSV. Loaded and replaced as a whole by the androzoo-sync
-- job (infra/data/androzoo): COPY into a staging table, then a swap inside one transaction.
-- Idempotent, so it can be applied by hand to a running database first and by dbmate later.
create schema if not exists androzoo;

create table if not exists androzoo.apks (
  sha256 text primary key,                -- uppercase hex, as in the list
  sha1 text,
  md5 text,
  dex_date timestamp,                     -- the classes.dex timestamp (often 1980-01-01 or wrong)
  apk_size bigint,
  pkg_name text,
  vercode bigint,
  vt_detection integer,                   -- VirusTotal positives; null when not scanned
  vt_scan_date timestamp,
  dex_size bigint,
  markets text,                           -- '|'-separated, e.g. play.google.com|anzhi
  added timestamp                         -- only from the with-added-date list; null otherwise
);
create index if not exists apks_pkg_name_idx on androzoo.apks (pkg_name);
create index if not exists apks_pkg_name_vercode_idx on androzoo.apks (pkg_name, vercode);

-- What the current table was loaded from; the daily check compares upstream against it.
create table if not exists androzoo.sync_state (
  id boolean primary key default true check (id),
  source text not null,                   -- URL, or the file of a one-off load
  etag text,
  last_modified text,
  content_length bigint,
  row_count bigint not null,
  loaded_at timestamptz not null default now()
);

-- Agents read through androzoo_ro (its login password is set outside migrations). The sync job
-- grants SELECT again on every table it swaps in.
do $$
begin
  if not exists (select from pg_roles where rolname = 'androzoo_ro') then
    create role androzoo_ro nologin;
  end if;
end $$;
grant usage on schema androzoo to androzoo_ro;
grant select on androzoo.apks, androzoo.sync_state to androzoo_ro;

-- migrate:down
drop schema androzoo cascade;
