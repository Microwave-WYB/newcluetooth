-- Zeroes the minutes in [$1, $2) (optionally of one uploader, $3 = '' for all)
-- in place, keeping row ids (WiGLE membership) for rows that get re-merged.
update sighting_minutes m
set
  blob_counts = '{}'::jsonb,
  sightings = 0,
  first_seen = null,
  last_seen = null,
  rssi_min = null,
  rssi_max = null,
  rssi_sum = 0,
  rssi_count = 0,
  location = null,
  best_rssi = null,
  best_accuracy = null,
  best_score = null,
  best_seen = null,
  min_lat = null,
  max_lat = null,
  min_lon = null,
  max_lon = null,
  tx_power = null,
  is_connectable = null,
  adv_tx_power = null,
  periodic_interval = null
where m.minute >= $1::timestamptz
  and m.minute < $2::timestamptz
  and (
    $3::text = ''
    or m.uploader_id = (select id from uploaders where uploader = $3::text)
  )
