-- Upsert-merges the sightings of {{ batch }} into sighting_minutes. Every
-- column is a sum, count, min/max or the best sample under
-- sighting_sample_better, so merging a minute in parts equals one pass. Keys
-- are written in sorted order to avoid deadlocks.
-- The key is (minute, addr, payload, uploader, session) plus the v3 PDU fields
-- addr_type, is_legacy, primary_phy, secondary_phy, adv_sid and data_status
-- (all null for legacy and v2 sightings); nulls compare equal. The joins keep plain
-- equality on minute, addr and payload_id (hashable) and use IS NOT DISTINCT FROM only
-- on the nullable key columns: one row-wise comparison forces nested loops.
with fixes as (
  select
    date_trunc('minute', b.scanned_at, 'UTC') as minute,
    b.*,
    sighting_sample_score(b.rssi, b.accuracy) as score
  from {{ batch }} b
),
-- Best located sample per key: the ordering sighting_sample_better encodes.
best as (
  select distinct on (minute, addr, payload_id, uploader_id, session_id,
    addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status)
    minute, addr, payload_id, uploader_id, session_id,
    addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status,
    st_setsrid(st_makepoint(lon, lat), 4326)::geography as location,
    rssi as best_rssi,
    accuracy as best_accuracy,
    score as best_score,
    scanned_at as best_seen
  from fixes
  where lat is not null and lon is not null
  order by
    minute, addr, payload_id, uploader_id, session_id,
    addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status,
    score desc nulls last,
    greatest(coalesce(accuracy, 50), 1),
    scanned_at
),
per_blob as (
  select minute, addr, payload_id, uploader_id, session_id,
    addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status, blob_id, count(*) as n
  from fixes
  group by minute, addr, payload_id, uploader_id, session_id,
    addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status, blob_id
),
blob_maps as (
  select
    minute, addr, payload_id, uploader_id, session_id,
    addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status,
    jsonb_object_agg(blob_id::text, n) as blob_counts
  from per_blob
  group by minute, addr, payload_id, uploader_id, session_id,
    addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status
),
per_key as (
  select
    minute, addr, payload_id, uploader_id, session_id,
    addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status,
    count(*) as sightings,
    min(scanned_at) as first_seen,
    max(scanned_at) as last_seen,
    min(rssi) as rssi_min,
    max(rssi) as rssi_max,
    coalesce(sum(rssi), 0) as rssi_sum,
    count(rssi) as rssi_count,
    min(lat) as min_lat,
    max(lat) as max_lat,
    min(lon) filter (where lat is not null) as min_lon,
    max(lon) filter (where lat is not null) as max_lon,
    max(tx_power) as tx_power,
    bool_or(is_connectable) as is_connectable,
    max(adv_tx_power) as adv_tx_power,
    max(periodic_interval) as periodic_interval
  from fixes
  group by minute, addr, payload_id, uploader_id, session_id,
    addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status
)
insert into sighting_minutes as m (
  minute, addr, payload_id, uploader_id, session_id,
  addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status,
  blob_counts,
  sightings, first_seen, last_seen, rssi_min, rssi_max, rssi_sum, rssi_count,
  location, best_rssi, best_accuracy, best_score, best_seen,
  min_lat, max_lat, min_lon, max_lon, tx_power, is_connectable,
  adv_tx_power, periodic_interval
)
select
  k.minute, k.addr, k.payload_id, k.uploader_id, k.session_id,
  k.addr_type, k.is_legacy, k.primary_phy, k.secondary_phy, k.adv_sid, k.data_status,
  b.blob_counts,
  k.sightings, k.first_seen, k.last_seen, k.rssi_min, k.rssi_max, k.rssi_sum, k.rssi_count,
  x.location, x.best_rssi, x.best_accuracy, x.best_score, x.best_seen,
  k.min_lat, k.max_lat, k.min_lon, k.max_lon, k.tx_power, k.is_connectable,
  k.adv_tx_power, k.periodic_interval
from per_key k
join blob_maps b
  on b.minute = k.minute
 and b.addr = k.addr
 and b.payload_id = k.payload_id
 and b.uploader_id is not distinct from k.uploader_id
 and b.session_id is not distinct from k.session_id
 and b.addr_type is not distinct from k.addr_type
 and b.is_legacy is not distinct from k.is_legacy
 and b.primary_phy is not distinct from k.primary_phy
 and b.secondary_phy is not distinct from k.secondary_phy
 and b.adv_sid is not distinct from k.adv_sid
 and b.data_status is not distinct from k.data_status
left join best x
  on x.minute = k.minute
 and x.addr = k.addr
 and x.payload_id = k.payload_id
 and x.uploader_id is not distinct from k.uploader_id
 and x.session_id is not distinct from k.session_id
 and x.addr_type is not distinct from k.addr_type
 and x.is_legacy is not distinct from k.is_legacy
 and x.primary_phy is not distinct from k.primary_phy
 and x.secondary_phy is not distinct from k.secondary_phy
 and x.adv_sid is not distinct from k.adv_sid
 and x.data_status is not distinct from k.data_status
order by k.minute, k.addr, k.payload_id, k.uploader_id, k.session_id,
  k.addr_type, k.is_legacy, k.primary_phy, k.secondary_phy, k.adv_sid, k.data_status
on conflict on constraint sighting_minutes_key do update set
  blob_counts = jsonb_counts_merge(m.blob_counts, excluded.blob_counts),
  sightings = m.sightings + excluded.sightings,
  first_seen = least(m.first_seen, excluded.first_seen),
  last_seen = greatest(m.last_seen, excluded.last_seen),
  rssi_min = least(m.rssi_min, excluded.rssi_min),
  rssi_max = greatest(m.rssi_max, excluded.rssi_max),
  rssi_sum = m.rssi_sum + excluded.rssi_sum,
  rssi_count = m.rssi_count + excluded.rssi_count,
  location = case when sighting_sample_better(
      excluded.best_score, excluded.best_accuracy, excluded.best_seen,
      m.best_score, m.best_accuracy, m.best_seen
    ) then excluded.location else m.location end,
  best_rssi = case when sighting_sample_better(
      excluded.best_score, excluded.best_accuracy, excluded.best_seen,
      m.best_score, m.best_accuracy, m.best_seen
    ) then excluded.best_rssi else m.best_rssi end,
  best_accuracy = case when sighting_sample_better(
      excluded.best_score, excluded.best_accuracy, excluded.best_seen,
      m.best_score, m.best_accuracy, m.best_seen
    ) then excluded.best_accuracy else m.best_accuracy end,
  best_score = case when sighting_sample_better(
      excluded.best_score, excluded.best_accuracy, excluded.best_seen,
      m.best_score, m.best_accuracy, m.best_seen
    ) then excluded.best_score else m.best_score end,
  best_seen = case when sighting_sample_better(
      excluded.best_score, excluded.best_accuracy, excluded.best_seen,
      m.best_score, m.best_accuracy, m.best_seen
    ) then excluded.best_seen else m.best_seen end,
  min_lat = least(m.min_lat, excluded.min_lat),
  max_lat = greatest(m.max_lat, excluded.max_lat),
  min_lon = least(m.min_lon, excluded.min_lon),
  max_lon = greatest(m.max_lon, excluded.max_lon),
  tx_power = greatest(m.tx_power, excluded.tx_power),
  is_connectable = coalesce(m.is_connectable or excluded.is_connectable, m.is_connectable, excluded.is_connectable),
  adv_tx_power = greatest(m.adv_tx_power, excluded.adv_tx_power),
  periodic_interval = greatest(m.periodic_interval, excluded.periodic_interval)
