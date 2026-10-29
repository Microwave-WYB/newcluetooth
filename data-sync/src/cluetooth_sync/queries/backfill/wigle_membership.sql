-- Minute rows holding scans that were already in a WiGLE batch join that batch,
-- so they are never uploaded again.
insert into wigle_upload_batch_minutes (batch_id, minute_id, minute)
select distinct ws.batch_id, m.id, m.minute
from {{ batch }} x
join wigle_upload_batch_scans_old ws on ws.scan_id = x.scan_id
join sighting_minutes m
  on m.minute = date_trunc('minute', x.scanned_at, 'UTC')
 and m.addr = x.addr
 and m.payload_id = x.payload_id
 and m.uploader_id is not distinct from x.uploader_id
 and m.session_id is not distinct from x.session_id
on conflict do nothing
