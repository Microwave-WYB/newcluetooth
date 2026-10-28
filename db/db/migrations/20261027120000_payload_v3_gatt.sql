-- migrate:up
-- Producer payload v3 (extended advertising) and GATT discoveries (gatt/v1).
--
-- 1. sighting_minutes: the v3 PDU fields. The reserved columns addr_type,
--    is_legacy, primary_phy and secondary_phy become part of the row key
--    together with the new adv_sid and data_status: they say which kind of
--    advertisement a sighting was, so two kinds are never merged into one
--    minute row and the merge stays exact (the previous "first non-null wins"
--    depended on the order of ingestion). adv_tx_power and periodic_interval
--    are measurements, not identity: they merge as the maximum like tx_power.
--    v2 and legacy rows hold nulls in all of them, and the key treats nulls as
--    equal, so their keys are unchanged.
-- 2. gatt_discoveries / gatt_attributes: the legacy tables were never written
--    by any code. They are replaced, but only if they are empty; the guard
--    below aborts the migration otherwise.

do $$
begin
  if exists (select 1 from gatt_discoveries) or exists (select 1 from gatt_attributes) then
    raise exception 'gatt_discoveries or gatt_attributes holds rows; refusing to replace them';
  end if;
end;
$$;

alter table sighting_minutes
  add column adv_sid smallint,
  add column adv_tx_power smallint,
  add column periodic_interval integer,
  add column data_status smallint,
  add constraint sighting_minutes_v3_ranges check (
    (primary_phy is null or primary_phy in (1, 3))
    and (secondary_phy is null or secondary_phy between 0 and 3)
    and (adv_sid is null or adv_sid between 0 and 15)
    and (periodic_interval is null or periodic_interval between 1 and 65536)
    and (data_status is null or data_status between 0 and 2)
    and (addr_type is null or addr_type between 0 and 255)
  );

-- Rebuilds the unique index on every monthly partition (a short ACCESS
-- EXCLUSIVE lock on the table; the key columns added are all null today).
alter table sighting_minutes
  drop constraint sighting_minutes_key,
  add constraint sighting_minutes_key
    unique nulls not distinct (
      minute, addr, payload_id, uploader_id, session_id,
      addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status
    );

-- adv_shapes.minutes counted minute rows; with the PDU fields in the key one
-- address-minute can have several rows, so count the distinct minutes.
create or replace view adv_shapes as
select
  m.addr,
  m.payload_id,
  f.sha256,
  m.first_seen,
  m.last_seen,
  m.sightings,
  m.minutes,
  m.rssi_min,
  m.rssi_max,
  f.local_name,
  f.adv_types,
  f.manufacturer_ids,
  f.service_uuids,
  f.service_data_uuids,
  m.min_lat,
  m.max_lat,
  m.min_lon,
  m.max_lon,
  f.adv_types as ad_types_ordered,
  f.ad_data_lengths
from (
  select
    addr,
    payload_id,
    min(first_seen) as first_seen,
    max(last_seen) as last_seen,
    sum(sightings)::bigint as sightings,
    count(distinct minute)::bigint as minutes,
    min(rssi_min) as rssi_min,
    max(rssi_max) as rssi_max,
    min(min_lat) as min_lat,
    max(max_lat) as max_lat,
    min(min_lon) as min_lon,
    max(max_lon) as max_lon
  from sighting_minutes
  group by addr, payload_id
) m
join payload_fields f on f.payload_id = m.payload_id;

drop table gatt_attributes;
drop trigger gatt_discoveries_set_location on gatt_discoveries;
drop table gatt_discoveries;

