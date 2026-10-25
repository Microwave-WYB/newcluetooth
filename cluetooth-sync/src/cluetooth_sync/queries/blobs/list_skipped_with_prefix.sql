-- Blobs discovery must not yield: already succeeded, or failed and still
-- inside their retry backoff window.
select uri
from blobs
where (success is true or next_attempt_at > now())
  and left(uri, length($1)) = $1
