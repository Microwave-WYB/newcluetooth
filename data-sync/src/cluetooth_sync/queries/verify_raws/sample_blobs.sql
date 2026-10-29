-- Sampled blobs for the pre-migration archive check. `random()` over the small
-- ledger table keeps this cheap; the caller replays each object.
select uri
from blobs
where success is true
order by random()
limit $1
