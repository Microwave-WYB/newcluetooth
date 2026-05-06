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

        if byte_offset + field_len >= bytes_len then
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

create or replace function ble_uuid128_le (uuid_bytes bytea) returns text as $$
declare
    h text;
begin
    if uuid_bytes is null or octet_length(uuid_bytes) != 16 then
        return null;
    end if;

    h := encode(uuid_bytes, 'hex');
    return
        substring(h from 31 for 2) ||
        substring(h from 29 for 2) ||
        substring(h from 27 for 2) ||
        substring(h from 25 for 2) ||
        '-' ||
        substring(h from 23 for 2) ||
        substring(h from 21 for 2) ||
        '-' ||
        substring(h from 19 for 2) ||
        substring(h from 17 for 2) ||
        '-' ||
        substring(h from 15 for 2) ||
        substring(h from 13 for 2) ||
        '-' ||
        substring(h from 11 for 2) ||
        substring(h from 9 for 2) ||
        substring(h from 7 for 2) ||
        substring(h from 5 for 2) ||
        substring(h from 3 for 2) ||
        substring(h from 1 for 2);
end;
$$ language plpgsql immutable strict;

create or replace function ble_adv_types (raw bytea) returns smallint[] as $$
  select coalesce(
    array_agg((item.value ->> 'type')::smallint order by item.ordinality),
    array[]::smallint[]
  )
  from jsonb_array_elements(public.parse_ble_adv(raw)) with ordinality as item(value, ordinality)
$$ language sql immutable strict;

create or replace function ble_adv_manufacturer_ids (raw bytea) returns integer[] as $$
  with manufacturer_data as (
    select decode(item.value ->> 'data', 'hex') as data
    from jsonb_array_elements(public.parse_ble_adv(raw)) as item(value)
    where (item.value ->> 'type')::int = 255
      and length(item.value ->> 'data') >= 4
  ),
  manufacturer_ids as (
    select get_byte(data, 0) + get_byte(data, 1) * 256 as company_id
    from manufacturer_data
  )
  select coalesce(
    array_agg(distinct company_id order by company_id),
    array[]::integer[]
  )
  from manufacturer_ids
$$ language sql immutable strict;

create or replace function ble_adv_service_uuids (raw bytea) returns text[] as $$
declare
    ad_struct jsonb;
    ad_type int;
    data bytea;
    byte_offset int;
    uuid text;
    result text[] := array[]::text[];
begin
    for ad_struct in select value from jsonb_array_elements(public.parse_ble_adv(raw)) loop
        ad_type := (ad_struct ->> 'type')::int;
        data := decode(ad_struct ->> 'data', 'hex');

        if ad_type in (2, 3) then
            byte_offset := 0;
            while byte_offset + 2 <= octet_length(data) loop
                uuid := lpad(
                    to_hex(get_byte(data, byte_offset + 1) * 256 + get_byte(data, byte_offset)),
                    4,
                    '0'
                );
                result := array_append(result, uuid);
                byte_offset := byte_offset + 2;
            end loop;
        elsif ad_type in (4, 5) then
            byte_offset := 0;
            while byte_offset + 4 <= octet_length(data) loop
                uuid :=
                    lpad(to_hex(get_byte(data, byte_offset + 3)), 2, '0') ||
                    lpad(to_hex(get_byte(data, byte_offset + 2)), 2, '0') ||
                    lpad(to_hex(get_byte(data, byte_offset + 1)), 2, '0') ||
                    lpad(to_hex(get_byte(data, byte_offset)), 2, '0');
                result := array_append(result, uuid);
                byte_offset := byte_offset + 4;
            end loop;
        elsif ad_type in (6, 7) then
            byte_offset := 0;
            while byte_offset + 16 <= octet_length(data) loop
                uuid := public.ble_uuid128_le(substring(data from byte_offset + 1 for 16));
                if uuid is not null then
                    result := array_append(result, uuid);
                end if;
                byte_offset := byte_offset + 16;
            end loop;
        end if;
    end loop;

    return coalesce(
        (select array_agg(distinct u.uuid order by u.uuid) from unnest(result) as u(uuid)),
        array[]::text[]
    );
end;
$$ language plpgsql immutable strict;

create or replace function ble_adv_service_data_uuids (raw bytea) returns text[] as $$
declare
    ad_struct jsonb;
    ad_type int;
    data bytea;
    uuid text;
    result text[] := array[]::text[];
