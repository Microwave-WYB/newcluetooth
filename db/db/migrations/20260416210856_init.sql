-- migrate:up
create extension if not exists postgis;

create or replace function parse_ble_adv(raw bytea) returns jsonb as $$
declare
    bytes_len int;
    byte_offset int := 0;
    field_len int;
    field_type int;
    field_data bytea;
    result jsonb := '[]'::jsonb;
begin
    if raw is null then
        return result;
    end if;

    bytes_len := octet_length(raw);
    if bytes_len = 0 then
        return result;
    end if;

    while byte_offset < bytes_len loop
        field_len := get_byte(raw, byte_offset);

        if field_len = 0 then
            exit;
        end if;

        if byte_offset + field_len >= bytes_len + 1 then
            exit;
        end if;

        field_type := get_byte(raw, byte_offset + 1);
        field_data := substring(raw from byte_offset + 3 for field_len - 1);

        result := result || jsonb_build_array(
            jsonb_build_object(
                'type', field_type,
                'data', encode(field_data, 'hex')
            )
        );

        byte_offset := byte_offset + field_len + 1;
    end loop;

    return result;
end;
$$ language plpgsql immutable strict;

create table blobs (
  uri text primary key,
  uploader text,
  app_version text,
  meta jsonb,
  updated_at timestamptz,
  synced_at timestamptz,
  success boolean,
  error text
);

create table scans (
  id serial primary key,
  blob text references blobs (uri),
  -- device
  addr macaddr not null,
  rssi smallint,
  scanned_at timestamptz not null,
  -- advertisement
  raw bytea not null,
  local_name text,
  tx_power smallint,
  is_connectable boolean,
  -- geolocation
  lat float8,
  lon float8,
  accuracy float4,
  location geometry (Point, 4326)
);

create table gatt_discoveries (
  id serial primary key,
  blob text references blobs (uri),
  -- device
  addr macaddr not null,
  discovered_at timestamptz not null,
  raw_advertisement bytea,
  -- discovery result
  raw_profile jsonb not null,
  profile_hash text,
  -- geolocation
  lat float8,
  lon float8,
  accuracy float4,
  location geometry (Point, 4326)
);

create table gatt_attributes (
  discovery_id integer not null references gatt_discoveries (id) on delete cascade,
  attribute_id integer not null,
  parent_attribute_id integer,
  kind text not null,
  uuid text,
  data jsonb not null,
  primary key (discovery_id, attribute_id)
);

create or replace function set_location () returns trigger as $$
begin
    if new.lat is not null and new.lon is not null then
        new.location = st_setsrid(st_makepoint(new.lon, new.lat), 4326);
    end if;
    return new;
end;
$$ language plpgsql;

create trigger scans_set_location before insert
or
update on scans for each row
execute function set_location ();

create trigger gatt_discoveries_set_location before insert
or
update on gatt_discoveries for each row
execute function set_location ();

create index scans_location_idx on scans using gist (location);

create index scans_addr_idx on scans (addr);

create index scans_scanned_at_idx on scans (scanned_at);

create index gatt_discoveries_location_idx on gatt_discoveries using gist (location);

create index gatt_discoveries_addr_idx on gatt_discoveries (addr);

create index gatt_discoveries_discovered_at_idx on gatt_discoveries (discovered_at);

create index gatt_discoveries_profile_hash_idx on gatt_discoveries (profile_hash);

create index gatt_attributes_kind_uuid_idx on gatt_attributes (kind, uuid);

create index gatt_attributes_uuid_idx on gatt_attributes (uuid);

-- passive enrichment output over aggregate advertisements, keyed on exact producer content.
create table advertisement_enrichments (
  addr macaddr not null,
  raw bytea not null,
  enrichment_kind text not null,
  enrichment_id text not null,
  enrichment_revision text not null,
  recipe_git_commit text,
  recipe_path text,
  data jsonb not null,
  computed_at timestamptz not null default now(),
  primary key (addr, raw, enrichment_kind, enrichment_id, enrichment_revision)
);

create index advertisement_enrichments_enrichment_idx on advertisement_enrichments (enrichment_kind, enrichment_id);
create index advertisement_enrichments_data_idx on advertisement_enrichments using gin (data jsonb_path_ops);

-- active recipe CLI sessions and their structured transcript/BLE logs.
create table recipe_interaction_runs (
  id serial primary key,
  recipe_id text not null,
  recipe_sha256 text not null,
  recipe_git_commit text not null,
  recipe_path text not null,
  addr macaddr not null,
  raw_advertisement bytea,
  started_at timestamptz not null,
  finished_at timestamptz,
  status text not null,
  result jsonb
);

create index recipe_interaction_runs_addr_started_at_idx on recipe_interaction_runs (addr, started_at);
create index recipe_interaction_runs_recipe_idx on recipe_interaction_runs (recipe_id, recipe_sha256);

