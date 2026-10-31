-- Session-level advisory lock on one blob, held until the connection closes.
-- Polars' ADBC writer commits the session's transaction, which releases the
-- ledger row lock and the transaction-level locks, so this is what keeps a
-- second process from ingesting the same blob while the first is mid-way. The
-- two-integer form (namespace 1, hash of the URI) cannot collide with the
-- single-bigint ingest/rebuild lock; a hash collision only serializes two blobs.
select true as locked
from pg_advisory_lock(1, hashtext($1))
