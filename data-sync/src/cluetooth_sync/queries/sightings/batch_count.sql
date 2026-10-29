select count(*)::bigint as row_count
from {{ batch }}
