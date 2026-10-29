-- Blobs whose sighting count differs between scans_old and minute provenance.
with old as (
  select b.id as blob_id, count(*) as n
  from scans_old s join blobs b on b.uri = s.blob
  group by b.id
),
new as (
  select c.key::bigint as blob_id, sum(c.value::bigint) as n
  from sighting_minutes m cross join jsonb_each_text(m.blob_counts) c
  group by c.key::bigint
)
select 0::bigint as old_value, count(*)::bigint as new_value
from old full join new using (blob_id)
where old.n is distinct from new.n
