insert into {{ raws }} (raw)
select distinct raw
from scans_old
where id > $1 and id <= $2
