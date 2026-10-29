-- Old built-in ad_structures enrichments equal the structures now stored.
select 0::bigint as old_value, count(*)::bigint as new_value
from adv_enrichments_old e
join payloads p on p.sha256 = sha256(e.raw)
cross join lateral (
  select coalesce(
    jsonb_agg(jsonb_build_object('type', s.ad_type, 'data', encode(s.data, 'hex')) order by u.ord),
    '[]'::jsonb
  ) as data
  from unnest(p.structure_ids) with ordinality as u(structure_id, ord)
  join ad_structures s on s.id = u.structure_id
) now
where e.enrichment_kind = 'builtin'
  and e.enrichment_id = 'ad_structures'
  and e.data is distinct from now.data
