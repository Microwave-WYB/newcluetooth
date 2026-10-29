// The androzoo-sync service: runs the daily check once a day at ANDROZOO_SYNC_AT (HH:MM, UTC).
// It does not run at start, so (re)deploying never triggers a download by itself.
import { loadConfig, runSync } from "./sync.mjs";

const at = process.env.ANDROZOO_SYNC_AT || "03:30";
const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(at);
if (!m) throw new Error(`ANDROZOO_SYNC_AT must be HH:MM (UTC), got ${at}`);

const log = (o) => process.stdout.write(`${JSON.stringify({ ts: new Date().toISOString(), ...o })}\n`);

export function nextRun(now, hh, mm) {
  const t = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), hh, mm));
  if (t <= now) t.setUTCDate(t.getUTCDate() + 1);
  return t;
}

const config = loadConfig();
for (;;) {
  const next = nextRun(new Date(), Number(m[1]), Number(m[2]));
  log({ event: "scheduled", next: next.toISOString() });
  await new Promise((r) => setTimeout(r, next - Date.now()));
  try {
    await runSync(config, { emit: log });
  } catch (e) {
    log({ event: "failed", error: String(e?.message ?? e).slice(0, 500), note: "the current androzoo.apks table is unchanged" });
  }
}
