select
  (select count(*) from scans_old)::bigint as old_value,
  (select coalesce(sum(sightings), 0) from payloads)::bigint as new_value
