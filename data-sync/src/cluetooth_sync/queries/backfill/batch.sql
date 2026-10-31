-- scans_old ids in ($1, $2] as normalized sightings (scan_id kept for WiGLE).
insert into {{ batch }} (
  scanned_at, addr, payload_id, uploader_id, session_id, blob_id,
  rssi, tx_power, is_connectable, lat, lon, accuracy, scan_id
)
select
  s.scanned_at, s.addr, p.id, b.uploader_id, b.session_id, b.id,
  s.rssi, s.tx_power, s.is_connectable, s.lat, s.lon, s.accuracy, s.id
from scans_old s
join blobs b on b.uri = s.blob
join payloads p on p.sha256 = sha256(s.raw)
where s.id > $1 and s.id <= $2
