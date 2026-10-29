-- Blobs that contributed to minutes in [$1, $2), optionally of one uploader
-- ($3 = '' for all uploaders).
select distinct b.uri
from sighting_minutes m
cross join unnest(m.blob_ids) as contributed(blob_id)
join blobs b on b.id = contributed.blob_id
left join uploaders u on u.id = m.uploader_id
where m.minute >= $1::timestamptz
  and m.minute < $2::timestamptz
  and ($3::text = '' or u.uploader = $3::text)
order by b.uri
