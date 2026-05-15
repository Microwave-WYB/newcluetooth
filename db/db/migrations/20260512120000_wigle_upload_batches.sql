-- migrate:up
create table wigle_upload_batches (
  id bigserial primary key,
  client_token text unique,
  status text not null check (
    status in (
      'created',
      'exported',
      'uploading',
      'uploaded',
      'completed',
      'failed'
    )
  ),
  row_count bigint not null default 0,
  min_scan_id bigint,
  max_scan_id bigint,
  filename text,
  csv_sha256 text,
  wigle_transids jsonb not null default '[]'::jsonb,
  wigle_status text,
  upload_response jsonb,
  status_response jsonb,
  error text,
  created_at timestamptz not null default now(),
  exported_at timestamptz,
  uploaded_at timestamptz,
  status_checked_at timestamptz,
  completed_at timestamptz
);

create table wigle_upload_batch_scans (
  batch_id bigint not null references wigle_upload_batches (id) on delete cascade,
  scan_id bigint not null references scans (id) on delete restrict,
  primary key (batch_id, scan_id)
);

create index wigle_upload_batches_status_idx on wigle_upload_batches (status, id);
create index wigle_upload_batch_scans_scan_idx on wigle_upload_batch_scans (scan_id);

-- migrate:down
drop table wigle_upload_batch_scans;
drop table wigle_upload_batches;
