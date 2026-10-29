// Daily counters persisted to one JSON file, so a restart does not reset a budget.
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const DAY_FORMAT = new Intl.DateTimeFormat("en-CA", {
  timeZone: "America/Los_Angeles",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/** The WiGLE day: Pacific time, rolling over at 00:05 (as the wigle CLI counts it). */
export function pacificDay(ms) {
  return DAY_FORMAT.format(new Date(ms - 5 * 60 * 1000));
}

export class DailyCounters {
  constructor({ file, now = () => Date.now(), dayOf = pacificDay } = {}) {
    this.file = file;
    this.now = now;
    this.dayOf = dayOf;
    this.state = { day: dayOf(now()), counts: {}, flags: {} };
    if (file) {
      try {
        const s = JSON.parse(readFileSync(file, "utf8"));
        if (s && typeof s.day === "string") this.state = { day: s.day, counts: s.counts ?? {}, flags: s.flags ?? {} };
      } catch {
        // No state yet, or unreadable: start the day empty.
      }
    }
  }

  #roll() {
    const day = this.dayOf(this.now());
    if (day !== this.state.day) this.state = { day, counts: {}, flags: {} };
  }

  get(key) {
    this.#roll();
    return this.state.counts[key] ?? 0;
  }

  add(key, n = 1) {
    this.#roll();
    this.state.counts[key] = (this.state.counts[key] ?? 0) + n;
    this.#save();
  }

  flagged(key) {
    this.#roll();
    return this.state.flags[key] === true;
  }

  flag(key) {
    this.#roll();
    this.state.flags[key] = true;
    this.#save();
  }

  #save() {
    if (!this.file) return;
    mkdirSync(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.state), { mode: 0o600 });
    renameSync(tmp, this.file);
  }
}
