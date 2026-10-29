-- $1 blob id, $2 uploader id (-1 for none: ADBC cannot bind a null parameter);
-- session_id per row from the stage.
insert into {{ batch }} (
  scanned_at, addr, payload_id, uploader_id, session_id, blob_id,
  rssi, tx_power, is_connectable, lat, lon, accuracy,
  is_legacy, primary_phy, secondary_phy, adv_sid, adv_tx_power, periodic_interval,
  data_status, addr_type
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
  s.accuracy,
  s.is_legacy,
  cast(s.primary_phy as smallint),
  cast(s.secondary_phy as smallint),
  cast(s.adv_sid as smallint),
  cast(s.adv_tx_power as smallint),
  cast(s.periodic_interval as integer),
  cast(s.data_status as smallint),
  cast(s.addr_type as smallint)
from {{ stage }} s
join payloads p
  on p.sha256 = sha256(decode(s.raw, 'hex'))
