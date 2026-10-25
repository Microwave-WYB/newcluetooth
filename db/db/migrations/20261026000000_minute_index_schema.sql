-- migrate:up
-- Minute-index schema. The database no longer stores per-sighting rows: the
-- uploaded GCS objects are the raw archive (`cluetooth replay` parses them),
-- and the database indexes them as
--   * ad_structures: every distinct AD structure once, unique on (ad_type, data);
--   * payloads: each exact advertisement once, as sha256 + ordered structure ids;
--   * sighting_minutes: one row per (minute, addr, payload, uploader, session),
--     partitioned by month, with exact mergeable sums and a geography centroid.
-- There is no stored per-(addr, payload) rollup: `adv_shapes` is a plain view
-- over sighting_minutes (make it a materialized view if a whole-database
-- analysis ever needs one).
--
-- The previous tables are renamed, not dropped: scans_old, advs_old,
-- adv_enrichments_old, wigle_upload_batch_scans_old, projection_state_old.
-- Existing data is copied by
-- `cluetooth backfill` (docs/operations.md); the old tables are dropped only by
-- a later explicit migration.

drop view adv_shapes;

alter table scans rename to scans_old;
alter index scans_pkey rename to scans_old_pkey;
alter index scans_location_idx rename to scans_old_location_idx;
alter index scans_addr_idx rename to scans_old_addr_idx;
alter index scans_scanned_at_idx rename to scans_old_scanned_at_idx;

alter table advs rename to advs_old;
alter index advs_pkey rename to advs_old_pkey;
alter index advs_last_seen_idx rename to advs_old_last_seen_idx;
alter index advs_scans_through_id_idx rename to advs_old_scans_through_id_idx;
alter index advs_adv_types_idx rename to advs_old_adv_types_idx;
alter index advs_manufacturer_ids_idx rename to advs_old_manufacturer_ids_idx;
alter index advs_service_uuids_idx rename to advs_old_service_uuids_idx;
alter index advs_service_data_uuids_idx rename to advs_old_service_data_uuids_idx;
alter index advs_centroid_idx rename to advs_old_centroid_idx;
alter index advs_bbox_idx rename to advs_old_bbox_idx;

alter table projection_state rename to projection_state_old;
alter index projection_state_pkey rename to projection_state_old_pkey;

alter table adv_enrichments rename to adv_enrichments_old;
alter index adv_enrichments_pkey rename to adv_enrichments_old_pkey;
alter index adv_enrichments_enrichment_idx rename to adv_enrichments_old_enrichment_idx;
alter index adv_enrichments_data_idx rename to adv_enrichments_old_data_idx;

alter table wigle_upload_batch_scans rename to wigle_upload_batch_scans_old;
alter index wigle_upload_batch_scans_pkey rename to wigle_upload_batch_scans_old_pkey;
alter index wigle_upload_batch_scans_scan_idx rename to wigle_upload_batch_scans_old_scan_idx;
-- Batches created before this migration hold scan ids here; later ones hold
-- sighting_minutes ids.
alter table wigle_upload_batches rename column min_scan_id to min_item_id;
alter table wigle_upload_batches rename column max_scan_id to max_item_id;

-- Tokenizer for exact payload storage. Yields the AD structures in order
-- (ord from 1) exactly as parse_ble_adv reads them, then at most one remainder
-- row (ad_type null) with the unparsed bytes after the last structure: the
-- zero padding of a fixed-size buffer, or a truncated field.
create function ble_adv_split (raw bytea)
returns table (ord smallint, ad_type smallint, data bytea) as $$
declare
    bytes_len int := octet_length(raw);
    byte_offset int := 0;
    field_len int;
    n smallint := 0;
begin
    while byte_offset < bytes_len loop
        field_len := get_byte(raw, byte_offset);
        exit when field_len = 0 or byte_offset + field_len >= bytes_len;
        n := n + 1;
        ord := n;
        ad_type := get_byte(raw, byte_offset + 1);
        data := substring(raw from byte_offset + 3 for field_len - 1);
        return next;
        byte_offset := byte_offset + field_len + 1;
    end loop;
    if byte_offset < bytes_len then
        ord := n + 1;
        ad_type := null;
        data := substring(raw from byte_offset + 1);
        return next;
    end if;
end;
$$ language plpgsql immutable strict;

