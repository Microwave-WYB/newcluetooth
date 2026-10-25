-- old: scans_old rows; new: sightings in sighting_minutes.
select
  (select count(*) from scans_old)::bigint as old_value,
  (select coalesce(sum(sightings), 0) from sighting_minutes)::bigint as new_value
