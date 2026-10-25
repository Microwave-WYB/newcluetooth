select 0::bigint as old_value, count(*)::bigint as new_value
from blobs
where uploader_id is null
  and uri ~ '_0\.0\.[1-4]([-+][A-Za-z0-9_.-]+)?\.jsonl\.zst\.encrypted$'
