// WiGLE: Bluetooth search only, under a daily budget that stops RESERVE calls short of it.
import { Reject, sendJson } from "./http.mjs";

export const WIGLE_PATH = "/wigle/api/v2/bluetooth/search";

// Query parameters of WiGLE's /api/v2/bluetooth/search that may pass. name and namelike go through
// unchanged (namelike keeps its % wildcards). Anything else is refused rather than dropped, so a
// caller never thinks a filter applied when it did not.
export const WIGLE_PARAMS = new Set([
  "name",
  "namelike",
  "searchAfter",
  "resultsPerPage",
  "latrange1",
  "latrange2",
  "longrange1",
  "longrange2",
  "closestLat",
  "closestLong",
  "lastupdt",
  "country",
  "region",
  "city",
  "postalCode",
]);

const LIMIT_TEXT = /too many|daily limit|throttl/i;

export async function handleWigle(req, res, ctx, deps) {
  const { config, secrets, counters, scrubber } = deps;
  const url = new URL(req.url, "http://keyproxy");
  ctx.service = "wigle";
  if (url.pathname !== WIGLE_PATH) throw new Reject(403, `only ${WIGLE_PATH} is allowed`);
  if (req.method !== "GET") throw new Reject(405, "only GET is allowed");
  ctx.endpoint = "wigle.bluetooth.search";

  const params = {};
  for (const [k, v] of url.searchParams) {
    if (!WIGLE_PARAMS.has(k)) throw new Reject(400, `parameter not allowed: ${k}`);
    if (Object.hasOwn(params, k)) throw new Reject(400, `repeated parameter: ${k}`);
    if (v.length > 512) throw new Reject(400, `parameter too long: ${k}`);
    params[k] = v;
  }
  ctx.params = params;
  if (!secrets.wigle) throw new Reject(503, "WiGLE is not configured (no wigle-api-key secret)");

  const { dailyBudget, reserve, perCallerDaily } = config.wigle;
  const usable = Math.max(0, dailyBudget - reserve);
  if (counters.flagged("wigle.limited")) {
    throw new Reject(429, "WiGLE reported its daily limit today; wait for the reset (00:05 America/Los_Angeles)");
  }
  const used = counters.get("wigle");
  if (used >= usable) {
    throw new Reject(429, `WiGLE daily budget used: ${used} of ${dailyBudget} calls, ${reserve} kept in reserve`);
  }
  const callerKey = `wigle.caller.${ctx.caller}`;
  if (perCallerDaily > 0 && counters.get(callerKey) >= perCallerDaily) {
    throw new Reject(429, `WiGLE per-caller budget used for ${ctx.caller}: ${perCallerDaily} calls today`);
  }
  // Count before calling, so concurrent requests cannot overshoot the budget.
  counters.add("wigle");
  counters.add(callerKey);
  const remaining = usable - used - 1;

  let up;
  let text;
  try {
    up = await fetch(`${config.wigle.url}/api/v2/bluetooth/search?${new URLSearchParams(params)}`, {
      headers: { Authorization: `Basic ${secrets.wigle}`, Accept: "application/json", "User-Agent": config.userAgent },
      redirect: "manual",
      signal: AbortSignal.timeout(config.wigle.timeoutMs),
    });
    text = await up.text();
  } catch (e) {
    throw new Reject(502, `WiGLE unreachable: ${e.cause?.code ?? e.name}`);
  }
  if (up.status === 429 || (up.status === 200 && isLimitBody(text))) counters.flag("wigle.limited");

  const body = scrubber.scrub(text);
  res.writeHead(up.status, {
    "content-type": up.headers.get("content-type") ?? "application/json",
    "content-length": Buffer.byteLength(body),
    "x-keyproxy-wigle-remaining": String(Math.max(0, remaining)),
  });
  res.end(body);
  ctx.bytesOut = Buffer.byteLength(body);
}

function isLimitBody(text) {
  try {
    const j = JSON.parse(text);
    return j?.success === false && LIMIT_TEXT.test(String(j.message ?? j.error ?? ""));
  } catch {
    return false;
  }
}

/** GET /wigle/budget: today's usage, for agents to plan with. Not a WiGLE call. */
export function wigleBudget(res, ctx, { config, counters }) {
  ctx.service = "wigle";
  ctx.endpoint = "wigle.budget";
  const { dailyBudget, reserve, perCallerDaily } = config.wigle;
  sendJson(res, 200, {
    day: counters.state.day,
    used: counters.get("wigle"),
    daily_budget: dailyBudget,
    reserve,
    remaining: Math.max(0, dailyBudget - reserve - counters.get("wigle")),
    per_caller_daily: perCallerDaily || null,
    limited_by_wigle: counters.flagged("wigle.limited"),
  });
}
