-- Up to 2000 random minute rows recomputed from scans_old, every column.
with sample as (
  select * from sighting_minutes order by random() limit 2000
),
recomputed as (
  select
    m.id,
    count(*) as sightings,
    min(s.scanned_at) as first_seen,
    max(s.scanned_at) as last_seen,
    min(s.rssi) as rssi_min,
    max(s.rssi) as rssi_max,
    coalesce(sum(s.rssi), 0) as rssi_sum,
    count(s.rssi) as rssi_count,
    min(s.lat) as min_lat,
    max(s.lat) as max_lat,
    min(s.lon) filter (where s.lat is not null) as min_lon,
    max(s.lon) filter (where s.lat is not null) as max_lon,
    max(s.tx_power) as tx_power,
    bool_or(s.is_connectable) as is_connectable
  from sample m
  join payloads p on p.id = m.payload_id
  join blobs b
    on b.uploader_id is not distinct from m.uploader_id
   and b.session_id is not distinct from m.session_id
  join scans_old s
    on s.blob = b.uri
   and s.addr = m.addr
   and sha256(s.raw) = p.sha256
   and s.scanned_at >= m.minute
   and s.scanned_at < m.minute + interval '1 minute'
  group by m.id
),
best as (
  select distinct on (m.id)
    m.id, s.scanned_at as best_seen, s.rssi as best_rssi, s.accuracy as best_accuracy,
    st_setsrid(st_makepoint(s.lon, s.lat), 4326)::geography as location
  from sample m
  join payloads p on p.id = m.payload_id
  join blobs b
    on b.uploader_id is not distinct from m.uploader_id
   and b.session_id is not distinct from m.session_id
  join scans_old s
    on s.blob = b.uri
   and s.addr = m.addr
   and sha256(s.raw) = p.sha256
   and s.scanned_at >= m.minute
   and s.scanned_at < m.minute + interval '1 minute'
  where s.lat is not null and s.lon is not null
  order by
    m.id,
    sighting_sample_score(s.rssi, s.accuracy) desc nulls last,
    greatest(coalesce(s.accuracy, 50), 1),
    s.scanned_at
)
select 0::bigint as old_value, count(*)::bigint as new_value
from sample m
left join recomputed r on r.id = m.id
left join best x on x.id = m.id
where r.id is null
   or (m.sightings, m.first_seen, m.last_seen, m.rssi_min, m.rssi_max, m.rssi_sum, m.rssi_count,
       m.min_lat, m.max_lat, m.min_lon, m.max_lon, m.tx_power, m.is_connectable)
      is distinct from
      (r.sightings, r.first_seen, r.last_seen, r.rssi_min, r.rssi_max, r.rssi_sum, r.rssi_count,
       r.min_lat, r.max_lat, r.min_lon, r.max_lon, r.tx_power, r.is_connectable)
   or (m.best_seen, m.best_rssi, m.best_accuracy) is distinct from (x.best_seen, x.best_rssi, x.best_accuracy)
   or not coalesce(st_equals(m.location::geometry, x.location::geometry), m.location is null and x.location is null)
