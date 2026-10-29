select b.id, b.uploader_id
from blobs b
where b.uri = $1
