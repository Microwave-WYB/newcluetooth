import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { buildBundle, parseFrontMatter } from "./bundle.mjs";

const read = (name) => JSON.parse(readFileSync(new URL(name, import.meta.url), "utf8"));
const legacy = read("./legacy-mapping.json");
const labelsZh = read("./category-labels.zh.json");

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
const zh = { "audio-device": "音频设备", printer: "打印机" };

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
    labelsZh: zh,
  });
  assert.equal(bundle.schema, 1);
  assert.match(bundle.version, /^2026-10-26\+[0-9a-f]{8}$/);
  assert.deepEqual(bundle.categories, [
    { id: "audio-device", label: "Audio device", label_zh: "音频设备" },
    { id: "printer", label: "Printer", label_zh: "打印机" },
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
    labelsZh: zh,
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

test("version depends on content, not on file order or time", () => {
  const pages = (title) => ({ "device--a": { frontMatter: `title: ${title}\nkind: device\none_line: x\nupdated: 2026-10-01` } });
  const v = (title) => buildBundle({ wikiDir: wiki(pages(title)), legacy: noLegacy, labelsZh: zh }).version;
  assert.equal(v("A"), v("A"));
  assert.notEqual(v("A"), v("B"));
});

test("unknown category, unknown kind and missing Chinese label are errors", () => {
  const bad = (frontMatter, options = {}) =>
    buildBundle({ wikiDir: wiki({ "device--a": { frontMatter } }), legacy: noLegacy, labelsZh: zh, ...options });
  assert.throws(() => bad("title: A\nkind: device\none_line: x\ncategories: [nope]"), /unknown category "nope"/);
  assert.throws(() => bad("title: A\nkind: device\none_line: x\ncategories: [retired]"), /unknown category "retired"/);
  assert.throws(() => bad("title: A\nkind: gadget\none_line: x"), /directory does not start/);
  assert.throws(() => buildBundle({ wikiDir: wiki({ "gadget--a": { frontMatter: "title: A\nkind: gadget\none_line: x" } }), legacy: noLegacy, labelsZh: zh }), /neither device\|platform\|identifier/);
  assert.throws(() => bad("title: A\nkind: device\none_line: x", { labelsZh: { "audio-device": "x" } }), /missing Chinese label for printer/);
  assert.throws(() => bad("title: A\nkind: device"), /one_line must be a non-empty string/);
});

test("the checked-in mapping and labels cover the draft vocabulary and the old snapshot kinds", () => {
  const kinds = new Set(Object.values(legacy.kinds));
  assert.deepEqual([...kinds].sort(), ["device", "identifier", "platform"]);
  for (const id of Object.values(legacy.categories).flat()) assert.ok(labelsZh[id], `no Chinese label for ${id}`);
});
