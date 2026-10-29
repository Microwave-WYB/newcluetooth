-- $1 discovery id. Staged attributes -> gatt_attributes (properties travel as a
-- comma-separated string, values as hex).
insert into gatt_attributes (
  discovery_id, attribute_id, parent_attribute_id, kind, uuid, is_primary, instance,
  properties, value, read_status
)
select
  $1::bigint, cast(a.attribute_id as integer), cast(a.parent_attribute_id as integer),
  a.kind, a.uuid, a.is_primary, cast(a.instance as integer),
  case when a.properties is null then null else string_to_array(a.properties, ',') end,
  decode(a.value_hex, 'hex'), a.read_status
from {{ stage }} a
order by a.attribute_id
