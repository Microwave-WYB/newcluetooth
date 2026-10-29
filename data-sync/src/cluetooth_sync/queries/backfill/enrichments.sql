-- Non-built-in enrichments carry over; built-in AD structures are superseded
-- by ad_structures (checked by verify/builtin_enrichments.sql).
insert into adv_enrichments (
  addr, payload_id, enrichment_kind, enrichment_id, enrichment_revision,
  recipe_git_commit, recipe_path, data, computed_at
)
select
  e.addr, p.id, e.enrichment_kind, e.enrichment_id, e.enrichment_revision,
  e.recipe_git_commit, e.recipe_path, e.data, e.computed_at
from adv_enrichments_old e
join payloads p on p.sha256 = sha256(e.raw)
where not (e.enrichment_kind = 'builtin' and e.enrichment_id = 'ad_structures')
on conflict do nothing
