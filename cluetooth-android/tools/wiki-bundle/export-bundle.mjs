#!/usr/bin/env node
// Usage: node export-bundle.mjs --wiki <checkout> [--out <file>] [--categories <file>]
// Default --out is the app asset, ../../app/src/main/assets/wiki-bundle.json.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { buildBundle } from "./bundle.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const { values } = parseArgs({
  options: {
    wiki: { type: "string" },
    out: { type: "string", default: resolve(here, "../../app/src/main/assets/wiki-bundle.json") },
    categories: { type: "string" },
  },
});
if (!values.wiki) {
  console.error("usage: export-bundle.mjs --wiki <wiki checkout> [--out file] [--categories file]");
  process.exit(2);
}
const readJson = (name) => JSON.parse(readFileSync(resolve(here, name), "utf8").replace(/^\uFEFF/, ""));
const bundle = buildBundle({
  wikiDir: resolve(values.wiki),
  vocabularyPath: values.categories && resolve(values.categories),
  legacy: readJson("legacy-mapping.json"),
});
mkdirSync(dirname(values.out), { recursive: true });
writeFileSync(values.out, JSON.stringify(bundle, null, 2) + "\n");
const rules = bundle.pages.filter((p) => p.has_rule).length;
console.log(`wrote ${values.out}: version ${bundle.version} (rules ${bundle.rules_version}), ${bundle.pages.length} pages, ${rules} rules, ${bundle.categories.length} categories`);
