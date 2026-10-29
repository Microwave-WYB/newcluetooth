#!/usr/bin/env node
/*
 * Exports the prototype's design data as JSON for code generation (docs/v2-api.md, lanes C and E):
 *
 *   tokens.json     every custom property of tokens.css, light and dark, var() resolved and calc() folded
 *                   where every term is px or unitless; CSS units are preserved ("12px", "0.18s").
 *   map-style.json  the sources and layers of map-style.js, built with the light and the dark color sets
 *                   exactly as app.js styleColors() reads them from the tokens.
 *
 * Run from the repository root: mise exec -- node cluetooth-android/design/ui-v2/export.mjs
 * --check writes nothing and exits 1 when a committed JSON file is out of date.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const DIR = dirname(fileURLToPath(import.meta.url));
const CHECK = process.argv.includes("--check");

// ---- tokens.css ----

/** The declarations of the first `:root { ... }` block at or after `from`, in source order. */
function rootBlock(css, from) {
  const start = css.indexOf(":root", from);
  if (start < 0) throw new Error(":root block not found");
  const open = css.indexOf("{", start);
  const close = css.indexOf("}", open);
  const body = css.slice(open + 1, close).replace(/\/\*[\s\S]*?\*\//g, "");
  const decls = new Map();
  for (const m of body.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) decls.set(m[1], m[2].trim().replace(/\s+/g, " "));
  return decls;
}

function parseTokens(css) {
  const light = rootBlock(css, 0);
  const media = css.indexOf("@media (prefers-color-scheme: dark)");
  if (media < 0) throw new Error("dark media block not found");
  const darkOverrides = rootBlock(css, media);
  for (const name of darkOverrides.keys()) if (!light.has(name)) throw new Error(`${name} is dark-only`);
  // var() resolves in the context where it is used, so dark re-resolves every light expression with the overrides
  const dark = new Map([...light, ...darkOverrides]);
  return { light, dark, darkOverrides };
}

/** Substitutes var() references recursively, then folds calc(). */
function resolve(decls, name, seen = new Set()) {
  if (seen.has(name)) throw new Error(`var() cycle at ${name}`);
  const raw = decls.get(name);
  if (raw === undefined) throw new Error(`undefined token ${name}`);
  const next = new Set(seen).add(name);
  const substituted = raw.replace(/var\((--[\w-]+)\)/g, (_, ref) => resolve(decls, ref, next));
  return foldCalc(substituted);
}

/** Folds every innermost calc() whose terms are all px or unitless; others stay as written. */
function foldCalc(value) {
  let out = value;
  for (;;) {
    const folded = out.replace(/calc\(([^()]*)\)/g, (whole, expr) => {
      const v = evalPx(expr);
      return v === null ? `calc⟨${expr}⟩` : v;
    });
    if (folded === out) break;
    out = folded;
  }
  return out.replace(/calc⟨/g, "calc(").replace(/⟩/g, ")");
}

/** Evaluates `a + b * c` over px and unitless numbers; null if any other unit or token appears. */
function evalPx(expr) {
  const tokens = expr.trim().split(/\s+/);
  const terms = [];
  for (const t of tokens) {
    if (/^[-+*/]$/.test(t)) terms.push(t);
    else {
      const m = /^(-?\d*\.?\d+)(px)?$/.exec(t);
      if (!m) return null;
      terms.push({ n: Number(m[1]), px: !!m[2] });
    }
  }
  // * and / first, then + and -
  const stack = [terms[0]];
  for (let i = 1; i < terms.length; i += 2) {
    const op = terms[i], b = terms[i + 1];
    if (op === "*" || op === "/") {
      const a = stack.pop();
      if (op === "/" && b.px) return null;
      stack.push({ n: op === "*" ? a.n * b.n : a.n / b.n, px: a.px || b.px });
    } else stack.push(op, b);
  }
  let acc = stack[0];
  for (let i = 1; i < stack.length; i += 2) {
    const b = stack[i + 1];
    if (acc.px !== b.px) return null;
    acc = { n: stack[i] === "+" ? acc.n + b.n : acc.n - b.n, px: acc.px };
  }
  const n = Math.round(acc.n * 1000) / 1000;
  return acc.px ? `${n}px` : String(n);
}

const HEX = /^#([0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$/i;
const RGBA = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$/i;

/** A CSS color as Compose-style "#AARRGGBB", or null if the value is not a color. */
function argb(value) {
  let r, g, b, a = 255;
  const hex = HEX.exec(value);
  const rgba = RGBA.exec(value);
  if (hex) {
    let h = hex[1];
    if (h.length <= 4) h = [...h].map((c) => c + c).join("");
    [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
    if (h.length === 8) a = parseInt(h.slice(6, 8), 16);
  } else if (rgba) {
    [r, g, b] = [1, 2, 3].map((i) => Number(rgba[i]));
    if (rgba[4] !== undefined) a = Math.round(Number(rgba[4]) * 255);
  } else return null;
  return "#" + [a, r, g, b].map((x) => x.toString(16).padStart(2, "0")).join("").toUpperCase();
}

/** "0 2px 16px rgba(0, 0, 0, 0.06)" as { x, y, blur, spread, color, argb }; px preserved. */
function shadow(value) {
  const m = /^(\S+) (\S+) (\S+)(?: (\S+px))? (rgba?\(.*\)|#\w+)$/.exec(value);
  if (!m) return null;
  const px = (s) => (s === "0" ? "0px" : s);
  return { x: px(m[1]), y: px(m[2]), blur: px(m[3]), spread: px(m[4] ?? "0"), color: m[5], argb: argb(m[5]) };
}

function typeOf(value) {
  if (argb(value)) return "color";
  if (shadow(value)) return "shadow";
  if (/^-?\d*\.?\d+px$/.test(value)) return "length";
  if (/^-?\d*\.?\d+s$/.test(value)) return "duration";
  if (/^-?\d*\.?\d+$/.test(value)) return "number";
  if (/^cubic-bezier\(/.test(value)) return "easing";
  if (value === "light" || value === "dark") return "scheme";
  if (/,/.test(value) && !/\(/.test(value)) return "font";
  // relative to the frame or the window (%, cqw, cqh, env()): the layout computes these
  return "expression";
}

function tokenEntry(name, decls) {
  const light = resolve(decls.light, name);
  const dark = resolve(decls.dark, name);
  const type = typeOf(light);
  const e = { type, css: decls.light.get(name) };
  if (decls.darkOverrides.has(name)) e.cssDark = decls.darkOverrides.get(name);
  e.light = light;
  e.dark = dark;
  if (type === "color") Object.assign(e, { lightArgb: argb(light), darkArgb: argb(dark) });
  if (type === "shadow") Object.assign(e, { lightShadow: shadow(light), darkShadow: shadow(dark) });
  if (type === "duration") e.ms = Math.round(parseFloat(light) * 1000);
  if (type === "easing") e.bezier = light.replace(/^cubic-bezier\(|\)$/g, "").split(",").map(Number);
  return e;
}

function exportTokens(css) {
  const decls = parseTokens(css);
  const tokens = {};
  for (const name of decls.light.keys()) tokens[name] = tokenEntry(name, decls);
  return {
    generatedBy: "cluetooth-android/design/ui-v2/export.mjs",
    source: "tokens.css",
    note: "1 CSS px is 1 dp. light/dark are resolved values; css is the declaration as written.",
    tokens,
  };
}

// ---- map-style.js ----

function loadMapStyle(js) {
  const sandbox = { window: {} };
  vm.runInNewContext(js, sandbox, { filename: "map-style.js" });
  return sandbox.window.CluetoothMapStyle;
}

/** For MapLibre: 3-digit hex expanded, 8-digit hex as rgba() (its color parser takes neither). */
function mapColor(value) {
  const a = argb(value);
  if (!a) throw new Error(`not a color: ${value}`);
  const [al, r, g, b] = [1, 3, 5, 7].map((i) => parseInt(a.slice(i, i + 2), 16));
  if (al === 255) return "#" + a.slice(3).toLowerCase();
  return `rgba(${r}, ${g}, ${b}, ${Math.round((al / 255) * 1000) / 1000})`;
}

/** app.js styleColors(), for one scheme of tokens.json. Keep the two in step. */
function styleColors(tokens, scheme) {
  const t = (n) => tokens[n][scheme];
  const c = (n) => mapColor(t(n));
  return {
    route: c("--map-route"), routeOpacity: +t("--map-route-opacity"),
    density: c("--map-density"), densityOpacity: +t("--map-density-opacity"), densityRadius: +t("--map-density-radius"),
    point: c("--blue"), stroke: c("--on-blue"), clusterText: c("--on-blue"), selected: c("--t1"), me: c("--blue"),
    rssiStrong: c("--rssi-strong"), rssiFair: c("--rssi-fair"), rssiWeak: c("--rssi-weak"),
    start: c("--map-start"), end: c("--map-end"), endText: c("--map-end"), halo: c("--fill1"), font: "Noto Sans Bold",
  };
}

function exportMapStyle(js, tokens) {
  const MS = loadMapStyle(js);
  const schemes = ["light", "dark"];
  const colors = Object.fromEntries(schemes.map((s) => [s, styleColors(tokens, s)]));
  const plain = (x) => JSON.parse(JSON.stringify(x));
  return {
    generatedBy: "cluetooth-android/design/ui-v2/export.mjs",
    source: "map-style.js",
    baseStyle: {
      light: "https://tiles.openfreemap.org/styles/liberty",
      dark: "https://tiles.openfreemap.org/styles/dark",
    },
    sources: plain(MS.sources()),
    colors,
    layers: Object.fromEntries(schemes.map((s) => [s, plain(MS.layers(colors[s]))])),
    runtime: {
      routeOpacity: { session: +tokens["--map-route-opacity"].light, allSessions: +tokens["--map-routes-opacity"].light },
      dim: +tokens["--dim"].light,
      selection: {
        doc: "map-style.js selectionProps(addr, dim): [layerId, kind, property, value] to set; addr null clears",
        set: plain(MS.selectionProps("ADDR", "DIM")),
        clear: plain(MS.selectionProps(null, "DIM")),
      },
      ends: plain(MS.endsOf([[0, 0], [1, 1]]).features.map((f) => f.properties)),
    },
  };
}

// ---- write ----

const json = (x) => JSON.stringify(x, null, 2) + "\n";
const tokensJson = exportTokens(readFileSync(join(DIR, "tokens.css"), "utf8"));
const outputs = {
  "tokens.json": json(tokensJson),
  "map-style.json": json(exportMapStyle(readFileSync(join(DIR, "map-style.js"), "utf8"), tokensJson.tokens)),
};

let stale = false;
for (const [file, text] of Object.entries(outputs)) {
  const path = join(DIR, file);
  if (CHECK) {
    let old = null;
    try { old = readFileSync(path, "utf8"); } catch {}
    if (old !== text) { stale = true; console.error(`${file} is out of date; run export.mjs`); }
  } else {
    writeFileSync(path, text);
    console.log(`wrote ${file}`);
  }
}
if (stale) process.exit(1);
