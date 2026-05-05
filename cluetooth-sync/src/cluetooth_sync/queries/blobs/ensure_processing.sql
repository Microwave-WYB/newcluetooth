insert into blobs (uri)
values ($1)
on conflict (uri) do nothing
