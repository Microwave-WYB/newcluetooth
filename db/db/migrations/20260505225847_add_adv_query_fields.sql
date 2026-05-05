-- migrate:up
drop trigger if exists scans_upsert_adv_structure on scans;

drop function if exists upsert_adv_structure();

refresh materialized view adv_observations;

drop materialized view payloads;

drop materialized view devices;

drop materialized view advs;

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
$$ language plpgsql immutable;

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
                uuid := ble_uuid128_le(substring(data from byte_offset + 1 for 16));
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
            uuid := ble_uuid128_le(substring(data from 1 for 16));
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

create materialized view advs as
with adv_groups as (
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
    adv_observations
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
      adv_observations o
    where
      o.addr = g.addr
      and o.raw = g.raw
      and o.centroid is not null
      and g.centroid is not null
  ) as radius,
  ble_adv_types(g.raw) as adv_types,
  ble_adv_manufacturer_ids(g.raw) as manufacturer_ids,
  ble_adv_service_uuids(g.raw) as service_uuids,
  ble_adv_service_data_uuids(g.raw) as service_data_uuids
from
  adv_groups g;

create unique index on advs (addr, raw);
create index advs_raw_idx on advs (raw);
create index advs_adv_types_idx on advs using gin (adv_types);
create index advs_manufacturer_ids_idx on advs using gin (manufacturer_ids);
create index advs_service_uuids_idx on advs using gin (service_uuids);
create index advs_service_data_uuids_idx on advs using gin (service_data_uuids);

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
  advs
group by
  addr;

create unique index on devices (addr);

create materialized view payloads as
select
  raw,
  count(*)::int as addr_count,
  min(first_seen) as first_seen,
  max(last_seen) as last_seen,
  avg(rssi_avg)::float4 as rssi_avg,
  st_centroid (st_collect (centroid)) as centroid
from
  advs
group by
  raw;

create unique index on payloads (raw);

-- migrate:down
drop materialized view payloads;

drop materialized view devices;

drop materialized view advs;

create materialized view advs as
with adv_groups as (
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
    adv_observations
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
      adv_observations o
    where
      o.addr = g.addr
      and o.raw = g.raw
      and o.centroid is not null
      and g.centroid is not null
  ) as radius
from
  adv_groups g;

create unique index on advs (addr, raw);
create index advs_raw_idx on advs (raw);

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
  advs
group by
  addr;

create unique index on devices (addr);

create materialized view payloads as
select
  raw,
  count(*)::int as addr_count,
  min(first_seen) as first_seen,
  max(last_seen) as last_seen,
  avg(rssi_avg)::float4 as rssi_avg,
  st_centroid (st_collect (centroid)) as centroid
from
  advs
group by
  raw;

create unique index on payloads (raw);

drop function ble_adv_service_data_uuids(bytea);

drop function ble_adv_service_uuids(bytea);

drop function ble_adv_manufacturer_ids(bytea);

drop function ble_adv_types(bytea);

drop function ble_uuid128_le(bytea);
