insert into blobs (uri, synced_at, success, error)
values ($1, now(), false, $2)
on conflict (uri) do update
set synced_at = excluded.synced_at,
    success = excluded.success,
    error = excluded.error
