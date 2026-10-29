#!/usr/bin/env node
/*
 * Renders the HTML prototype (scanner/design/ui-v2) in headless Chromium and writes PNGs for the fidelity
 * comparison with the Compose gallery (docs/v2-api.md §6.4): the full page, and every #gallery cell cropped.
 *
 *   node prototype-shots.mjs --chrome <path> --out <dir> [--url http://127.0.0.1:8123/] [--hash #gallery]
 *        [--width 412] [--height 915] [--scale 2.625] [--dark]
 *
 * It talks to Chromium over the DevTools protocol (Node's built-in WebSocket); no npm packages. Serve the
 * prototype first (python3 -m http.server in scanner/design/ui-v2).
 */
import { spawn } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const arg = (k, d) => { const i = process.argv.indexOf(`--${k}`); return i < 0 ? d : process.argv[i + 1]; };
const flag = (k) => process.argv.includes(`--${k}`);
const chrome = arg("chrome");
const out = arg("out");
const url = arg("url", "http://127.0.0.1:8123/") + arg("hash", "#gallery");
const width = +arg("width", 412), height = +arg("height", 915), scale = +arg("scale", 2.625);
const dark = flag("dark");
const cellsOnly = flag("cells-only");
if (!chrome || !out) { console.error("usage: --chrome <path> --out <dir>"); process.exit(2); }
mkdirSync(out, { recursive: true });

const profile = mkdtempSync(join(tmpdir(), "proto-shots-"));
const proc = spawn(chrome, ["--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
  "--hide-scrollbars", "--force-color-profile=srgb", "--font-render-hinting=none", "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
const wsUrl = await new Promise((resolve, reject) => {
  let buf = "";
  proc.stderr.on("data", (d) => { buf += d; const m = /DevTools listening on (ws:\/\/\S+)/.exec(buf); if (m) resolve(m[1]); });
  proc.on("exit", (c) => reject(new Error(`chrome exited ${c}: ${buf}`)));
});

const ws = new WebSocket(wsUrl);
await new Promise((r) => ws.addEventListener("open", r, { once: true }));
let id = 0;
const pending = new Map(), waiters = [];
ws.addEventListener("message", (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) { const { resolve, reject } = pending.get(msg.id); pending.delete(msg.id); msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result); }
  else for (const w of waiters) if (w.method === msg.method) { waiters.splice(waiters.indexOf(w), 1); w.resolve(msg.params); break; }
});
const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
  const m = { id: ++id, method, params }; if (sessionId) m.sessionId = sessionId;
  pending.set(m.id, { resolve, reject }); ws.send(JSON.stringify(m));
});
const once = (method) => new Promise((resolve) => waiters.push({ method, resolve }));

try {
  const { targetId } = await send("Target.createTarget", { url: "about:blank" });
  const { sessionId: s } = await send("Target.attachToTarget", { targetId, flatten: true });
  await send("Page.enable", {}, s);
  await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: scale, mobile: false }, s);
  await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: dark ? "dark" : "light" }] }, s);
  const loaded = once("Page.loadEventFired");
  await send("Page.navigate", { url }, s);
  await loaded;
  await new Promise((r) => setTimeout(r, 2500)); // maps and fonts settle
  // no focus ring from the gallery's autofocus, no caret
  await send("Runtime.evaluate", { expression: "document.activeElement && document.activeElement.blur && 0" }, s);
  const shot = async (clip) => Buffer.from((await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true, ...(clip ? { clip: { ...clip, scale: 1 } } : {}) }, s)).data, "base64");
  if (!cellsOnly) writeFileSync(join(out, "page.png"), await shot());
  const { result } = await send("Runtime.evaluate", { returnByValue: true, expression: `(() => {
    const cells = []; let sect = "";
    for (const el of document.querySelectorAll(".g-sect, .g-cell")) {
      if (el.classList.contains("g-sect")) { sect = el.firstChild.textContent.trim(); continue; }
      const box = el.querySelector(".g-box") || el, r = box.getBoundingClientRect();
      cells.push({ sect, label: (el.querySelector("small") || {}).textContent || "", x: r.left + scrollX, y: r.top + scrollY, width: r.width, height: r.height });
    }
    return cells; })()` }, s);
  const slug = (t) => t.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 60);
  const index = [];
  for (const c of result.value || []) {
    if (!c.width || !c.height) continue;
    const name = `${slug(c.sect)}__${slug(c.label) || "cell"}.png`;
    writeFileSync(join(out, name), await shot({ x: c.x, y: c.y, width: c.width, height: c.height }));
    index.push({ ...c, file: name });
  }
  writeFileSync(join(out, "cells.json"), JSON.stringify(index, null, 2));
  if (flag("dump-data")) {
    const { result: data } = await send("Runtime.evaluate", { returnByValue: true, expression: `(() => {
      const D = window.CluetoothData, devs = D.getDevices();
      return { devices: devs, products: D.getProducts("all", "now"), govee: D.getProduct("govee") }; })()` }, s);
    writeFileSync(join(out, "data.json"), JSON.stringify(data.value, null, 1));
  }
  console.log(`${index.length} cells → ${out}`);
} finally {
  ws.close();
  proc.kill();
  rmSync(profile, { recursive: true, force: true });
}
