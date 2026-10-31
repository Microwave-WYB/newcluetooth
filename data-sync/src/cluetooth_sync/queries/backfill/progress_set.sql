update backfill_progress
set through_id = $2, rows = rows + $3, done = $4, updated_at = now()
where phase = $1
