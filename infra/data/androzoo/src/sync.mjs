// androzoo-sync: keeps androzoo.apks equal to AndroZoo's current APK list.
//
//   node src/sync.mjs                         daily check: HEAD, and download + load if changed
//   node src/sync.mjs --force                 download + load even if unchanged
//   node src/sync.mjs --initial-file <path>   one-off load of an existing file, no download
//
// The list is public (no AndroZoo key). One JSON line per event on stdout; exit code 1 when the
// run failed (the current table is then untouched).
import { rename, rm, stat } from "node:fs/promises";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import pg from "pg";
import { download, head } from "./download.mjs";
import { cleanStaging, loadFile } from "./load.mjs";

const LOCK_KEY = 4381022601; // pg_try_advisory_lock: one sync at a time (manual or scheduled)

export function loadConfig(env = process.env) {
  return {
    databaseUrl: env.ANDROZOO_DATABASE_URL,
    listUrl: env.ANDROZOO_LIST_URL || "https://androzoo.uni.lu/static/lists/latest.csv.gz",
    dataDir: env.ANDROZOO_DATA_DIR || "/var/lib/androzoo-sync",
    segments: Number(env.ANDROZOO_SEGMENTS || 10),
    retries: Number(env.ANDROZOO_SEGMENT_RETRIES || 5),
    userAgent: "cluetooth-androzoo-sync/1 (+https://wirelesswiki.org)",
  };
}

const log = (o) => process.stdout.write(`${JSON.stringify({ ts: new Date().toISOString(), ...o })}\n`);

/** Whether upstream differs from what the current table was loaded from. */
export function changed(state, meta) {
  if (!state) return true;
  return (
    (state.etag ?? null) !== (meta.etag ?? null) ||
    (state.last_modified ?? null) !== (meta.lastModified ?? null) ||
    (state.content_length === null ? null : Number(state.content_length)) !== (meta.contentLength ?? null)
  );
}

/** One run. Returns { status: "unchanged" | "loaded" | "busy", rows? }; throws on failure. */
export async function runSync(config, { initialFile, force = false, fetchImpl = fetch, emit = log } = {}) {
  const client = new pg.Client({ connectionString: config.databaseUrl });
  await client.connect();
  try {
    const { rows: lock } = await client.query("select pg_try_advisory_lock($1) as ok", [LOCK_KEY]);
    if (!lock[0].ok) {
      emit({ event: "busy", note: "another androzoo sync holds the lock" });
      return { status: "busy" };
    }
    await cleanStaging(client);

    if (initialFile) {
      // One-off: load a file that is already on disk (e.g. the agents' old copy); record its
      // size and mtime. The next daily check will not match it and downloads the current list.
      const st = await stat(initialFile);
      emit({ event: "initial-load", file: initialFile, bytes: st.size });
      const rows = await loadFile(client, initialFile, { source: `file:${initialFile}`, contentLength: st.size, lastModified: st.mtime.toUTCString() }, { log: emit });
      emit({ event: "loaded", rows, source: initialFile });
      return { status: "loaded", rows };
    }

    const meta = await head(config.listUrl, { userAgent: config.userAgent, fetchImpl });
    const { rows: st } = await client.query("select etag, last_modified, content_length from androzoo.sync_state");
    if (!force && !changed(st[0], meta)) {
      emit({ event: "unchanged", etag: meta.etag, last_modified: meta.lastModified, content_length: meta.contentLength });
      return { status: "unchanged" };
    }

    const name = new URL(config.listUrl).pathname.split("/").pop();
    const current = join(config.dataDir, name);
    const next = `${current}.next`;
    emit({ event: "download-start", url: config.listUrl, bytes: meta.contentLength, segments: config.segments });
    const started = Date.now();
    try {
      await download(config.listUrl, next, meta, { segments: config.segments, retries: config.retries, userAgent: config.userAgent, fetchImpl, log: emit });
    } catch (e) {
      await rm(next, { force: true });
      throw e;
    }
    emit({ event: "downloaded", bytes: meta.contentLength, seconds: Math.round((Date.now() - started) / 1000) });

    let rows;
    try {
      rows = await loadFile(client, next, { source: config.listUrl, ...meta }, { log: emit });
    } catch (e) {
      await rm(next, { force: true });
      await cleanStaging(client).catch(() => {});
      throw e;
    }
    // Keep only the newest file.
    await rename(next, current);
    emit({ event: "loaded", rows, source: config.listUrl, etag: meta.etag });
    return { status: "loaded", rows };
  } finally {
    await client.end().catch(() => {});
  }
}

async function main() {
  const { values } = parseArgs({ options: { "initial-file": { type: "string" }, force: { type: "boolean" } } });
  const config = loadConfig();
  if (!config.databaseUrl) {
    log({ event: "failed", error: "ANDROZOO_DATABASE_URL is not set" });
    process.exit(2);
  }
  try {
    await runSync(config, { initialFile: values["initial-file"], force: values.force });
  } catch (e) {
    log({ event: "failed", error: String(e?.message ?? e).slice(0, 500), note: "the current androzoo.apks table is unchanged" });
    process.exit(1);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) await main();
