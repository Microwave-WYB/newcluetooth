-- Distinct (minute, addr, payload, uploader, session) keys in scans_old.
select
  (
    select count(*)
    from (
      select distinct date_trunc('minute', s.scanned_at, 'UTC'), s.addr, sha256(s.raw), b.uploader_id, b.session_id
      from scans_old s join blobs b on b.uri = s.blob
    ) k
  )::bigint as old_value,
  (select count(*) from sighting_minutes)::bigint as new_value
