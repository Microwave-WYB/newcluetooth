insert into adv_enrichments (
  addr,
  raw,
  enrichment_kind,
  enrichment_id,
  enrichment_revision,
  data
)
select
  s.addr,
  s.raw,
  'builtin',
  'ad_structures',
  '20260416210856_init',
  parse_ble_adv(s.raw)
from (
  select distinct
    addr,
    raw
  from scans
) s
where not exists (
  select 1
  from adv_enrichments e
  where e.addr = s.addr
    and e.raw = s.raw
    and e.enrichment_kind = 'builtin'
    and e.enrichment_id = 'ad_structures'
    and e.enrichment_revision = '20260416210856_init'
)
on conflict (addr, raw, enrichment_kind, enrichment_id, enrichment_revision) do nothing;
