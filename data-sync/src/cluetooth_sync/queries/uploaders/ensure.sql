insert into uploaders (uploader)
values ($1)
on conflict (uploader) do nothing
