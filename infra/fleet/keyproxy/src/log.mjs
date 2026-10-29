// One JSON line per request. Every line passes through scrub(), which replaces each secret (and
// the encodings it could appear in) with <redacted>, so a secret a caller or upstream echoes back
// still never reaches the log.
import { appendFileSync } from "node:fs";

const MIN_SECRET_LENGTH = 6;

/** The forms a secret can take on the wire: raw, URL-encoded, base64, and the parts of a basic-auth pair. */
export function secretVariants(values) {
  const out = new Set();
  const add = (s) => {
    if (typeof s === "string" && s.length >= MIN_SECRET_LENGTH) out.add(s);
  };
  for (const v of values) {
    if (!v) continue;
    add(v);
    add(encodeURIComponent(v));
    add(Buffer.from(v).toString("base64"));
    // A WiGLE key is base64("name:token"): hide the decoded pair and the token too.
    const decoded = Buffer.from(v, "base64").toString("utf8");
    if (/^[\x20-\x7e]+:[\x20-\x7e]+$/.test(decoded) && Buffer.from(decoded).toString("base64") === v) {
      add(decoded);
      add(decoded.slice(decoded.indexOf(":") + 1));
    }
  }
  // Longest first, so a secret that contains another is replaced whole.
  return [...out].sort((a, b) => b.length - a.length);
}

export function createScrubber(values) {
  const needles = secretVariants(values);
  const scrub = (s) => {
    let t = String(s);
    for (const n of needles) t = t.split(n).join("<redacted>");
    return t;
  };
  const scrubDeep = (v) => {
    if (typeof v === "string") return scrub(v);
    if (Array.isArray(v)) return v.map(scrubDeep);
    if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).map(([k, x]) => [scrub(k), scrubDeep(x)]));
    return v;
  };
  return { scrub, scrubDeep };
}

export function createLogger({ scrubber, write = (line) => process.stdout.write(line), file } = {}) {
  return {
    log(entry) {
      const line = `${scrubber.scrub(JSON.stringify(scrubber.scrubDeep(entry)))}\n`;
      write(line);
      if (file) {
        try {
          appendFileSync(file, line, { mode: 0o600 });
        } catch (e) {
          process.stderr.write(`keyproxy: cannot append to log file: ${e.code ?? e.message}\n`);
        }
      }
    },
  };
}
