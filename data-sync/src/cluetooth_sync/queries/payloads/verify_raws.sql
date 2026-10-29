-- Raws of {{ raws }} whose payload does not rebuild to the exact bytes.
select count(*)::bigint as mismatched
from {{ raws }} r
left join payloads p on p.sha256 = sha256(r.raw)
where p.id is null
   or payload_raw(p.id) is distinct from r.raw
