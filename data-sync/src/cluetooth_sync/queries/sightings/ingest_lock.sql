-- Held shared by every ingest transaction; `cluetooth rebuild` takes it
-- exclusively so no blob commits into minutes it is resetting.
select true as locked
from pg_advisory_xact_lock_shared(4381022501)
