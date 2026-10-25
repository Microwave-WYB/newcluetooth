-- Run in its own transaction: waits for in-flight scan inserts to commit, then
-- the following statement's snapshot sees every scan id at or below max(id).
select true as locked
from pg_advisory_xact_lock(4381022501)
