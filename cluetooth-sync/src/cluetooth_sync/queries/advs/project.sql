with state as materialized (
  select
    scans_through_id as previous_scans_through_id
  from
    projection_state
  where
    name = 'advs'
  for update
),
target as materialized (
  select
    coalesce(max(id), 0)::bigint as target_scans_through_id
  from
    scans
),
changed_keys as materialized (
  select distinct
    s.addr,
    s.raw
  from
    scans s
    cross join state
    cross join target
  where
    s.id > state.previous_scans_through_id
    and s.id <= target.target_scans_through_id
),
scan_groups as (
  select
    s.addr,
    s.raw,
    min(s.scanned_at) as first_seen,
    max(s.scanned_at) as last_seen,
    count(*)::bigint as scans_count,
    min(s.rssi)::smallint as rssi_min,
    (
      array_agg(
        s.local_name
        order by
          s.scanned_at desc,
          s.id desc
      ) filter (where s.local_name is not null)
    ) [1] as local_name,
    count(s.location)::bigint as location_count,
    st_centroid(
      st_collect(s.location) filter (where s.location is not null)
    )::geometry (Point, 4326) as centroid,
    case
      when count(s.location) = 0 then null
      else st_makeenvelope(
        min(st_x(s.location)) filter (where s.location is not null),
        min(st_y(s.location)) filter (where s.location is not null),
        max(st_x(s.location)) filter (where s.location is not null),
        max(st_y(s.location)) filter (where s.location is not null),
        4326
      )::geometry (Polygon, 4326)
    end as bbox
  from
    scans s
    join changed_keys k
      on k.addr = s.addr
     and k.raw = s.raw
  group by
    s.addr,
    s.raw
),
projected_advs as (
  select
    g.addr,
    g.raw,
    target.target_scans_through_id as scans_through_id,
    g.first_seen,
    g.last_seen,
    g.scans_count,
    g.rssi_min,
    g.location_count,
    g.centroid,
    g.bbox,
    g.local_name,
    ble_adv_types(g.raw) as adv_types,
    ble_adv_manufacturer_ids(g.raw) as manufacturer_ids,
    ble_adv_service_uuids(g.raw) as service_uuids,
    ble_adv_service_data_uuids(g.raw) as service_data_uuids
  from
    scan_groups g
    cross join target
),
upserted as (
  insert into advs (
    addr,
    raw,
    scans_through_id,
    first_seen,
    last_seen,
    scans_count,
    rssi_min,
    location_count,
    centroid,
    bbox,
    local_name,
    adv_types,
    manufacturer_ids,
    service_uuids,
    service_data_uuids
  )
  select
    addr,
    raw,
    scans_through_id,
    first_seen,
    last_seen,
    scans_count,
    rssi_min,
    location_count,
    centroid,
    bbox,
    local_name,
    adv_types,
    manufacturer_ids,
    service_uuids,
    service_data_uuids
  from
    projected_advs
  on conflict (addr, raw) do update set
    scans_through_id = excluded.scans_through_id,
    first_seen = excluded.first_seen,
    last_seen = excluded.last_seen,
    scans_count = excluded.scans_count,
    rssi_min = excluded.rssi_min,
    location_count = excluded.location_count,
    centroid = excluded.centroid,
    bbox = excluded.bbox,
    local_name = excluded.local_name,
    adv_types = excluded.adv_types,
    manufacturer_ids = excluded.manufacturer_ids,
    service_uuids = excluded.service_uuids,
    service_data_uuids = excluded.service_data_uuids
  returning 1
),
updated_advs as (
  select count(*) from upserted
)
update projection_state
set
  scans_through_id = target.target_scans_through_id,
  updated_at = now()
from
  target,
  state,
  updated_advs
where
  projection_state.name = 'advs'
