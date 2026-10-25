-- Informational: scans_old rows whose stored local_name differs from the name
-- in their raw advertisement (e.g. masked names). Not stored per row any more.
select null::bigint as old_value, count(*)::bigint as new_value
from scans_old s
join payload_fields f on f.sha256 = sha256(s.raw)
where s.local_name is distinct from f.local_name
