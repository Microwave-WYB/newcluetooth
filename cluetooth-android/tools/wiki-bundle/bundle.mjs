// Wiki checkout -> bundle object. No I/O besides reading the checkout; the CLI writes the file.
//
// Input (both front matter formats are YAML; the old one is a JSON object between the --- lines):
//   <wiki>/wiki-content/<kind>--<slug>/index.md   front matter: title, kind, one_line, entity?, categories?, updated?
//   <wiki>/wiki-content/<kind>--<slug>/rule.json  optional fingerprint rule
//   <wiki>/categories.json | categories.draft.json   { facets: { categories: [{ id, label, status? }] } }
import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { parse } from "yaml";

export const BUNDLE_SCHEMA = 1;
export const KINDS = ["device", "platform", "identifier"];

const stripBom = (text) => (text.charCodeAt(0) === 0xfeff ? text.slice(1) : text);
const readText = (path) => stripBom(readFileSync(path, "utf8"));

export function parseFrontMatter(source, where = "index.md") {
  // a BOM, CRLF line ends and a closing --- without a newline after it are all accepted
  const m = /^---[ \t]*\r?\n([\s\S]*?)\r?\n---[ \t]*(?:\r?\n|$)/.exec(stripBom(source));
  if (!m) throw new Error(`${where}: no front matter`);
  const data = parse(m[1]);
  if (data === null || typeof data !== "object" || Array.isArray(data)) {
    throw new Error(`${where}: front matter is not a mapping`);
  }
  return data;
}

export function readVocabulary(wikiDir, explicitPath) {
  const candidates = explicitPath
    ? [explicitPath]
    : [join(wikiDir, "categories.json"), join(wikiDir, "categories.draft.json")];
  const path = candidates.find((p) => existsSync(p));
  if (!path) throw new Error(`no category vocabulary found (tried ${candidates.join(", ")})`);
  const doc = JSON.parse(readText(path));
  const categories = doc?.facets?.categories;
  if (!Array.isArray(categories)) throw new Error(`${path}: facets.categories is missing`);
  return categories.filter((c) => (c.status ?? "active") === "active").map((c) => ({ id: c.id, label: c.label }));
}

function stringField(fm, key, where) {
  const v = fm[key];
  if (typeof v !== "string" || v.trim() === "") throw new Error(`${where}: front matter ${key} must be a non-empty string`);
  return v.trim();
}

/**
 * @param {{wikiDir: string, vocabularyPath?: string, legacy: {kinds: object, categories: object}, warn?: (message: string) => void}} options
 */
export function buildBundle({ wikiDir, vocabularyPath, legacy, warn = (message) => console.warn(`warning: ${message}`) }) {
  const vocabulary = readVocabulary(wikiDir, vocabularyPath);
  const known = new Map(vocabulary.map((c) => [c.id, c]));
  // The legacy mapping is a stand-in that goes stale as the wiki moves on: say so, never abort on it.
  const legacyCategories = {};
  for (const [page, ids] of Object.entries(legacy.categories ?? {})) {
    const valid = ids.filter((id) => known.has(id));
    for (const id of ids) if (!known.has(id)) warn(`legacy-mapping.json: ${page}: category "${id}" is not in the vocabulary; ignored`);
    legacyCategories[page] = valid;
  }

  const content = join(wikiDir, "wiki-content");
  const dirs = readdirSync(content)
    .filter((d) => statSync(join(content, d)).isDirectory())
    .sort();
  const pages = [];
  let updated = "";
  for (const dir of dirs) {
    const file = join(content, dir, "index.md");
    if (!existsSync(file)) throw new Error(`${dir}: missing index.md`);
    const fm = parseFrontMatter(readText(file), `${dir}/index.md`);
    const where = `${dir}/index.md`;
    const rawKind = stringField(fm, "kind", where);
    if (!dir.startsWith(`${rawKind}--`)) throw new Error(`${where}: directory does not start with kind "${rawKind}--"`);
    const kind = KINDS.includes(rawKind) ? rawKind : legacy.kinds?.[rawKind];
    if (!kind) throw new Error(`${where}: kind "${rawKind}" is neither ${KINDS.join("|")} nor in legacy-mapping.json`);

    let categories;
    if (fm.categories !== undefined && fm.categories !== null) {
      if (!Array.isArray(fm.categories) || !fm.categories.every((c) => typeof c === "string")) {
        throw new Error(`${where}: categories must be a list of ids`);
      }
      categories = fm.categories;
    } else {
      categories = legacyCategories[dir] ?? [];
    }
    for (const id of categories) if (!known.has(id)) throw new Error(`${where}: unknown category "${id}"`);

    const rulePath = join(content, dir, "rule.json");
    const rule = existsSync(rulePath) ? JSON.parse(readText(rulePath)) : undefined;
    const page = {
      id: dir,
      title: stringField(fm, "title", where),
      kind,
      one_line: stringField(fm, "one_line", where),
      ...(categories.length > 0 ? { categories: [...new Set(categories)] } : {}),
      entity: typeof fm.entity === "string" && fm.entity !== "" ? fm.entity : null,
      has_rule: rule !== undefined,
      ...(rule !== undefined ? { rule } : {}),
    };
    pages.push(page);
    const u = fm.updated instanceof Date ? fm.updated.toISOString().slice(0, 10) : String(fm.updated ?? "");
    if (u > updated) updated = u;
  }

  for (const page of Object.keys(legacyCategories)) {
    if (!dirs.includes(page)) warn(`legacy-mapping.json: no page ${page} in this wiki; entry ignored`);
  }

  const categories = vocabulary.map((c) => ({ id: c.id, label: c.label }));
  // version: changes whenever anything the app reads changes (the date is only for people).
  // rules_version: changes only when a rule does; the app re-matches its stored advertisements
  // on that, not on a retitled page or a relabelled category.
  const digest = (value) => createHash("sha256").update(JSON.stringify(value)).digest("hex");
  return {
    schema: BUNDLE_SCHEMA,
    version: `${updated || "undated"}+${digest({ categories, pages }).slice(0, 8)}`,
    rules_version: digest(pages.map((p) => [p.id, p.rule ?? null])).slice(0, 16),
    categories,
    pages,
  };
}