-- Text for a name AD structure: trailing NULs removed, NULL when empty or not
-- valid UTF-8 (matching cluetooth_sync.pipeline.legacy_adapter).
create function ble_utf8_or_null (data bytea) returns text as $$
begin
    return nullif(convert_from(rtrim(data, '\x00'::bytea), 'UTF8'), '');
exception
    when others then
        return null;
end;
$$ language plpgsql immutable strict;

-- One structure re-encoded as a single-structure advertisement, so the
-- payload-level UUID decoders apply to it unchanged.
create function ble_structure_bytes (ad_type smallint, data bytea) returns bytea as $$
  select set_byte(set_byte('\x0000'::bytea, 0, octet_length(data) + 1), 1, ad_type) || data
$$ language sql immutable strict;

create function ble_structure_uuids (ad_type smallint, data bytea) returns text[] as $$
  select case
    when ad_type in (2, 3, 4, 5, 6, 7)
      then public.ble_adv_service_uuids(public.ble_structure_bytes(ad_type, data))
    when ad_type in (22, 32, 33)
      then nullif(
        public.ble_adv_service_data_uuids(public.ble_structure_bytes(ad_type, data)),
        array[]::text[]
      )
  end
$$ language sql immutable strict;

-- Every distinct AD structure once. Rules match here; shape conditions read
-- the payload's ordered structure_ids.
create table ad_structures (
  id bigint generated always as identity primary key,
  ad_type smallint not null,
  data bytea not null,
  local_name text generated always as (
    case when ad_type in (8, 9) then ble_utf8_or_null(data) end
  ) stored,
  company_id integer generated always as (
    case
      when ad_type = 255 and octet_length(data) >= 2
        then get_byte(data, 0) + get_byte(data, 1) * 256
    end
  ) stored,
  uuids text[] generated always as (ble_structure_uuids(ad_type, data)) stored,
  unique (ad_type, data)
);

create index ad_structures_company_id_idx on ad_structures (company_id)
  where company_id is not null;
create index ad_structures_local_name_idx on ad_structures (local_name)
  where local_name is not null;
create index ad_structures_uuids_idx on ad_structures using gin (uuids)
  where uuids is not null;

-- Each exact advertisement once. raw is not stored: payload_raw(id) rebuilds it
-- as the structures in order (length byte, type byte, data each), then `tail`
-- if present, else `zero_padding` zero bytes. sha256 of that reconstruction is
-- the exact identity of the original bytes (checked by the backfill).
create table payloads (
  id bigint generated always as identity primary key,
  sha256 bytea not null unique,
  structure_ids bigint[] not null,
  zero_padding smallint not null default 0,
  -- Unparsed remainder that is not all zeros (truncated field); null otherwise.
  tail bytea,
  -- Updated by each blob ingest transaction (sorted by payload id).
  first_seen timestamptz,
  last_seen timestamptz,
  sightings bigint not null default 0,
  constraint payloads_tail_or_padding check (tail is null or zero_padding = 0)
) with (fillfactor = 80);

create index payloads_structure_ids_idx on payloads using gin (structure_ids);
create index payloads_last_seen_idx on payloads (last_seen);

create function payload_raw (payload_id bigint) returns bytea as $$
  select
    coalesce(
      (
        select string_agg(public.ble_structure_bytes(s.ad_type, s.data), ''::bytea order by u.ord)
        from unnest(p.structure_ids) with ordinality as u(structure_id, ord)
        join public.ad_structures s on s.id = u.structure_id
      ),
      ''::bytea
    )
    || coalesce(p.tail, decode(repeat('00', p.zero_padding), 'hex'))
  from public.payloads p
  where p.id = payload_id
$$ language sql stable strict;

