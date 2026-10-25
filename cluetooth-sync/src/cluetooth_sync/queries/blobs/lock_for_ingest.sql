-- Serializes concurrent sync processes on one blob; a blob another process
-- already committed must not be inserted twice.
select success
from blobs
where uri = $1
for update
