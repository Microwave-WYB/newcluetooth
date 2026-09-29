-- migrate:up
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

-- migrate:down
drop view adv_shapes;
