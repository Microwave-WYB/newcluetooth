-- Old WiGLE scan memberships whose minute row is not in the same batch.
select 0::bigint as old_value, count(*)::bigint as new_value
from wigle_upload_batch_scans_old ws
join scans_old s on s.id = ws.scan_id
join blobs b on b.uri = s.blob
join payloads p on p.sha256 = sha256(s.raw)
left join sighting_minutes m
  on m.minute = date_trunc('minute', s.scanned_at, 'UTC')
 and m.addr = s.addr
 and m.payload_id = p.id
 and m.uploader_id is not distinct from b.uploader_id
 and m.session_id is not distinct from b.session_id
left join wigle_upload_batch_minutes bm
  on bm.batch_id = ws.batch_id
 and bm.minute_id = m.id
where bm.minute_id is null
