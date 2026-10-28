import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { buildBundle, parseFrontMatter } from "./bundle.mjs";

const read = (name) => JSON.parse(readFileSync(new URL(name, import.meta.url), "utf8"));
const legacy = read("./legacy-mapping.json");

const VOCAB = {
  version: 1,
  facets: {
    categories: [
      { id: "audio-device", label: "Audio device", status: "active" },
      { id: "printer", label: "Printer", status: "active" },
      { id: "retired", label: "Retired", status: "retired" },
    ],
  },
};

function wiki(pages, vocab = VOCAB) {
  const dir = mkdtempSync(join(tmpdir(), "wiki-"));
  writeFileSync(join(dir, "categories.draft.json"), JSON.stringify(vocab));
  for (const [id, { frontMatter, rule }] of Object.entries(pages)) {
    mkdirSync(join(dir, "wiki-content", id), { recursive: true });
    writeFileSync(join(dir, "wiki-content", id, "index.md"), `---\n${frontMatter}\n---\n\nBody\n`);
    if (rule) writeFileSync(join(dir, "wiki-content", id, "rule.json"), JSON.stringify(rule));
  }
  return dir;
}

const noLegacy = { kinds: {}, categories: {} };

test("old format: JSON front matter, kinds and categories from the mapping", () => {
  const dir = wiki({
    "model--jabra-x": {
      frontMatter: JSON.stringify({ title: "Jabra X", kind: "model", one_line: "Earbuds", updated: "2026-10-25", entity: "model/jabra-x" }, null, 2),
      rule: { name: { prefix: "Jabra" } },
    },
    "protocol--p": { frontMatter: JSON.stringify({ title: "P", kind: "protocol", one_line: "A protocol", updated: "2026-10-26" }) },
  });
  const bundle = buildBundle({
    wikiDir: dir,
    legacy: { kinds: { model: "device", protocol: "platform" }, categories: { "model--jabra-x": ["audio-device"] } },
  });
  assert.equal(bundle.schema, 1);
  assert.match(bundle.version, /^2026-10-26\+[0-9a-f]{8}$/);
  assert.deepEqual(bundle.categories, [
    { id: "audio-device", label: "Audio device" },
    { id: "printer", label: "Printer" },
  ]);
  assert.deepEqual(bundle.pages, [
    {
      id: "model--jabra-x", title: "Jabra X", kind: "device", one_line: "Earbuds",
      categories: ["audio-device"], entity: "model/jabra-x", has_rule: true, rule: { name: { prefix: "Jabra" } },
    },
    { id: "protocol--p", title: "P", kind: "platform", one_line: "A protocol", entity: null, has_rule: false },
  ]);
});

test("new format: YAML front matter with categories; real categories beat the mapping", () => {
  const dir = wiki({
    "device--printer-1": {
      frontMatter: "title: Printer 1\nkind: device\none_line: >-\n  A label printer\n  with two lines.\ncategories:\n  - printer\nupdated: 2026-11-02\nentity: device/printer-1",
    },
  });
  const bundle = buildBundle({
    wikiDir: dir,
    legacy: { kinds: {}, categories: { "device--printer-1": ["audio-device"] } },
  });
  assert.deepEqual(bundle.pages[0].categories, ["printer"]);
  assert.equal(bundle.pages[0].one_line, "A label printer with two lines.");
  assert.match(bundle.version, /^2026-11-02\+/);
});

test("parseFrontMatter reads both formats", () => {
  assert.deepEqual(parseFrontMatter('---\n{"title": "a"}\n---\nbody'), { title: "a" });
  assert.deepEqual(parseFrontMatter("---\ntitle: a\n---\nbody"), { title: "a" });
  assert.throws(() => parseFrontMatter("no front matter"), /no front matter/);
});

test("front matter tolerates a BOM, CRLF and a closing --- with no newline after it", () => {
  assert.deepEqual(parseFrontMatter("\uFEFF---\r\ntitle: a\r\n---\r\nbody"), { title: "a" });
  assert.deepEqual(parseFrontMatter("---\ntitle: a\n---"), { title: "a" });
  assert.deepEqual(parseFrontMatter("---\ntitle: a\n---  \n"), { title: "a" });
});

