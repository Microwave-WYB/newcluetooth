-- Drops minute rows a rebuild left empty, except rows a WiGLE batch already
-- claims: those keep their id (the foreign key is on delete restrict).
delete from sighting_minutes m
where m.minute >= $1::timestamptz
  and m.minute < $2::timestamptz
  and m.sightings = 0
  and not exists (
    select 1
    from wigle_upload_batch_minutes bm
    where bm.minute_id = m.id
      and bm.minute = m.minute
  )
