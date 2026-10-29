with old as (select addr, count(*) as n from scans_old group by addr),
new as (select addr, sum(sightings) as n from sighting_minutes group by addr)
select 0::bigint as old_value, count(*)::bigint as new_value
from old full join new using (addr)
where old.n is distinct from new.n
