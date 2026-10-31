update blobs
set synced_at = now(),
    success = true,
    error = null,
    next_attempt_at = null
where uri = $1
