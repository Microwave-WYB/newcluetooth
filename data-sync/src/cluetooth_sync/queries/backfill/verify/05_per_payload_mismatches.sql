-- Per exact payload: sightings, first and last seen, against both the minute
-- rows and the payload stats.
with old as (
  select sha256(raw) as sha256, count(*) as n, min(scanned_at) as first_seen, max(scanned_at) as last_seen
  from scans_old
  group by sha256(raw)
),
minutes as (
  select payload_id, sum(sightings) as n
  from sighting_minutes
  group by payload_id
)
select 0::bigint as old_value, count(*)::bigint as new_value
from old
full join payloads p using (sha256)
left join minutes m on m.payload_id = p.id
where old.n is distinct from m.n
   or old.n is distinct from p.sightings
   or old.first_seen is distinct from p.first_seen
   or old.last_seen is distinct from p.last_seen
