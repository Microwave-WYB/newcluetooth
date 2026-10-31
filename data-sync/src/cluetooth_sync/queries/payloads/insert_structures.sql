-- Adds unseen AD structures of {{ raws }} (each distinct structure is stored
-- once). Sorted so concurrent ingests sharing new structures wait on each
-- other instead of deadlocking.
insert into ad_structures (ad_type, data)
select distinct s.ad_type, s.data
from {{ raws }} r
cross join lateral ble_adv_split(r.raw) s
where s.ad_type is not null
  and not exists (select 1 from payloads p where p.sha256 = sha256(r.raw))
order by s.ad_type, s.data
on conflict (ad_type, data) do nothing
