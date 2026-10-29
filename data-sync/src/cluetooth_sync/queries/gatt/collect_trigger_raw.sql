-- The trigger advertisement of one staged discovery (none if it has no trigger).
insert into {{ raws }} (raw)
select decode(trigger_raw, 'hex')
from {{ stage }}
where trigger_raw is not null
