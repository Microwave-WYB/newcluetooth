insert into backfill_progress (phase)
values ($1)
on conflict (phase) do update set phase = excluded.phase
returning through_id, rows, done
