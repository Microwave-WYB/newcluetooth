#!/usr/bin/env node
/*
 * Exports the prototype's design data as JSON for code generation (docs/v2-api.md, lanes C and E):
 *
 *   tokens.json     every custom property of tokens.css, light and dark, var() resolved and calc() folded
 *                   where every term is px or unitless; CSS units are preserved ("12px", "0.18s").
 *   map-style.json  the sources and layers of map-style.js, built with the light and the dark values that
 *                   map-style.js valuesFrom() reads from the tokens (the same code app.js uses).
 *
 * The parser is strict: anything it does not recognise (another block or at-rule, a non-token declaration, an
 * unknown value form, an inset or multi-layer shadow, a non-color map value) stops the export with an error.
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

function fail(message) {
  throw new Error(`export.mjs: ${message}`);
}

// ---- tokens.css ----

/** Top-level blocks as { prelude, body } with balanced braces; comments removed. */
function blocks(css) {
  const src = css.replace(/\/\*[\s\S]*?\*\//g, "");
  const out = [];
  let i = 0;
  while (i < src.length) {
    const open = src.indexOf("{", i);
    if (open < 0) {
      if (src.slice(i).trim()) fail(`unexpected text after the last block: ${src.slice(i).trim().slice(0, 40)}`);
      break;
    }
    let depth = 1, j = open + 1;
    for (; j < src.length && depth; j++) {
      if (src[j] === "{") depth++;
      else if (src[j] === "}") depth--;
    }
    if (depth) fail("unbalanced braces");
    out.push({ prelude: src.slice(i, open).trim(), body: src.slice(open + 1, j - 1) });
    i = j;
  }
  return out;
}

/** The custom properties of a `:root` body, in order; `color-scheme` is the only other declaration allowed. */
function declarations(body, where) {
  if (/[{}]/.test(body)) fail(`nested block inside ${where}`);
  const decls = new Map();
  for (const raw of body.split(";")) {
    const decl = raw.trim();
    if (!decl) continue;
    const m = /^(--[\w-]+)\s*:\s*([\s\S]+)$/.exec(decl);
    if (!m) {
      if (/^color-scheme\s*:\s*(light|dark)$/.test(decl)) continue;
      fail(`unrecognised declaration in ${where}: ${decl}`);
    }
    if (decls.has(m[1])) fail(`${m[1]} declared twice in ${where}`);
    decls.set(m[1], m[2].trim().replace(/\s+/g, " "));
  }
  return decls;
}

function parseTokens(css) {
  const top = blocks(css);
  if (top.length !== 2) fail(`expected :root and one dark @media block, found ${top.length} blocks`);
  const [root, media] = top;
  if (root.prelude !== ":root") fail(`first block must be :root, found ${root.prelude}`);
  if (media.prelude !== "@media (prefers-color-scheme: dark)") fail(`second block must be the dark @media, found ${media.prelude}`);
  const inner = blocks(media.body);
  if (inner.length !== 1 || inner[0].prelude !== ":root") fail("the dark @media block must hold exactly one :root block");
  const light = declarations(root.body, ":root");
  const darkOverrides = declarations(inner[0].body, "dark :root");
  for (const name of darkOverrides.keys()) if (!light.has(name)) fail(`${name} is dark-only`);
  // var() resolves in the context where it is used, so dark re-resolves every light expression with the overrides
  const dark = new Map([...light, ...darkOverrides]);
  return { light, dark, darkOverrides };
}

/** Substitutes var() references recursively, then folds calc(). */
function resolve(decls, name, seen = new Set()) {
  if (seen.has(name)) fail(`var() cycle at ${name}`);
  const raw = decls.get(name);
  if (raw === undefined) fail(`undefined token ${name}`);
  const next = new Set(seen).add(name);
  if (/var\((?!--[\w-]+\))/.test(raw)) fail(`var() with a fallback or bad name in ${name}: ${raw}`);
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
  if (terms.length % 2 === 0) fail(`malformed calc(${expr})`);
  // * and / first, then + and -
  const stack = [terms[0]];
  for (let i = 1; i < terms.length; i += 2) {
    const op = terms[i], b = terms[i + 1];
    if (typeof op !== "string" || typeof b !== "object") fail(`malformed calc(${expr})`);
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
    if ([r, g, b].some((x) => x > 255) || a > 255) fail(`color out of range: ${value}`);
  } else return null;
  return "#" + [a, r, g, b].map((x) => x.toString(16).padStart(2, "0")).join("").toUpperCase();
}

const LENGTH = /^-?\d*\.?\d+px$|^0$/;

/** One outer shadow "x y blur [spread] color" as { x, y, blur, spread, color, argb }; px preserved.
 *  Fails on inset and multi-layer shadows, which the Compose port has no mapping for. */
function shadow(value, name) {
  if (/(^|\s)inset(\s|$)/.test(value)) fail(`${name}: inset shadows are not supported: ${value}`);
  if (/,(?![^()]*\))/.test(value)) fail(`${name}: multi-layer shadows are not supported: ${value}`);
  const m = /^(\S+) (\S+) (\S+)(?: (\S+))? (rgba?\(.*\)|#\w+)$/.exec(value);
  if (!m || ![m[1], m[2], m[3], m[4] ?? "0"].every((x) => LENGTH.test(x)) || !argb(m[5])) {
    fail(`${name}: unrecognised shadow: ${value}`);
  }
  const px = (s) => (s === "0" ? "0px" : s);
  return { x: px(m[1]), y: px(m[2]), blur: px(m[3]), spread: px(m[4] ?? "0"), color: m[5], argb: argb(m[5]) };
}

/** Values the layout computes at runtime (frame/window relative); the only "expression" forms accepted. */
const RELATIVE = /^(-?\d*\.?\d+(cqw|cqh)|calc\(100% - -?\d*\.?\d+px\)|env\(safe-area-inset-top, 0px\)|calc\(env\(safe-area-inset-top, 0px\) \+ -?\d*\.?\d+px\))$/;

function typeOf(value, name) {
  if (argb(value)) return "color";
  if (/^-?\d*\.?\d+px$/.test(value)) return "length";
  if (/^-?\d*\.?\d+s$/.test(value)) return "duration";
  if (/^-?\d*\.?\d+$/.test(value)) return "number";
  if (/^cubic-bezier\(\s*[-\d.]+\s*,\s*[-\d.]+\s*,\s*[-\d.]+\s*,\s*[-\d.]+\s*\)$/.test(value)) return "easing";
  if (/^-?\d*\.?\d+px -?\d*\.?\d+px|^0 /.test(value) || /(^|\s)inset(\s|$)/.test(value)) return "shadow";
  if (/^[\w\s"'-]+(,[\w\s"'-]+)+$/.test(value) && /^--font/.test(name)) return "font";
  if (RELATIVE.test(value)) return "expression";
  fail(`${name}: unrecognised value: ${value}`);
}

function tokenEntry(name, decls) {
  const light = resolve(decls.light, name);
  const dark = resolve(decls.dark, name);
  const type = typeOf(light, name);
  if (typeOf(dark, name) !== type) fail(`${name}: light is a ${type} but dark is not: ${dark}`);
  const e = { type, css: decls.light.get(name) };
  if (decls.darkOverrides.has(name)) e.cssDark = decls.darkOverrides.get(name);
  e.light = light;
  e.dark = dark;
  if (type === "color") Object.assign(e, { lightArgb: argb(light), darkArgb: argb(dark) });
  if (type === "shadow") Object.assign(e, { lightShadow: shadow(light, name), darkShadow: shadow(dark, name) });
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
  const MS = sandbox.window.CluetoothMapStyle;
  for (const k of ["baseStyles", "tokenOf", "numeric", "valuesFrom", "sources", "layers", "selectionProps", "endsOf"]) {
    if (MS?.[k] === undefined) fail(`map-style.js does not export ${k}`);
  }
  return MS;
}

/** For MapLibre: 3-digit hex expanded, alpha as rgba() (its color parser takes no 8-digit hex). */
function mapColor(value, key) {
  const a = argb(value);
  if (!a) fail(`map value ${key} is not a color: ${value}`);
  const [al, r, g, b] = [1, 3, 5, 7].map((i) => parseInt(a.slice(i, i + 2), 16));
  if (al === 255) return "#" + a.slice(3).toLowerCase();
  return `rgba(${r}, ${g}, ${b}, ${Math.round((al / 255) * 1000) / 1000})`;
}

/** map-style.js valuesFrom() over one scheme of tokens.json, colors normalized for MapLibre. */
function styleValues(MS, tokens, scheme) {
  const tok = (n) => {
    if (!tokens[n]) fail(`map-style.js reads unknown token ${n}`);
    return tokens[n][scheme];
  };
  const values = MS.valuesFrom(tok);
  for (const [k, v] of Object.entries(values)) {
    if (k === "font") continue;
    if (MS.numeric.includes(k)) {
      if (!Number.isFinite(v)) fail(`map value ${k} is not a number: ${v}`);
    } else values[k] = mapColor(v, k);
  }
  return values;
}

function exportMapStyle(js, tokens) {
  const MS = loadMapStyle(js);
  const schemes = ["light", "dark"];
  const values = Object.fromEntries(schemes.map((s) => [s, styleValues(MS, tokens, s)]));
  const plain = (x) => JSON.parse(JSON.stringify(x));
  return {
    generatedBy: "cluetooth-android/design/ui-v2/export.mjs",
    source: "map-style.js",
    baseStyle: plain(MS.baseStyles),
    sources: plain(MS.sources()),
    values,
    layers: Object.fromEntries(schemes.map((s) => [s, plain(MS.layers(values[s]))])),
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
