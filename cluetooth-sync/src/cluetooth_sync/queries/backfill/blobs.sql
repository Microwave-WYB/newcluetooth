-- Producer identity for legacy blobs from `<timestamp>_<uploader>_<version>.jsonl...`
-- (same rule as cluetooth_sync.pipeline.legacy_adapter.legacy_blob_identity).
with parsed as (
  select
    uri,
    m[1] as uploader,
    m[2] as app_version
  from blobs
  cross join lateral regexp_match(
    regexp_replace(uri, '^.*/', ''),
    '^[^_/]+_(.+)_([0-9]+(?:\.[0-9]+){2}(?:[-+][A-Za-z0-9_.-]+)?)\.jsonl(?:\.zst)?(?:\.encrypted)?$'
  ) as m
  where uploader_id is null
    and m[2] ~ '^0\.0\.[1-4]([-+].*)?$'
),
new_uploaders as (
  insert into uploaders (uploader)
  select distinct uploader from parsed order by uploader
  on conflict (uploader) do nothing
  returning id, uploader
),
all_uploaders as (
  select id, uploader from new_uploaders
  union all
  select u.id, u.uploader from uploaders u join parsed p using (uploader)
)
update blobs b
set
  uploader_id = (select min(a.id) from all_uploaders a where a.uploader = p.uploader),
  app_version = coalesce(b.app_version, p.app_version)
from parsed p
where b.uri = p.uri
