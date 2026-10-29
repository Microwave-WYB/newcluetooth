// Loads an AndroZoo list file into androzoo.apks: COPY into an unlogged raw table (all text),
// build the typed table with its indexes next to the live one, then drop the old table and
// rename the new one inside a single transaction. Readers see the old table or the new one,
// never a half-loaded one. Any failure before the swap leaves the live table untouched.
import { createReadStream } from "node:fs";
import { open } from "node:fs/promises";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { createGunzip } from "node:zlib";
import { from as copyFrom } from "pg-copy-streams";

export const COLUMNS = ["sha256", "sha1", "md5", "dex_date", "apk_size", "pkg_name", "vercode", "vt_detection", "vt_scan_date", "dex_size", "markets", "added"];
const REQUIRED = COLUMNS.slice(0, 11);
const READERS = ["androzoo_ro"];

/** Reads the header line without consuming it; returns the column names and a full stream. */
async function peekHeader(src) {
  // One iterator for both parts: leaving a for-await loop early would destroy the source.
  const it = src[Symbol.asyncIterator]();
  let buf = Buffer.alloc(0);
  for (;;) {
    const { value, done } = await it.next();
    if (done) break;
    buf = Buffer.concat([buf, value]);
    if (buf.includes(0x0a) || buf.length > 64 * 1024) break;
  }
  if (buf.length === 0) throw new Error("empty list file");
  const nl = buf.indexOf(0x0a);
  const line = buf.subarray(0, nl >= 0 ? nl : buf.length).toString("utf8").replace(/\r$/, "");
  const head = buf;
  const stream = Readable.from(
    (async function* () {
      yield head;
      for (;;) {
        const { value, done } = await it.next();
        if (done) return;
        yield value;
      }
    })(),
  );
  return { columns: line.split(",").map((c) => c.trim()), stream };
}

const asTimestamp = (c) => `case when pg_input_is_valid(${c}, 'timestamp') then ${c}::timestamp end`;
const asInt = (c, type) => `case when pg_input_is_valid(${c}, '${type}') then ${c}::${type} end`;

/**
 * Loads `file` (.csv or .csv.gz) and records `state` in androzoo.sync_state, all or nothing.
 * Returns the number of rows in the new table.
 */
export async function loadFile(client, file, state, { log = () => {} } = {}) {
  // gzip by content, not name: the job loads `latest.csv.gz.next` before renaming it.
  const fh = await open(file);
  const magic = Buffer.alloc(2);
  await fh.read(magic, 0, 2, 0);
  await fh.close();
  let src = createReadStream(file);
  if (magic[0] === 0x1f && magic[1] === 0x8b) src = src.pipe(createGunzip());
  const { columns, stream } = await peekHeader(src);
  const unknown = columns.filter((c) => !COLUMNS.includes(c));
  const missing = REQUIRED.filter((c) => !columns.includes(c));
  if (unknown.length || missing.length) {
    stream.destroy();
    throw new Error(`unexpected list header (unknown: ${unknown.join(",") || "-"}; missing: ${missing.join(",") || "-"})`);
  }

  await client.query("drop table if exists androzoo.apks_raw, androzoo.apks_next");
  await client.query(`create unlogged table androzoo.apks_raw (${columns.map((c) => `${c} text`).join(", ")})`);
  const copy = client.query(copyFrom(`copy androzoo.apks_raw (${columns.join(", ")}) from stdin with (format csv, header true)`));
  await pipeline(stream, copy);
  log({ event: "copied", rows: Number(copy.rowCount) });

  const col = (c) => (columns.includes(c) ? c : "null");
  await client.query(`
    create table androzoo.apks_next as
    select upper(sha256) as sha256, upper(sha1) as sha1, upper(md5) as md5,
           ${asTimestamp(col("dex_date"))} as dex_date,
           ${asInt(col("apk_size"), "bigint")} as apk_size,
           pkg_name,
           ${asInt(col("vercode"), "bigint")} as vercode,
           ${asInt(col("vt_detection"), "integer")} as vt_detection,
           ${asTimestamp(col("vt_scan_date"))} as vt_scan_date,
           ${asInt(col("dex_size"), "bigint")} as dex_size,
           markets,
           ${columns.includes("added") ? asTimestamp("added") : "null::timestamp"} as added
    from androzoo.apks_raw`);
  await client.query("drop table androzoo.apks_raw");
  // ~25M rows: give the sorts behind the primary key and indexes room (this session only).
  await client.query("set maintenance_work_mem = '1GB'");
  await client.query("alter table androzoo.apks_next add constraint apks_next_pkey primary key (sha256)");
  await client.query("create index apks_next_pkg_name_idx on androzoo.apks_next (pkg_name)");
  await client.query("create index apks_next_pkg_name_vercode_idx on androzoo.apks_next (pkg_name, vercode)");
  await client.query("analyze androzoo.apks_next");
  const { rows } = await client.query("select count(*)::bigint as n from androzoo.apks_next");
  const rowCount = Number(rows[0].n);

  await client.query("begin");
  try {
    await client.query("drop table androzoo.apks");
    await client.query("alter table androzoo.apks_next rename to apks");
    await client.query("alter table androzoo.apks rename constraint apks_next_pkey to apks_pkey");
    await client.query("alter index androzoo.apks_next_pkg_name_idx rename to apks_pkg_name_idx");
    await client.query("alter index androzoo.apks_next_pkg_name_vercode_idx rename to apks_pkg_name_vercode_idx");
    for (const role of READERS) {
      const { rowCount: exists } = await client.query("select 1 from pg_roles where rolname = $1", [role]);
      if (exists) await client.query(`grant select on androzoo.apks to ${role}`);
    }
    await client.query(
      `insert into androzoo.sync_state (id, source, etag, last_modified, content_length, row_count, loaded_at)
       values (true, $1, $2, $3, $4, $5, now())
       on conflict (id) do update set source = excluded.source, etag = excluded.etag,
         last_modified = excluded.last_modified, content_length = excluded.content_length,
         row_count = excluded.row_count, loaded_at = excluded.loaded_at`,
      [state.source, state.etag ?? null, state.lastModified ?? null, state.contentLength ?? null, rowCount],
    );
    await client.query("commit");
  } catch (e) {
    await client.query("rollback").catch(() => {});
    throw e;
  }
  return rowCount;
}

/** Removes leftovers of an interrupted load (never the live table). */
export async function cleanStaging(client) {
  await client.query("drop table if exists androzoo.apks_raw, androzoo.apks_next");
}
