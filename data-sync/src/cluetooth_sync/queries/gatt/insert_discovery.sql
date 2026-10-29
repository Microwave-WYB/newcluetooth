-- $1 blob id. One staged discovery -> gatt_discoveries; the trigger
-- advertisement is looked up in payloads (stored just before).
insert into gatt_discoveries (
  blob_id, discovery_uuid, session_id, app_version, device_model, android_version,
  addr, addr_type,
  trigger_payload_id, trigger_rssi, trigger_scanned_at, trigger_is_legacy,
  trigger_primary_phy, trigger_secondary_phy, trigger_adv_sid,
  started_at, ended_at, lat, lon, accuracy,
  outcome, stage_reached, error_stage, error_status, error_name,
  disconnect_initiator, disconnect_status, mtu, phy_tx, phy_rx,
  connect_ms, mtu_ms, discover_ms, read_ms, profile_hash, raw
)
select
  $1::bigint, cast(s.discovery_uuid as uuid), cast(s.session_id as uuid),
  s.app_version, s.device_model, s.android_version,
  cast(s.addr as macaddr), s.addr_type,
  (select p.id from payloads p where p.sha256 = sha256(decode(s.trigger_raw, 'hex'))),
  cast(s.trigger_rssi as smallint), s.trigger_scanned_at, s.trigger_is_legacy,
  cast(s.trigger_primary_phy as smallint), cast(s.trigger_secondary_phy as smallint),
  cast(s.trigger_adv_sid as smallint),
  s.started_at, s.ended_at, s.lat, s.lon, s.accuracy,
  s.outcome, s.stage_reached, s.error_stage, cast(s.error_status as integer), s.error_name,
  s.disconnect_initiator, cast(s.disconnect_status as integer),
  cast(s.mtu as smallint), cast(s.phy_tx as smallint), cast(s.phy_rx as smallint),
  cast(s.connect_ms as integer), cast(s.mtu_ms as integer),
  cast(s.discover_ms as integer), cast(s.read_ms as integer),
  s.profile_hash, cast(s.raw_json as jsonb)
from {{ stage }} s
returning id