begin
    for ad_struct in select value from jsonb_array_elements(public.parse_ble_adv(raw)) loop
        ad_type := (ad_struct ->> 'type')::int;
        data := decode(ad_struct ->> 'data', 'hex');

        if ad_type = 22 and octet_length(data) >= 2 then
            uuid := lpad(to_hex(get_byte(data, 1) * 256 + get_byte(data, 0)), 4, '0');
            result := array_append(result, uuid);
        elsif ad_type = 32 and octet_length(data) >= 4 then
            uuid :=
                lpad(to_hex(get_byte(data, 3)), 2, '0') ||
                lpad(to_hex(get_byte(data, 2)), 2, '0') ||
                lpad(to_hex(get_byte(data, 1)), 2, '0') ||
                lpad(to_hex(get_byte(data, 0)), 2, '0');
            result := array_append(result, uuid);
        elsif ad_type = 33 and octet_length(data) >= 16 then
            uuid := public.ble_uuid128_le(substring(data from 1 for 16));
            if uuid is not null then
                result := array_append(result, uuid);
            end if;
        end if;
    end loop;

    return coalesce(
        (select array_agg(distinct u.uuid order by u.uuid) from unnest(result) as u(uuid)),
        array[]::text[]
    );
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
  id bigserial primary key,
  blob text references blobs (uri),
  -- device
  addr macaddr not null,
  rssi smallint,
  scanned_at timestamptz not null,
  -- adv
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

create table projection_state (
  name text primary key,
  scans_through_id bigint not null default 0,
  updated_at timestamptz not null default now()
);

insert into projection_state (name) values ('advs');

create table advs (
  addr macaddr not null,
  raw bytea not null,
  scans_through_id bigint not null,
  first_seen timestamptz not null,
  last_seen timestamptz not null,
  scans_count bigint not null,
  rssi_min smallint,
  centroid_lat float8,
  centroid_lon float8,
  location_count bigint not null,
  radius float8,
  local_name text,
  adv_types smallint[] not null,
  manufacturer_ids integer[] not null,
  service_uuids text[] not null,
  service_data_uuids text[] not null,
  primary key (addr, raw)
);

create table gatt_discoveries (
  id serial primary key,
  blob text references blobs (uri),
  -- device
  addr macaddr not null,
  discovered_at timestamptz not null,
  raw_adv bytea,
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
    else
        new.location = null;
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

create index scans_addr_raw_id_idx on scans (addr, raw, id);

create index scans_addr_raw_scanned_at_idx on scans (addr, raw, scanned_at);

create index advs_last_seen_idx on advs (last_seen);

create index advs_scans_through_id_idx on advs (scans_through_id);

create index advs_adv_types_idx on advs using gin (adv_types);

create index advs_manufacturer_ids_idx on advs using gin (manufacturer_ids);

create index advs_service_uuids_idx on advs using gin (service_uuids);

create index advs_service_data_uuids_idx on advs using gin (service_data_uuids);

create index gatt_discoveries_location_idx on gatt_discoveries using gist (location);

create index gatt_discoveries_addr_idx on gatt_discoveries (addr);

create index gatt_discoveries_discovered_at_idx on gatt_discoveries (discovered_at);

create index gatt_discoveries_profile_hash_idx on gatt_discoveries (profile_hash);

create index gatt_attributes_kind_uuid_idx on gatt_attributes (kind, uuid);

create index gatt_attributes_uuid_idx on gatt_attributes (uuid);

-- passive enrichment output over aggregate advs, keyed on exact producer content.
create table adv_enrichments (
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

create index adv_enrichments_enrichment_idx on adv_enrichments (enrichment_kind, enrichment_id);
create index adv_enrichments_data_idx on adv_enrichments using gin (data jsonb_path_ops);

-- active recipe CLI sessions and their structured transcript/BLE logs.
create table recipe_interaction_runs (
  id serial primary key,
  recipe_id text not null,
  recipe_sha256 text not null,
  recipe_git_commit text not null,
  recipe_path text not null,
  addr macaddr not null,
  raw_adv bytea,
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

-- migrate:down
drop table recipe_interaction_events;

drop table recipe_interaction_runs;

drop table adv_enrichments;

drop table gatt_attributes;

drop trigger gatt_discoveries_set_location on gatt_discoveries;

drop table gatt_discoveries;

drop trigger scans_set_location on scans;

drop function set_location;

drop table advs;

drop table projection_state;

drop table scans;

drop table blobs;

drop function ble_adv_service_data_uuids(bytea);

drop function ble_adv_service_uuids(bytea);

drop function ble_adv_manufacturer_ids(bytea);

drop function ble_adv_types(bytea);

drop function ble_uuid128_le(bytea);

drop function parse_ble_adv(bytea);

drop extension postgis;
