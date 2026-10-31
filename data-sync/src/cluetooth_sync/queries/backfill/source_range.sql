select
  min(scanned_at)::date as first_day,
  max(scanned_at)::date as last_day,
  coalesce(max(id), 0)::bigint as max_id,
  count(*)::bigint as row_count
from scans_old
