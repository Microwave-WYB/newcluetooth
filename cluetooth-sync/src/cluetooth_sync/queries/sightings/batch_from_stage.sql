-- $1 blob id, $2 uploader id (-1 for none: ADBC cannot bind a null parameter);
-- session_id per row from the stage.
insert into {{ batch }} (
  scanned_at, addr, payload_id, uploader_id, session_id, blob_id,
  rssi, tx_power, is_connectable, lat, lon, accuracy
)
select
  s.scanned_at,
  cast(s.addr as macaddr),
  p.id,
  nullif($2, -1)::integer,
  cast(s.session_id as uuid),
  $1::bigint,
  cast(s.rssi as smallint),
  cast(s.tx_power as smallint),
  s.is_connectable,
  s.lat,
  s.lon,
  s.accuracy
from {{ stage }} s
join payloads p
  on p.sha256 = sha256(decode(s.raw, 'hex'))
