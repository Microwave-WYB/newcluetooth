-- migrate:up
-- Archive of every WiGLE API call made by the research tooling (the `wigle` CLI), successes and errors alike.
-- Not a cache: callers consult it to avoid re-querying, and quota use is derived from it (calls per day, 429s).
create schema wigle;

create table wigle.responses (
  id bigint generated always as identity primary key,
  endpoint text not null,                 -- e.g. /api/v2/bluetooth/search
  params jsonb not null,                  -- the query as sent, without the cursor
  params_hash text not null,              -- sha256 of the canonical params JSON
  search_after text,                      -- cursor sent with this page (null: first page)
  next_search_after text,                 -- cursor returned (null: no more pages)
  total bigint,                           -- total the API reported on this page (it can change between pages)
  http_status integer,                    -- null when the request never got a response
  error text,                             -- 429 body, network error, ...; null on success
  caller text,                            -- self-reported label (root, worker#31, y2wei, cron)
  called_at timestamptz not null default now(),
  response jsonb                          -- raw response body when it was JSON
);

create index responses_params_page_idx on wigle.responses (params_hash, search_after);
create index responses_called_at_idx on wigle.responses (called_at);
create index responses_params_gin_idx on wigle.responses using gin (params jsonb_path_ops);

-- Only the wigle CLI writes (role wigle_writer; its login password is set outside migrations).
do $$
begin
  if not exists (select from pg_roles where rolname = 'wigle_writer') then
    create role wigle_writer nologin;
  end if;
end $$;
grant usage on schema wigle to wigle_writer;
grant select, insert on wigle.responses to wigle_writer;

-- Research agents read through wiki_ro when it exists.
do $$
begin
  if exists (select from pg_roles where rolname = 'wiki_ro') then
    grant usage on schema wigle to wiki_ro;
    grant select on wigle.responses to wiki_ro;
  end if;
end $$;

-- migrate:down
drop schema wigle cascade;
