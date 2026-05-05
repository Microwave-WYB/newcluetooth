select uri
from blobs
where success is true
  and left(uri, length($1)) = $1