-- One row per GATT discovery object (gatt/v1). `raw` is the full JSON.
-- profile_hash is sha256 (lowercase hex) of the canonical structure without
-- values and instance ids (docs/payload-schema-v3.md); null when no service was
-- discovered.
create table gatt_discoveries (
  id bigint generated always as identity primary key,
  blob_id bigint not null unique references blobs (id),
  -- UUIDv7 of the object (path, footer-less: the JSON `id`).
  discovery_uuid uuid not null unique,
  session_id uuid,
  app_version text,
  device_model text,
  android_version text,
  addr macaddr not null,
  addr_type text,
  -- The advertisement that was on screen when the operator started the
  -- discovery. The payload is stored once in `payloads`; the PDU fields of the
  -- sighting sit next to it.
  trigger_payload_id bigint references payloads (id),
  trigger_rssi smallint,
  trigger_scanned_at timestamptz,
  trigger_is_legacy boolean,
  trigger_primary_phy smallint,
  trigger_secondary_phy smallint,
  trigger_adv_sid smallint,
  started_at timestamptz not null,
  ended_at timestamptz,
  lat float8,
  lon float8,
  accuracy float4,
  location geometry (Point, 4326),
  outcome text not null,
  stage_reached text not null,
  error_stage text,
  error_status integer,
  error_name text,
  disconnect_initiator text,
  disconnect_status integer,
  mtu smallint,
  phy_tx smallint,
  phy_rx smallint,
  connect_ms integer,
  mtu_ms integer,
  discover_ms integer,
  read_ms integer,
  profile_hash text,
  raw jsonb not null,
  constraint gatt_discoveries_outcome check (
    outcome in ('complete', 'partial', 'failed', 'cancelled')
  ),
  constraint gatt_discoveries_stage check (
    stage_reached in ('connect', 'mtu', 'discover', 'read', 'disconnect')
  ),
  constraint gatt_discoveries_ended check (ended_at is null or ended_at >= started_at),
  constraint gatt_discoveries_coordinates_paired check ((lat is null) = (lon is null)),
  constraint gatt_discoveries_lat_range check (lat is null or lat between -90 and 90),
  constraint gatt_discoveries_lon_range check (lon is null or lon between -180 and 180),
  constraint gatt_discoveries_accuracy_semantics check (
    (accuracy is null or lat is not null)
    and (accuracy is null or (accuracy >= 0 and accuracy < 'Infinity'::float4))
  ),
  constraint gatt_discoveries_mtu_range check (mtu is null or mtu between 23 and 517),
  constraint gatt_discoveries_phy_range check (
    (phy_tx is null or phy_tx between 1 and 3) and (phy_rx is null or phy_rx between 1 and 3)
  )
);

create trigger gatt_discoveries_set_location before insert
or
update on gatt_discoveries for each row
execute function set_location ();

create index gatt_discoveries_location_idx on gatt_discoveries using gist (location);
create index gatt_discoveries_addr_idx on gatt_discoveries (addr);
create index gatt_discoveries_started_at_idx on gatt_discoveries (started_at);
create index gatt_discoveries_profile_hash_idx on gatt_discoveries (profile_hash);
create index gatt_discoveries_trigger_payload_idx on gatt_discoveries (trigger_payload_id)
  where trigger_payload_id is not null;

-- One row per service, characteristic or descriptor, in document order
-- (attribute_id counts from 1, parents before children). uuid uses the notation
-- of ad_structures.uuids: lowercase, 16-bit as 4 hex digits.
create table gatt_attributes (
  discovery_id bigint not null references gatt_discoveries (id) on delete cascade,
  attribute_id integer not null,
  parent_attribute_id integer,
  kind text not null,
  uuid text not null,
  -- Services only.
  is_primary boolean,
  -- Android instance id (not an ATT handle); null for descriptors.
  instance integer,
  -- Characteristics only.
  properties text[],
  -- Characteristics and descriptors: the bytes read (null unless read_status
  -- is 'ok') and how the read ended.
  value bytea,
  read_status text,
  primary key (discovery_id, attribute_id),
  foreign key (discovery_id, parent_attribute_id)
    references gatt_attributes (discovery_id, attribute_id),
  constraint gatt_attributes_kind check (kind in ('service', 'characteristic', 'descriptor')),
  constraint gatt_attributes_parent check ((kind = 'service') = (parent_attribute_id is null)),
  constraint gatt_attributes_service_fields check (
    kind = 'service' or is_primary is null
  ),
  constraint gatt_attributes_properties check (kind = 'characteristic' or properties is null),
  constraint gatt_attributes_read check (
    case kind
      when 'service' then value is null and read_status is null
      else read_status is not null and (value is null or read_status = 'ok')
    end
  ),
  constraint gatt_attributes_read_status check (
    read_status is null
    or read_status in (
      'ok', 'insufficient_authentication', 'insufficient_encryption',
      'read_not_permitted', 'timeout', 'not_read'
    )
    or read_status ~ '^error:-?[0-9]+$'
  )
);

create index gatt_attributes_kind_uuid_idx on gatt_attributes (kind, uuid);
create index gatt_attributes_uuid_idx on gatt_attributes (uuid);

-- migrate:down
-- Destroys every GATT discovery and the minute rows that carry a v3 key field,
-- and puts the ledger back so they can be ingested again:
--   * the scans/v3/ and gatt/v1/ blobs are reset (success null, no attempts);
--     a sync of the older code rejects them (unsupported path) until this
--     migration is applied again, after which they are ingested afresh;
--   * the stats of the payloads those rows pointed at are recomputed from the
--     remaining rows, so ingesting again does not count them twice.
-- A v3 file whose rows carried no PDU field at all lands in rows that also hold
-- other blobs; those cannot be taken apart, so the rollback refuses then.
-- A minute row a WiGLE batch already claims blocks the delete and aborts.
do $$
begin
  if exists (
    select 1
    from sighting_minutes m
    join blobs b on b.id = any (m.blob_ids)
    where b.uri ~ '^gs://[^/]+/scans/v3/'
      and m.addr_type is null and m.is_legacy is null and m.primary_phy is null
      and m.secondary_phy is null and m.adv_sid is null and m.data_status is null
  ) then
    raise exception 'a scans/v3 blob also contributed to minute rows without v3 key fields; rebuild those minutes first';
  end if;
