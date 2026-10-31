select count(*)::bigint as row_count
from scans_old
where id > $1 and id <= $2
