create role ro login password 'infra_wireless_scanning_ro';

grant connect on database cluetooth to ro;
grant usage on schema public to ro;
grant select on all tables in schema public to ro;
grant usage, select on all sequences in schema public to ro;

alter default privileges for role cluetooth in schema public
grant select on tables to ro;

alter default privileges for role cluetooth in schema public
grant usage, select on sequences to ro;
