-- migrate:up
-- NOT VALID preserves deployability if historical legacy rows predate the strict
-- coordinate contract; PostgreSQL still enforces each constraint for new writes.
-- PostgreSQL sorts NaN above Infinity, so the strict upper accuracy bound rejects
-- both NaN and positive Infinity (and the nonnegative bound rejects -Infinity).
alter table scans
  add constraint scans_coordinates_paired
    check ((lat is null) = (lon is null)) not valid,
  add constraint scans_lat_range
    check (lat is null or lat between -90 and 90) not valid,
  add constraint scans_lon_range
    check (lon is null or lon between -180 and 180) not valid,
  add constraint scans_accuracy_semantics
    check (
      (accuracy is null or lat is not null)
      and (accuracy is null or (accuracy >= 0 and accuracy < 'Infinity'::float4))
    ) not valid;

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

-- migrate:down
alter table gatt_discoveries
  drop constraint if exists gatt_discoveries_accuracy_semantics,
  drop constraint if exists gatt_discoveries_lon_range,
  drop constraint if exists gatt_discoveries_lat_range,
  drop constraint if exists gatt_discoveries_coordinates_paired;

alter table scans
  drop constraint if exists scans_accuracy_semantics,
  drop constraint if exists scans_lon_range,
  drop constraint if exists scans_lat_range,
  drop constraint if exists scans_coordinates_paired;
