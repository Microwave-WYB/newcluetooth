-- Held shared by every scan-inserting transaction until it commits, so a
-- projection that briefly takes the exclusive form sees no in-flight scan ids.
select true as locked
from pg_advisory_xact_lock_shared(4381022501)
