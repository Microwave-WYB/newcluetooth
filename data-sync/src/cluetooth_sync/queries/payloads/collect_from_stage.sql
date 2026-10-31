-- Distinct raw advertisements of one staged blob.
insert into {{ raws }} (raw)
select distinct decode(raw, 'hex')
from {{ stage }}
