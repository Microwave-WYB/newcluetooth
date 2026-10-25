-- Normalized sightings of one ingest (or backfill) batch.
create temporary table {{ batch }} (
  scanned_at timestamptz not null,
  addr macaddr not null,
  payload_id bigint not null,
  uploader_id integer,
  session_id uuid,
  blob_id bigint not null,
  rssi smallint,
  tx_power smallint,
  is_connectable boolean,
  lat float8,
  lon float8,
  accuracy float4,
  -- Old scans.id, only set by the backfill (WiGLE membership mapping).
  scan_id bigint
) on commit drop
