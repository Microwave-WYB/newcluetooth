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

export function parseFrontMatter(source, where = "index.md") {
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n/.exec(source);
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
  const doc = JSON.parse(readFileSync(path, "utf8"));
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
 * @param {{wikiDir: string, vocabularyPath?: string, legacy: {kinds: object, categories: object}, labelsZh: object, version?: string}} options
 */
export function buildBundle({ wikiDir, vocabularyPath, legacy, labelsZh, version }) {
  const vocabulary = readVocabulary(wikiDir, vocabularyPath);
  const known = new Map(vocabulary.map((c) => [c.id, c]));
  for (const id of Object.keys(legacy.categories ?? {}).flatMap((p) => legacy.categories[p])) {
    if (!known.has(id)) throw new Error(`legacy-mapping.json: unknown category ${id}`);
  }
  for (const c of vocabulary) {
    if (typeof labelsZh[c.id] !== "string" || labelsZh[c.id] === "") {
      throw new Error(`category-labels.zh.json: missing Chinese label for ${c.id}`);
    }
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
    const fm = parseFrontMatter(readFileSync(file, "utf8"), `${dir}/index.md`);
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
      categories = legacy.categories?.[dir] ?? [];
    }
    for (const id of categories) if (!known.has(id)) throw new Error(`${where}: unknown category "${id}"`);

    const rulePath = join(content, dir, "rule.json");
    const rule = existsSync(rulePath) ? JSON.parse(readFileSync(rulePath, "utf8")) : undefined;
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

  const categories = vocabulary.map((c) => ({ id: c.id, label: c.label, label_zh: labelsZh[c.id] }));
  // The version changes whenever anything the app reads changes, so a new
  // asset always triggers a re-match; the date is only for people.
  const digest = createHash("sha256").update(JSON.stringify({ categories, pages })).digest("hex").slice(0, 8);
  return {
    schema: BUNDLE_SCHEMA,
    version: version ?? `${updated || "undated"}+${digest}`,
    categories,
    pages,
  };
}
