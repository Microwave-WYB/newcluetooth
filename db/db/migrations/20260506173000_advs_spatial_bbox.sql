-- migrate:up
alter table advs
  add column centroid geometry (Point, 4326),
  add column bbox geometry (Polygon, 4326);

with spatial_advs as (
  select
    addr,
    raw,
    st_centroid(
      st_collect(location) filter (where location is not null)
    )::geometry (Point, 4326) as centroid,
    case
      when count(location) = 0 then null
      else st_makeenvelope(
        min(st_x(location)) filter (where location is not null),
        min(st_y(location)) filter (where location is not null),
        max(st_x(location)) filter (where location is not null),
        max(st_y(location)) filter (where location is not null),
        4326
      )::geometry (Polygon, 4326)
    end as bbox
  from
    scans
  group by
    addr,
    raw
)
update advs a
set
  centroid = s.centroid,
  bbox = s.bbox
from
  spatial_advs s
where
  s.addr = a.addr
  and s.raw = a.raw;

alter table advs
  drop column centroid_lat,
  drop column centroid_lon,
  drop column radius,
  add column centroid_lat float8 generated always as (st_y(centroid)) stored,
  add column centroid_lon float8 generated always as (st_x(centroid)) stored,
  add column min_lat float8 generated always as (st_ymin(box3d(bbox))) stored,
  add column max_lat float8 generated always as (st_ymax(box3d(bbox))) stored,
  add column min_lon float8 generated always as (st_xmin(box3d(bbox))) stored,
  add column max_lon float8 generated always as (st_xmax(box3d(bbox))) stored;

create index advs_centroid_idx on advs using gist (centroid);

create index advs_bbox_idx on advs using gist (bbox);

-- migrate:down
drop index advs_bbox_idx;

drop index advs_centroid_idx;

alter table advs
  drop column min_lat,
  drop column max_lat,
  drop column min_lon,
  drop column max_lon,
  drop column centroid_lat,
  drop column centroid_lon,
  add column centroid_lat float8,
  add column centroid_lon float8,
  add column radius float8;

update advs
set
  centroid_lat = st_y(centroid),
  centroid_lon = st_x(centroid);

alter table advs
  drop column bbox,
  drop column centroid;
