select null::bigint as old_value, count(*)::bigint as new_value
from sighting_minutes_default
