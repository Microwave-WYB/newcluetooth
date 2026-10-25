-- Creates or row-locks the ledger entry for one blob ingest transaction and
-- records producer identity. The row lock serializes concurrent sync processes
-- on the same blob; `success` tells the caller whether it is already ingested.
insert into blobs (uri, uploader_id, app_version, session_id)
values ($1, $2::integer, $3::text, $4::uuid)
on conflict (uri) do update
set uploader_id = coalesce(excluded.uploader_id, blobs.uploader_id),
    app_version = coalesce(excluded.app_version, blobs.app_version),
    session_id = coalesce(excluded.session_id, blobs.session_id)
returning id, success
