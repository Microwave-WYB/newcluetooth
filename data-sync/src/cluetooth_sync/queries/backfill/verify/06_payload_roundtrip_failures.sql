-- Payloads whose structures + padding/tail do not rebuild the exact bytes.
select 0::bigint as old_value, count(*)::bigint as new_value
from payloads p
where sha256(payload_raw(p.id)) is distinct from p.sha256