-- Payload-level fields with the semantics of the former advs columns.
create view payload_fields as
select
  p.id as payload_id,
  p.sha256,
  (
    select s.local_name
    from unnest(p.structure_ids) with ordinality as u(structure_id, ord)
    join ad_structures s on s.id = u.structure_id
    where s.ad_type in (8, 9)
    order by s.ad_type desc, u.ord
    limit 1
  ) as local_name,
  coalesce(
    (
      select array_agg(s.ad_type order by u.ord)
      from unnest(p.structure_ids) with ordinality as u(structure_id, ord)
      join ad_structures s on s.id = u.structure_id
    ),
    array[]::smallint[]
  ) as adv_types,
  coalesce(
    (
      select array_agg(octet_length(s.data)::smallint order by u.ord)
      from unnest(p.structure_ids) with ordinality as u(structure_id, ord)
      join ad_structures s on s.id = u.structure_id
    ),
    array[]::smallint[]
  ) as ad_data_lengths,
  coalesce(
    (
      select array_agg(distinct s.company_id order by s.company_id)
      from ad_structures s
      where s.id = any (p.structure_ids) and s.company_id is not null
    ),
    array[]::integer[]
  ) as manufacturer_ids,
  coalesce(
    (
      select array_agg(distinct x.uuid order by x.uuid)
      from ad_structures s
      cross join unnest(s.uuids) as x(uuid)
      where s.id = any (p.structure_ids) and s.ad_type in (2, 3, 4, 5, 6, 7)
    ),
    array[]::text[]
  ) as service_uuids,
  coalesce(
    (
      select array_agg(distinct x.uuid order by x.uuid)
      from ad_structures s
      cross join unnest(s.uuids) as x(uuid)
      where s.id = any (p.structure_ids) and s.ad_type in (22, 32, 33)
    ),
    array[]::text[]
  ) as service_data_uuids
from payloads p;

create table uploaders (
  id integer generated always as identity primary key,
  -- Producer token from the legacy object name (`<ts>_<uploader>_<version>`).
  uploader text not null unique,
  created_at timestamptz not null default now()
);

alter table blobs
  add column id bigint generated always as identity,
  add column session_id uuid,
  add column uploader_id integer references uploaders (id),
  drop column uploader;

alter table blobs add constraint blobs_id_key unique (id);

create index blobs_uploader_id_idx on blobs (uploader_id);

-- {"<blob_id>": n} count maps (provenance of a minute row).
create function jsonb_counts_merge (a jsonb, b jsonb) returns jsonb as $$
  select coalesce(jsonb_object_agg(key, total), '{}'::jsonb)
  from (
    select key, sum(value::bigint) as total
    from (
      select * from jsonb_each_text(coalesce(a, '{}'::jsonb))
      union all
      select * from jsonb_each_text(coalesce(b, '{}'::jsonb))
    ) entries
    group by key
    having sum(value::bigint) <> 0
  ) totals
$$ language sql immutable;

create function jsonb_counts_keys (counts jsonb) returns bigint[] as $$
  select coalesce(array_agg(key::bigint order by key::bigint), array[]::bigint[])
  from jsonb_object_keys(counts) as key
$$ language sql immutable strict;

-- Best-sample rule for a minute's location (coefficients provisional, to be
-- tuned against bought-device ground truth): score = rssi/20 - 2*log10(acc),
-- acc = accuracy in metres, 50 when missing, at least 1. No RSSI: no score.
create function sighting_sample_score (rssi smallint, accuracy float4) returns float8 as $$
  -- log(double precision) is the base-10 logarithm.
  select rssi::float8 / 20 - 2 * log(greatest(coalesce(accuracy, 50), 1)::float8)
$$ language sql immutable;

-- True if sample a beats sample b: higher score (a missing score loses), then
-- better (smaller) effective accuracy, then earlier time. b absent (b_seen
-- null) always loses.
create function sighting_sample_better (
  a_score float8, a_accuracy float4, a_seen timestamptz,
  b_score float8, b_accuracy float4, b_seen timestamptz
) returns boolean as $$
  select case
    when a_seen is null then false
    when b_seen is null then true
    when a_score is distinct from b_score then
      coalesce(a_score > b_score, a_score is not null)
    when greatest(coalesce(a_accuracy, 50), 1) <> greatest(coalesce(b_accuracy, 50), 1) then
      greatest(coalesce(a_accuracy, 50), 1) < greatest(coalesce(b_accuracy, 50), 1)
    else a_seen < b_seen
  end
$$ language sql immutable;