create table recipe_interaction_events (
  run_id integer not null references recipe_interaction_runs (id) on delete cascade,
  seq integer not null,
  at timestamptz not null,
  event text not null,
  data jsonb not null,
  primary key (run_id, seq)
);

create index recipe_interaction_events_event_idx on recipe_interaction_events (event);

-- built-in AD structure parsing: parse_ble_adv(raw), populated eagerly on scan insert.
-- statement-level so bulk inserts coalesce into one INSERT ... SELECT DISTINCT.
create or replace function upsert_advertisement_structure () returns trigger as $$
begin
    insert into advertisement_enrichments (
      addr,
      raw,
      enrichment_kind,
      enrichment_id,
      enrichment_revision,
      data
    )
    select distinct
      n.addr,
      n.raw,
      'builtin',
      'ad_structures',
      '20260416210856_init',
      parse_ble_adv(n.raw)
    from new_table n
    on conflict (addr, raw, enrichment_kind, enrichment_id, enrichment_revision) do nothing;
    return null;
end;
$$ language plpgsql;

create trigger scans_upsert_advertisement_structure
after insert on scans
referencing new table as new_table
for each statement
execute function upsert_advertisement_structure ();

create materialized view advertisement_observations as
with advertisement_observation_groups as (
  select
    blob,
    addr,
    raw,
    (
      array_agg(
        local_name
        order by
          scanned_at desc
      )
    ) [1] as local_name,
    min(scanned_at) as first_seen,
    max(scanned_at) as last_seen,
    avg(rssi)::float4 as rssi_avg,
    st_centroid (st_collect (location)) as centroid,
    count(*)::int as scan_count
  from
    scans
  group by
    blob,
    addr,
    raw
)
select
  g.blob,
  g.addr,
  g.raw,
  g.local_name,
  g.first_seen,
  g.last_seen,
  g.rssi_avg,
  g.centroid,
  (
    select
      max(st_distance (s.location::geography, g.centroid::geography))::float4
    from
      scans s
    where
      s.blob is not distinct from g.blob
      and s.addr = g.addr
      and s.raw = g.raw
      and s.location is not null
      and g.centroid is not null
  ) as radius,
  g.scan_count
from
  advertisement_observation_groups g;

create unique index on advertisement_observations (blob, addr, raw);

create materialized view advertisements as
with advertisement_groups as (
  select
    addr,
    raw,
    (
      array_agg(
        local_name
        order by
          last_seen desc
      )
    ) [1] as local_name,
    min(first_seen) as first_seen,
    max(last_seen) as last_seen,
    avg(rssi_avg)::float4 as rssi_avg,
    st_centroid (st_collect (centroid)) as centroid
  from
    advertisement_observations
  group by
    addr,
    raw
)
select
  g.addr,
  g.raw,
  g.local_name,
  g.first_seen,
  g.last_seen,
  g.rssi_avg,
  g.centroid,
  (
    select
      max(st_distance (o.centroid::geography, g.centroid::geography))::float4
    from
      advertisement_observations o
    where
      o.addr = g.addr
      and o.raw = g.raw
      and o.centroid is not null
      and g.centroid is not null
  ) as radius
from
  advertisement_groups g;

create unique index on advertisements (addr, raw);
create index advertisements_raw_idx on advertisements (raw);

-- per-device rollup: "where/when has this MAC been observed"
create materialized view devices as
select
  addr,
  count(*)::int as variant_count,
  (
    array_agg(
      local_name
      order by
        last_seen desc
    ) filter (where local_name is not null)
  ) [1] as local_name,
  min(first_seen) as first_seen,
  max(last_seen) as last_seen,
  avg(rssi_avg)::float4 as rssi_avg,
  st_centroid (st_collect (centroid)) as centroid
from
  advertisements
group by
  addr;

create unique index on devices (addr);

-- per-payload rollup: "where/when has this advertisement been observed"
create materialized view payloads as
select
  raw,
  count(*)::int as addr_count,
  min(first_seen) as first_seen,
  max(last_seen) as last_seen,
  avg(rssi_avg)::float4 as rssi_avg,
  st_centroid (st_collect (centroid)) as centroid
from
  advertisements
group by
  raw;

create unique index on payloads (raw);

-- migrate:down
drop materialized view payloads;

drop materialized view devices;

drop materialized view advertisements;

drop materialized view advertisement_observations;

drop trigger scans_upsert_advertisement_structure on scans;

drop function upsert_advertisement_structure;

drop table recipe_interaction_events;

drop table recipe_interaction_runs;

drop table advertisement_enrichments;

drop table gatt_attributes;

drop trigger gatt_discoveries_set_location on gatt_discoveries;

drop table gatt_discoveries;

drop trigger scans_set_location on scans;

drop function set_location;

drop function parse_ble_adv;

drop table scans;

drop table blobs;

drop extension postgis;
