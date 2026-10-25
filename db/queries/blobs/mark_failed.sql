-- Failed blobs stay pending; each consecutive failure doubles the retry delay
-- (1 minute, 2, 4, ...) up to 1 hour.
insert into blobs (uri, synced_at, success, error, attempts, next_attempt_at)
values ($1, now(), false, $2, 1, now() + interval '1 minute')
on conflict (uri) do update
set synced_at = excluded.synced_at,
    success = excluded.success,
    error = excluded.error,
    attempts = blobs.attempts + 1,
    next_attempt_at = now() + least(
      interval '1 minute' * power(2, least(blobs.attempts, 16)),
      interval '1 hour'
    )
