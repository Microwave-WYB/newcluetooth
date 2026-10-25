-- $1 blob URI. Counts the sightings a replayed object holds, the per-sighting
-- rows `scans_old` holds for that blob, and the (addr, scanned_at, raw) keys
-- whose multiplicity differs. Raw bytes are the compared identity: a
-- convenience column such as `local_name` may have been modified in place.
with archived as (
  select
    cast(addr as macaddr) as addr,
    scanned_at,
    decode(raw, 'hex') as raw,
    count(*) as n
  from {{ stage }}
  group by 1, 2, 3
),
old as (
  select addr, scanned_at, raw, count(*) as n
  from scans_old
  where blob = $1
  group by 1, 2, 3
)
select
  (select coalesce(sum(n), 0) from archived)::bigint as archived,
  (select coalesce(sum(n), 0) from old)::bigint as old,
  (
    select count(*)
    from old
    full join archived using (addr, scanned_at, raw)
    where old.n is distinct from archived.n
  )::bigint as mismatched
