-- Folds {{ batch }} into payload stats. Rows are locked in id order first so
-- concurrent ingests touching the same hot payloads cannot deadlock.
with delta as (
  select payload_id, min(scanned_at) as first_seen, max(scanned_at) as last_seen, count(*) as n
  from {{ batch }}
  group by payload_id
),
locked as (
  select p.id
  from payloads p
  join delta d on d.payload_id = p.id
  order by p.id
  for no key update of p
)
update payloads p
set
  first_seen = least(p.first_seen, d.first_seen),
  last_seen = greatest(p.last_seen, d.last_seen),
  sightings = p.sightings + d.n
from delta d
where p.id = d.payload_id
  and p.id in (select id from locked)
