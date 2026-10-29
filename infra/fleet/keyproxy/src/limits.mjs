// Account-protection rate limits, one per limit type. Each can be switched off in its service's
// config entry. They keep a key from being banned; they never decide which calls are allowed.
import { Reject } from "./http.mjs";

const LIMIT_TEXT = /too many|daily limit|throttl/i;

export function createLimit(svc, counters, now) {
  const l = svc.limit;
  if (!l?.enabled) return null;
  const key = svc.prefix.slice(1);

  if (l.type === "daily-budget") {
    // Every call counts; calls stop `reserve` short of the budget, and for the rest of the day
    // once the upstream itself says the limit is reached (HTTP 429 or a "too many" JSON body).
    return {
      before(ctx) {
        if (counters.flagged(`${key}.limited`)) {
          throw new Reject(429, `${key} reported its daily limit today; wait for the reset (00:05 America/Los_Angeles)`);
        }
        const used = counters.get(key);
        if (used >= l.budget - l.reserve) {
          throw new Reject(429, `${key} daily budget used: ${used} of ${l.budget} calls, ${l.reserve} kept in reserve`);
        }
        counters.add(key);
        ctx.headers[`x-keyproxy-${key}-remaining`] = String(Math.max(0, l.budget - l.reserve - used - 1));
      },
      wantsBody: (up) => /json/i.test(up.headers.get("content-type") ?? ""),
      after(status, text) {
        if (status === 429 || (status === 200 && isLimitBody(text))) counters.flag(`${key}.limited`);
      },
      status: () => ({
        type: l.type,
        day: counters.state.day,
        used: counters.get(key),
        budget: l.budget,
        reserve: l.reserve,
        remaining: Math.max(0, l.budget - l.reserve - counters.get(key)),
        limited_upstream: counters.flagged(`${key}.limited`),
      }),
    };
  }

  if (l.type === "one-at-a-time") {
    const state = { active: false, lastStart: 0 };
    return {
      before(_ctx, res) {
        if (state.active) throw new Reject(429, `another ${key} request is in progress; one at a time`, { retryAfter: 10 });
        const wait = state.lastStart + l.minIntervalSeconds * 1000 - now();
        if (wait > 0) throw new Reject(429, `${key} requests are rate limited`, { retryAfter: Math.ceil(wait / 1000) });
        state.active = true;
        state.lastStart = now();
        res.on("close", () => {
          state.active = false;
        });
      },
      status: () => ({ type: l.type, active: state.active, min_interval_seconds: l.minIntervalSeconds }),
    };
  }

  throw new Error(`unknown limit type: ${l.type}`);
}

function isLimitBody(text) {
  try {
    const j = JSON.parse(text);
    return j?.success === false && LIMIT_TEXT.test(String(j.message ?? j.error ?? ""));
  } catch {
    return false;
  }
}
