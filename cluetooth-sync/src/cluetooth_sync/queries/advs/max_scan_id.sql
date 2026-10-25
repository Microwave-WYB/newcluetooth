select coalesce(max(id), 0)::bigint as max_scan_id
from scans
