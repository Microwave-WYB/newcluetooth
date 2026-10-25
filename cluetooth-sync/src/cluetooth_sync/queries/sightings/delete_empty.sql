delete from sighting_minutes
where minute >= $1::timestamptz
  and minute < $2::timestamptz
  and sightings = 0
