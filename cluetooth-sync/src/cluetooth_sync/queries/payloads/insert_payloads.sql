-- Adds unseen payloads of {{ raws }} as sha256 + ordered structure ids plus the
-- remainder after the last structure: all-zero padding as a length, anything
-- else verbatim in `tail`. payload_raw(id) reverses this exactly.
insert into payloads (sha256, structure_ids, zero_padding, tail)
select
  sha256(r.raw),
  coalesce(
    array_agg(a.id order by s.ord) filter (where s.ad_type is not null),
    array[]::bigint[]
  ),
  coalesce(
    max(octet_length(s.data)) filter (
      where s.ad_type is null and rtrim(s.data, '\x00'::bytea) = ''::bytea
    ),
    0
  ),
  max(s.data) filter (
    where s.ad_type is null and rtrim(s.data, '\x00'::bytea) <> ''::bytea
  )
from {{ raws }} r
cross join lateral ble_adv_split(r.raw) s
left join ad_structures a
  on a.ad_type = s.ad_type
 and a.data = s.data
where not exists (select 1 from payloads p where p.sha256 = sha256(r.raw))
group by r.raw
order by sha256(r.raw)
on conflict (sha256) do nothing
