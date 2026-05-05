-- {{ staging_table }} is replaced by the caller with a quoted temporary table name.
insert into scans (
  blob,
  addr,
  rssi,
  scanned_at,
  raw,
  local_name,
  tx_power,
  is_connectable,
  lat,
  lon,
  accuracy
)
select
  blob,
  cast(addr as macaddr),
  cast(rssi as smallint),
  scanned_at,
  decode(raw, 'hex'),
  local_name,
  cast(tx_power as smallint),
  is_connectable,
  lat,
  lon,
  accuracy
from {{ staging_table }}
