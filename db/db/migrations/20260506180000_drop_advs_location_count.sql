-- migrate:up
alter table advs
  drop column location_count;

-- migrate:down
alter table advs
  add column location_count bigint not null default 0;
