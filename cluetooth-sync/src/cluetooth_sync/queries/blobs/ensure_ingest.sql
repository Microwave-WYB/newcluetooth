-- Creates or row-locks the ledger entry for one blob ingest transaction and
-- records producer identity. The row lock serializes concurrent sync processes
-- on the same blob; `success` tells the caller whether it is already ingested.
-- The ADBC driver cannot bind a null parameter, so an absent value is sent as
-- -1 (uploader) or '' (app version, session) and nullif restores the null.
insert into blobs (uri, uploader_id, app_version, session_id)
values ($1, nullif($2, -1)::integer, nullif($3, '')::text, nullif($4, '')::uuid)
on conflict (uri) do update
set uploader_id = coalesce(excluded.uploader_id, blobs.uploader_id),
    app_version = coalesce(excluded.app_version, blobs.app_version),
    session_id = coalesce(excluded.session_id, blobs.session_id)
returning id, success