test("a BOM in index.md, rule.json and the vocabulary does not break the export", () => {
  const dir = wiki({ "device--a": { frontMatter: "title: A\nkind: device\none_line: x", rule: { company: [76] } } });
  const bom = (file) => writeFileSync(file, "\uFEFF" + readFileSync(file, "utf8"));
  bom(join(dir, "categories.draft.json"));
  bom(join(dir, "wiki-content/device--a/index.md"));
  bom(join(dir, "wiki-content/device--a/rule.json"));
  const bundle = buildBundle({ wikiDir: dir, legacy: noLegacy });
  assert.deepEqual(bundle.pages[0].rule, { company: [76] });
});

test("a stale legacy mapping warns instead of aborting", () => {
  const dir = wiki({ "model--a": { frontMatter: JSON.stringify({ title: "A", kind: "model", one_line: "x" }) } });
  const warnings = [];
  const bundle = buildBundle({
    wikiDir: dir,
    legacy: { kinds: { model: "device" }, categories: { "model--a": ["audio-device", "gone"], "model--missing": ["printer"] } },
    warn: (m) => warnings.push(m),
  });
  assert.deepEqual(bundle.pages[0].categories, ["audio-device"]);
  assert.equal(warnings.length, 2);
  assert.match(warnings[0], /model--a: category "gone" is not in the vocabulary/);
  assert.match(warnings[1], /no page model--missing/);
});

test("rules_version moves with the rules only; version moves with anything", () => {
  const build = (title, ruleCompany, label) =>
    buildBundle({
      wikiDir: wiki(
        { "device--a": { frontMatter: `title: ${title}\nkind: device\none_line: x\nupdated: 2026-10-01`, rule: { company: [ruleCompany] } } },
        { ...VOCAB, facets: { categories: VOCAB.facets.categories.map((c) => (c.id === "printer" ? { ...c, label } : c)) } },
      ),
      legacy: noLegacy,
    });
  const base = build("A", 76, "Printer");
  assert.match(base.rules_version, /^[0-9a-f]{16}$/);
  const retitled = build("B", 76, "Printer");
  const relabelled = build("A", 76, "Print device");
  const reruled = build("A", 77, "Printer");
  assert.equal(retitled.rules_version, base.rules_version);
  assert.equal(relabelled.rules_version, base.rules_version);
  assert.notEqual(retitled.version, base.version);
  assert.notEqual(relabelled.version, base.version);
  assert.notEqual(reruled.rules_version, base.rules_version);
});

test("version depends on content, not on file order or time", () => {
  const pages = (title) => ({ "device--a": { frontMatter: `title: ${title}\nkind: device\none_line: x\nupdated: 2026-10-01` } });
  const v = (title) => buildBundle({ wikiDir: wiki(pages(title)), legacy: noLegacy }).version;
  assert.equal(v("A"), v("A"));
  assert.notEqual(v("A"), v("B"));
});

test("unknown category, unknown kind and missing fields are errors", () => {
  const bad = (frontMatter, options = {}) =>
    buildBundle({ wikiDir: wiki({ "device--a": { frontMatter } }), legacy: noLegacy, ...options });
  assert.throws(() => bad("title: A\nkind: device\none_line: x\ncategories: [nope]"), /unknown category "nope"/);
  assert.throws(() => bad("title: A\nkind: device\none_line: x\ncategories: [retired]"), /unknown category "retired"/);
  assert.throws(() => bad("title: A\nkind: gadget\none_line: x"), /directory does not start/);
  assert.throws(() => buildBundle({ wikiDir: wiki({ "gadget--a": { frontMatter: "title: A\nkind: gadget\none_line: x" } }), legacy: noLegacy }), /neither device\|platform\|identifier/);
  assert.throws(() => bad("title: A\nkind: device"), /one_line must be a non-empty string/);
});

test("the checked-in mapping covers the draft vocabulary and the old snapshot kinds", () => {
  const kinds = new Set(Object.values(legacy.kinds));
  assert.deepEqual([...kinds].sort(), ["device", "identifier", "platform"]);
});
