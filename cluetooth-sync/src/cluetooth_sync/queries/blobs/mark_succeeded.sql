update blobs
set synced_at = now(),
    success = true,
    error = null
where uri = $1