-- Primary sightings store: one row per (minute, addr, payload, uploader,
-- session), partitioned by month. Every column is exact under merge, so a
-- minute split across blobs (or backfill batches) equals a single pass.
create table sighting_minutes (
  id bigint generated by default as identity,
  minute timestamptz not null,
  addr macaddr not null,
  payload_id bigint not null references payloads (id),
  uploader_id integer references uploaders (id),
  -- Producer v2 objects carry no uploader identity; the session keeps phones
  -- apart. Null for legacy rows, where the key is (minute, addr, payload, uploader).
  session_id uuid,
  -- Provenance: {"<blob_id>": sightings from that blob}.
  blob_counts jsonb not null,
  blob_ids bigint[] generated always as (jsonb_counts_keys(blob_counts)) stored,
  sightings bigint not null,
  first_seen timestamptz,
  last_seen timestamptz,
  rssi_min smallint,
  rssi_max smallint,
  rssi_sum bigint not null default 0,
  rssi_count bigint not null default 0,
  rssi_mean float8 generated always as (
    case when rssi_count > 0 then rssi_sum::float8 / rssi_count end
  ) stored,
  -- Location = the single best located sample of the minute (see
  -- sighting_sample_score); merging keeps the better sample, so it is exact.
  location geography (Point, 4326),
  best_rssi smallint,
  -- The sample's own accuracy (null if the producer gave none).
  best_accuracy float4,
  best_score float8,
  best_seen timestamptz,
  -- Spatial extent of all phone positions in the minute (flags co-moving devices).
  min_lat float8,
  max_lat float8,
  min_lon float8,
  max_lon float8,
  -- Highest advertised TX power; connectable if any sighting was.
  tx_power smallint,
  is_connectable boolean,
  -- Reserved for producer schema v3; first non-null value wins on merge.
  addr_type smallint,
  adv_length smallint,
  is_legacy boolean,
  primary_phy smallint,
  secondary_phy smallint,
  primary key (id, minute),
  constraint sighting_minutes_key
    unique nulls not distinct (minute, addr, payload_id, uploader_id, session_id),
  constraint sighting_minutes_extent_range check (
    (min_lat is null or min_lat between -90 and 90)
    and (max_lat is null or max_lat between -90 and 90)
    and (min_lon is null or min_lon between -180 and 180)
    and (max_lon is null or max_lon between -180 and 180)
  )
) partition by range (minute);

-- Besides the key (used by the merge upsert) and the id primary key (WiGLE
-- membership), only per-adv lookups and spatial queries are indexed.
create index sighting_minutes_addr_payload_idx on sighting_minutes (addr, payload_id);
create index sighting_minutes_location_idx on sighting_minutes using gist (location);

-- Minutes outside every monthly partition (a producer clock far off) land here.
create table sighting_minutes_default partition of sighting_minutes default;

-- Creates the monthly partition containing `month` if missing. Safe to call
-- repeatedly and concurrently; returns false when it cannot be created because
-- the default partition already holds rows for that month.
create function ensure_sighting_partition (month date) returns boolean as $$
declare
    start_month date := date_trunc('month', month)::date;
    partition_name text := format(
        'sighting_minutes_y%sm%s', to_char(start_month, 'YYYY'), to_char(start_month, 'MM')
    );
begin
    if to_regclass(format('public.%I', partition_name)) is not null then
        return true;
    end if;
    execute format(
        'create table if not exists public.%I partition of public.sighting_minutes '
        'for values from (%L) to (%L)',
        partition_name,
        start_month::text || ' 00:00:00+00',
        (start_month + interval '1 month')::date::text || ' 00:00:00+00'
    );
    return true;
exception
    when duplicate_table or unique_violation then
        return true;
    when check_violation then
        raise notice 'sighting_minutes_default holds rows for %; leaving them there',
            start_month;
        return false;
end;
$$ language plpgsql;

select ensure_sighting_partition(month::date)
from generate_series(date '2025-01-01', date '2027-12-01', interval '1 month') as month;

-- Passive enrichment output for an exact (addr, payload) adv. Built-in AD
-- structure parsing now lives in ad_structures instead of rows here.
create table adv_enrichments (
  addr macaddr not null,
  payload_id bigint not null references payloads (id),
  enrichment_kind text not null,
  enrichment_id text not null,
  enrichment_revision text not null,
  recipe_git_commit text,
  recipe_path text,
  data jsonb not null,
  computed_at timestamptz not null default now(),
  primary key (addr, payload_id, enrichment_kind, enrichment_id, enrichment_revision)
);

create index adv_enrichments_enrichment_idx on adv_enrichments (enrichment_kind, enrichment_id);
create index adv_enrichments_data_idx on adv_enrichments using gin (data jsonb_path_ops);

-- WiGLE batch membership by sighting minute (one CSV row per minute row).
create table wigle_upload_batch_minutes (
  batch_id bigint not null references wigle_upload_batches (id) on delete cascade,
  minute_id bigint not null,
  minute timestamptz not null,
  primary key (batch_id, minute_id),
  foreign key (minute_id, minute) references sighting_minutes (id, minute) on delete restrict
);