end;
$$;

drop table gatt_attributes;
drop trigger gatt_discoveries_set_location on gatt_discoveries;
drop table gatt_discoveries;

-- The legacy tables as created by the init migration, plus the coordinate
-- checks of 20260805233000.
create table gatt_discoveries (
  id serial primary key,
  blob text references blobs (uri),
  addr macaddr not null,
  discovered_at timestamptz not null,
  raw_adv bytea,
  raw_profile jsonb not null,
  profile_hash text,
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

create trigger gatt_discoveries_set_location before insert
or
update on gatt_discoveries for each row
execute function set_location ();

create index gatt_discoveries_location_idx on gatt_discoveries using gist (location);
create index gatt_discoveries_addr_idx on gatt_discoveries (addr);
create index gatt_discoveries_discovered_at_idx on gatt_discoveries (discovered_at);
create index gatt_discoveries_profile_hash_idx on gatt_discoveries (profile_hash);
create index gatt_attributes_kind_uuid_idx on gatt_attributes (kind, uuid);
create index gatt_attributes_uuid_idx on gatt_attributes (uuid);

alter table gatt_discoveries
  add constraint gatt_discoveries_coordinates_paired
    check ((lat is null) = (lon is null)) not valid,
  add constraint gatt_discoveries_lat_range
    check (lat is null or lat between -90 and 90) not valid,
  add constraint gatt_discoveries_lon_range
    check (lon is null or lon between -180 and 180) not valid,
  add constraint gatt_discoveries_accuracy_semantics
    check (
      (accuracy is null or lat is not null)
      and (accuracy is null or (accuracy >= 0 and accuracy < 'Infinity'::float4))
    ) not valid;

create temporary table v3_rollback_payloads on commit drop as
select distinct payload_id
from sighting_minutes
where addr_type is not null
  or is_legacy is not null
  or primary_phy is not null
  or secondary_phy is not null
  or adv_sid is not null
  or data_status is not null;

delete from sighting_minutes
where addr_type is not null
  or is_legacy is not null
  or primary_phy is not null
  or secondary_phy is not null
  or adv_sid is not null
  or data_status is not null;

update payloads p
set first_seen = rest.first_seen,
    last_seen = rest.last_seen,
    sightings = coalesce(rest.sightings, 0)
from v3_rollback_payloads r
left join lateral (
  select min(m.first_seen) as first_seen, max(m.last_seen) as last_seen,
         sum(m.sightings)::bigint as sightings
  from sighting_minutes m
  where m.payload_id = r.payload_id
) rest on true
where p.id = r.payload_id;

update blobs
set success = null, error = null, attempts = 0, next_attempt_at = null, synced_at = null
where uri ~ '^gs://[^/]+/(scans/v3|gatt/v1)/';

alter table sighting_minutes
  drop constraint sighting_minutes_key,
  add constraint sighting_minutes_key
    unique nulls not distinct (minute, addr, payload_id, uploader_id, session_id);

create or replace view adv_shapes as
select
  m.addr,
  m.payload_id,
  f.sha256,
  m.first_seen,
  m.last_seen,
  m.sightings,
  m.minutes,
  m.rssi_min,
  m.rssi_max,
  f.local_name,
  f.adv_types,
  f.manufacturer_ids,
  f.service_uuids,
  f.service_data_uuids,
  m.min_lat,
  m.max_lat,
  m.min_lon,
  m.max_lon,
  f.adv_types as ad_types_ordered,
  f.ad_data_lengths
from (
  select
    addr,
    payload_id,
    min(first_seen) as first_seen,
    max(last_seen) as last_seen,
    sum(sightings)::bigint as sightings,
    count(*)::bigint as minutes,
    min(rssi_min) as rssi_min,
    max(rssi_max) as rssi_max,
    min(min_lat) as min_lat,
    max(max_lat) as max_lat,
    min(min_lon) as min_lon,
    max(max_lon) as max_lon
  from sighting_minutes
  group by addr, payload_id
) m
join payload_fields f on f.payload_id = m.payload_id;

alter table sighting_minutes
  drop constraint sighting_minutes_v3_ranges,
  drop column data_status,
  drop column periodic_interval,
  drop column adv_tx_power,
  drop column adv_sid;
