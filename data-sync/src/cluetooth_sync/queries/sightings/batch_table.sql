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
  -- Producer payload v3 PDU fields; null for legacy and v2 sightings.
  is_legacy boolean,
  primary_phy smallint,
  secondary_phy smallint,
  adv_sid smallint,
  adv_tx_power smallint,
  periodic_interval integer,
  data_status smallint,
  addr_type smallint,
  -- Old scans.id, only set by the backfill (WiGLE membership mapping).
  scan_id bigint
) on commit drop