create index wigle_upload_batch_minutes_minute_idx
  on wigle_upload_batch_minutes (minute_id, minute);

-- Analyst-facing per-(addr, payload) view, computed from sighting_minutes on
-- query. Filter by addr/payload (or join a small candidate set) rather than
-- scanning it whole.
create view adv_shapes as
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

-- migrate:down
-- Destroys everything written to the new tables; restores the old names.
drop view adv_shapes;
drop table wigle_upload_batch_minutes;
drop table adv_enrichments;
drop function ensure_sighting_partition (date);
drop table sighting_minutes;
drop function sighting_sample_better (float8, float4, timestamptz, float8, float4, timestamptz);
drop function sighting_sample_score (smallint, float4);
drop function jsonb_counts_keys (jsonb);
drop function jsonb_counts_merge (jsonb, jsonb);
drop index blobs_uploader_id_idx;
alter table blobs drop constraint blobs_id_key;
alter table blobs
  drop column uploader_id,
  drop column session_id,
  drop column id,
  add column uploader text;
drop table uploaders;
drop view payload_fields;
drop function payload_raw (bigint);
drop table payloads;
drop table ad_structures;
drop function ble_structure_uuids (smallint, bytea);
drop function ble_structure_bytes (smallint, bytea);
drop function ble_utf8_or_null (bytea);
drop function ble_adv_split (bytea);

alter table wigle_upload_batches rename column max_item_id to max_scan_id;
alter table wigle_upload_batches rename column min_item_id to min_scan_id;
alter index wigle_upload_batch_scans_old_scan_idx rename to wigle_upload_batch_scans_scan_idx;
alter index wigle_upload_batch_scans_old_pkey rename to wigle_upload_batch_scans_pkey;
alter table wigle_upload_batch_scans_old rename to wigle_upload_batch_scans;

alter index projection_state_old_pkey rename to projection_state_pkey;
alter table projection_state_old rename to projection_state;

alter table adv_enrichments_old rename to adv_enrichments;
alter index adv_enrichments_old_pkey rename to adv_enrichments_pkey;
alter index adv_enrichments_old_enrichment_idx rename to adv_enrichments_enrichment_idx;
alter index adv_enrichments_old_data_idx rename to adv_enrichments_data_idx;

alter table advs_old rename to advs;
alter index advs_old_pkey rename to advs_pkey;
alter index advs_old_last_seen_idx rename to advs_last_seen_idx;
alter index advs_old_scans_through_id_idx rename to advs_scans_through_id_idx;
alter index advs_old_adv_types_idx rename to advs_adv_types_idx;
alter index advs_old_manufacturer_ids_idx rename to advs_manufacturer_ids_idx;
alter index advs_old_service_uuids_idx rename to advs_service_uuids_idx;
alter index advs_old_service_data_uuids_idx rename to advs_service_data_uuids_idx;
alter index advs_old_centroid_idx rename to advs_centroid_idx;
alter index advs_old_bbox_idx rename to advs_bbox_idx;

alter table scans_old rename to scans;
alter index scans_old_pkey rename to scans_pkey;
alter index scans_old_location_idx rename to scans_location_idx;
alter index scans_old_addr_idx rename to scans_addr_idx;
alter index scans_old_scanned_at_idx rename to scans_scanned_at_idx;

create view adv_shapes as
select
  a.addr,
  a.raw,
  a.scans_through_id,
  a.first_seen,
  a.last_seen,
  a.scans_count,
  a.rssi_min,
  a.local_name,
  a.adv_types,
  a.manufacturer_ids,
  a.service_uuids,
  a.service_data_uuids,
  a.centroid,
  a.bbox,
  a.centroid_lat,
  a.centroid_lon,
  a.min_lat,
  a.max_lat,
  a.min_lon,
  a.max_lon,
  coalesce(s.ad_types_ordered, array[]::smallint[]) as ad_types_ordered,
  coalesce(s.ad_data_lengths, array[]::smallint[]) as ad_data_lengths
from advs a
cross join lateral (
  select
    array_agg((ad_struct.elem ->> 'type')::smallint order by ad_struct.ord) as ad_types_ordered,
    array_agg((length(ad_struct.elem ->> 'data') / 2)::smallint order by ad_struct.ord) as ad_data_lengths
  from jsonb_array_elements(parse_ble_adv(a.raw)) with ordinality as ad_struct(elem, ord)
) s;
