/*
 * Static prototype data. Screens and components never read the objects below directly: they call
 * the get*() functions at the bottom, which are the boundary a real backend (the app's view models,
 * or a database API) would replace. Everything is fixed: the generator uses a seeded LCG, so every
 * load produces the same data. No timers, no live updates.
 */
(function () {
  "use strict";

  // ---- deterministic generator ----
  let seed = 20261028;
  const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
  const pick = (a) => a[Math.floor(rnd() * a.length)];
  const hex2 = (n) => n.toString(16).toUpperCase().padStart(2, "0");
  const mac = () => Array.from({ length: 6 }, () => hex2(Math.floor(rnd() * 256))).join(":");
  const bytes = (n) => Array.from({ length: n }, () => hex2(Math.floor(rnd() * 256))).join("").toLowerCase();

  const CENTER = { lat: 32.88, lon: -117.234 };

  // ---- categories (the chip row order) ----
  const CATEGORIES = [
    { id: "audio", label: "Audio", icon: "headphones" },
    { id: "lighting", label: "Lighting", icon: "bulb" },
    { id: "sensors", label: "Sensors", icon: "thermo" },
    { id: "trackers", label: "Trackers", icon: "tag" },
    { id: "wearables", label: "Wearables", icon: "watch" },
    { id: "vehicles", label: "Vehicles", icon: "car" },
    { id: "medical", label: "Medical", icon: "medical" },
  ];
  // the chip row is All + CATEGORIES: Unidentified comes right after All
  CATEGORIES.unshift({ id: "unidentified", label: "Unidentified", icon: "unknown" });

  // ---- products (wiki families) ----
  const PRODUCTS = [
    { id: "govee", name: "Govee lighting and sensors", category: "lighting", company: null, service: "EC88",
      description: "Govee and ihoment strip lights, bulbs and thermo-hygrometers.", places: 20, last: "just now" },
    { id: "airpods", name: "Apple AirPods and Beats (Proximity Pairing)", category: "audio", company: 76,
      description: "Apple and Beats earbuds and headphones announcing their case and battery state.", places: 64, last: "just now" },
    { id: "jbl", name: "JBL Flip and Charge speakers", category: "audio", company: 87,
      description: "Harman JBL portable speakers advertising their model and pairing state.", places: 7, last: "just now" },
    { id: "bose", name: "Bose QuietComfort headphones", category: "audio", company: 158,
      description: "Bose headphones advertising the Bose app service.", places: 11, last: "4 min ago" },
    { id: "hue", name: "Philips Hue Bluetooth lights", category: "lighting", company: null, service: "FE0F",
      description: "Hue bulbs and lamps that pair over Bluetooth without a bridge.", places: 3, last: "just now" },
    { id: "xiaomi", name: "Xiaomi Mi Home BLE sensors (MiBeacon)", category: "sensors", company: null, service: "FE95",
      description: "Xiaomi thermometers and sensors sending MiBeacon service data.", places: 9, last: "just now" },
    { id: "switchbot", name: "SwitchBot Meter and Hub sensors", category: "sensors", company: 2409, service: "FD3D",
      description: "SwitchBot meters advertising temperature and humidity.", places: 4, last: "just now" },
    { id: "tile", name: "Tile trackers", category: "trackers", company: null, service: "FEED",
      description: "Tile Mate, Slim and Pro item trackers.", places: 38, last: "just now" },
    { id: "smarttag", name: "Samsung Galaxy SmartTag and SmartTag2 offline finding beacons", category: "trackers", company: 117, service: "FD5A",
      description: "Samsung trackers broadcasting for SmartThings Find.", places: 26, last: "just now" },
    { id: "fitbit", name: "Fitbit and Google Pixel Watch wearables", category: "wearables", company: null, service: "FEAA",
      description: "Fitbit trackers and Pixel Watch advertising for phone sync.", places: 15, last: "2 min ago" },
    { id: "tesla", name: "Tesla vehicle phone key", category: "vehicles", company: null, service: null,
      description: "Tesla cars advertising for phone-as-key.", places: 6, last: "just now" },
    // seen before only (All)
    { id: "inkbird", name: "Inkbird IBS-TH temperature and humidity sensors", category: "sensors", company: null, service: "FFF0",
      description: "Inkbird sensors with readings in the manufacturer data.", places: 2, last: "2 h ago" },
    { id: "nanoleaf", name: "Nanoleaf Essentials", category: "lighting", company: 2059, service: null,
      description: "Nanoleaf bulbs and strips commissioning over Bluetooth.", places: 1, last: "3 d ago" },
    { id: "dexcom", name: "Dexcom G7 continuous glucose monitor", category: "medical", company: 208, service: "FEBC",
      description: "Dexcom sensors advertising to their paired phone.", places: 2, last: "1 d ago" },
    { id: "ouraring", name: "Oura Ring", category: "wearables", company: 1797, service: null,
      description: "Oura smart rings advertising for sync.", places: 1, last: "5 h ago" },
  ];
  const PRODUCT = Object.fromEntries(PRODUCTS.map((p) => [p.id, p]));

  const COMPANY_NAMES = { 76: "Apple, Inc.", 87: "Harman International", 117: "Samsung Electronics", 158: "Bose Corporation",
    208: "Dexcom, Inc.", 1797: "Oura Health Oy", 2059: "Nanoleaf", 2409: "Woan Technology", 6: "Microsoft", 89: "Nordic Semiconductor" };

  // ---- the live scan list: 30 devices in range (static) ----
  // [name, product id or null, company (for unidentified), rssi, stale, connectable, legacy]
  const LIVE = [
    ["ihoment_H6008_1A2B", "govee", null, -48, false, true, true],
    ["AirPods Pro", "airpods", null, -52, false, false, true],
    ["Govee_H6199_RGBIC_LED_Strip_Living_Room_Cabinet", "govee", null, -55, false, true, true],
    [null, "tile", null, -58, false, true, true],
    ["JBL Flip 6", "jbl", null, -61, false, true, true],
    ["LYWSD03MMC", "xiaomi", null, -63, false, true, true],
    [null, null, null, -64, false, false, true],
    ["GVH5075_4C21", "govee", null, -66, false, false, true],
    ["Hue color lamp", "hue", null, -67, true, true, true],
    ["SwitchBot Meter Plus", "switchbot", null, -69, false, true, true],
    ["[TV] Samsung 7 Series (55)", null, 117, -70, false, true, true],
    ["Bose QC Ultra Headphones", "bose", null, -72, false, true, false],
    [null, "smarttag", null, -73, false, false, false],
    ["AirPods Max", "airpods", null, -74, true, false, true],
    ["Charge 5", "jbl", null, -75, false, true, true],
    [null, null, 6, -76, false, false, true],
    [null, null, 76, -76, false, false, true],
    ["S7d1c2a9e0b4f61a3C", "tesla", null, -77, false, true, true],
    ["Thermostat Hallway Upstairs Remote Sensor (Back Bedroom)", null, null, -78, false, true, true],
    ["Charge 3", "fitbit", null, -79, false, true, true],
    [null, "smarttag", null, -81, false, false, false],
    ["ELK-BLEDOM", null, null, -82, false, true, true],
    [null, null, 89, -83, true, true, true],
    ["GVH5102_9E0A", "govee", null, -84, false, false, true],
    ["Nordic_UART", null, 89, -85, false, true, true],
    [null, "tile", null, -86, false, true, true],
    ["MJ_HT_V1", "xiaomi", null, -87, true, true, true],
    [null, null, null, -88, false, false, true],
    [null, null, 76, -90, false, false, true],
    ["BT05", null, null, -89, false, true, true],
    [null, null, null, -91, true, false, true],
    ["SmartLock-8F2C", null, null, -93, false, true, true],
  ];

  function history(rssi, stale, i) {
    // 30 samples, one every 2 s, ending now (or 20 s ago for stale rows)
    const end = stale ? 20 : 30;
    return Array.from({ length: 30 }, (_, k) =>
      k >= end ? null : k === end - 1 ? rssi : Math.round(rssi + 5 * Math.sin(k * 0.6 + i) + 2.5 * Math.sin(k * 1.7 + i * 2)));
  }

  function structuresFor(name, product, company) {
    const s = [{ type: "01", data: "06" }];
    if (name) s.push({ type: "09", data: Array.from(name).map((c) => c.charCodeAt(0).toString(16).padStart(2, "0")).join("") });
    const p = product ? PRODUCT[product] : null;
    const cid = p ? p.company : company;
    const svc = p ? p.service : null;
    if (svc) s.push({ type: "03", data: svc.slice(2).toLowerCase() + svc.slice(0, 2).toLowerCase() });
    if (svc) s.push({ type: "16", data: svc.slice(2).toLowerCase() + svc.slice(0, 2).toLowerCase() + bytes(8) });
    if (cid != null) s.push({ type: "FF", data: (cid & 0xff).toString(16).padStart(2, "0") + (cid >> 8).toString(16).padStart(2, "0") + bytes(10) });
    if (!svc && cid == null) s.push({ type: "FF", data: "ffff" + bytes(6) });
    s.push({ type: "0A", data: "f4" });
    return s.map((x) => ({ ...x, length: x.data.length / 2 + 1 }));
  }

  function makeDevice(row, i, extra) {
    const [name, product, company, rssi, stale, connectable, legacy] = row;
    const p = product ? PRODUCT[product] : null;
    const cid = p ? p.company : company;
    return {
      addr: mac(), name, product, rssi, stale, connectable, legacy,
      category: p ? p.category : "unidentified",
      company: cid,
      history: extra ? null : history(rssi, stale, i),
      structures: structuresFor(name, product, company),
      txPower: -12,
    };
  }

  const DEVICES = LIVE.map((r, i) => makeDevice(r, i, false));

  // ---- recordings and routes ----
  function route(phase, scale, n) {
    // a closed wiggly loop around the center
    return Array.from({ length: n }, (_, k) => {
      const t = (k / (n - 1)) * Math.PI * 1.3 + phase; // an open arc: Start and End stay apart
      return [
        +(CENTER.lon + scale * (0.0048 * Math.cos(t) + 0.0006 * Math.sin(5 * t))).toFixed(6),
        +(CENTER.lat + scale * (0.0034 * Math.sin(t) + 0.0005 * Math.cos(4 * t))).toFixed(6),
      ];
    });
  }

  const RECORDINGS = [
    { id: "now", current: true, day: "2026-10-28", time: "7:12 PM", minutes: 12, meters: 640, devices: 26, route: route(0.4, 0.45, 40) },
    { id: "r1", day: "2026-10-28", time: "6:05 PM", minutes: 23, meters: 1800, devices: 214, route: route(0, 1, 90) },
    { id: "r2", day: "2026-10-28", time: "12:10 PM", minutes: 8, meters: 0, devices: 41, route: [[CENTER.lon + 0.001, CENTER.lat - 0.0008]] },
    { id: "r3", day: "2026-10-27", time: "5:32 PM", minutes: 41, meters: 3200, devices: 388, route: route(1.2, 1.25, 120) },
    { id: "r4", day: "2026-10-27", time: "3:10 PM", minutes: 2, meters: 0, devices: 0, route: [] }, // no data: History hides it
    { id: "r5", day: "2026-10-27", time: "9:02 AM", minutes: 15, meters: 950, devices: 122, route: route(2.4, 0.7, 60) },
    { id: "r6", day: "2026-10-25", time: "2:14 PM", minutes: 65, meters: 4600, devices: 512, route: route(3.1, 1.4, 140) },
  ];

  // names seen per product for the devices that only appear in recordings
  const EXTRA = {
    "": [null, null, null, null, "Pixel 8", "Galaxy Buds2 Pro", "WH-1000XM5", "Instinct 2", "LE_WH-1000XM4", "Echo Dot-4F2"],
    govee: ["ihoment_H6159_77B1", "Govee_H6172_0D3E", "GVH5179_21AF"], airpods: ["AirPods", "AirPods Pro", "Beats Studio Buds"],
    tile: [null, "Tile"], smarttag: [null], xiaomi: ["LYWSD03MMC", "MJ_HT_V1"], jbl: ["JBL Flip 5", "JBL Charge 4"],
    fitbit: ["Charge 6", "Inspire 3"], bose: ["Bose QC45", "Bose Flex SoundLink"], switchbot: ["WoMeter", null],
    tesla: ["S3f09a1c27be4d580C", "Sb81d2c0e94a7f312C"], hue: ["Hue white lamp", "Hue go"],
  };
  const EXTRA_PRODUCTS = ["", "", "", "govee", "airpods", "tile", "smarttag", "xiaomi", "jbl", "fitbit", "bose", "switchbot", "tesla", "hue"];
  const extraDevices = {};

  function recordingDevices(rec) {
    if (rec.devices === 0) return [];
    const pts = rec.route.length ? rec.route : [[CENTER.lon, CENTER.lat]];
    const live = rec.current ? DEVICES.slice(0, rec.devices) : DEVICES.slice(0, Math.min(18, rec.devices));
    const out = live.map((d, i) => ({ addr: d.addr, at: jitter(pts[(i * 7) % pts.length]), rssi: d.rssi }));
    for (let i = out.length; i < rec.devices; i++) {
      const prod = pick(EXTRA_PRODUCTS) || null;
      const name = pick(EXTRA[prod || ""]);
      const d = makeDevice([name, prod, prod ? null : pick([null, 6, 89, 117]), -50 - Math.floor(rnd() * 45), true, rnd() > 0.4, rnd() > 0.2], i, true);
      extraDevices[d.addr] = d;
      out.push({ addr: d.addr, at: jitter(pts[Math.floor(rnd() * pts.length)]), rssi: d.rssi });
    }
    return out;
  }
  function jitter(p) { return [+(p[0] + (rnd() - 0.5) * 0.0011).toFixed(6), +(p[1] + (rnd() - 0.5) * 0.0009).toFixed(6)]; }
  RECORDINGS.forEach((r) => (r.points = recordingDevices(r)));

  const ALL = () => Object.assign(Object.fromEntries(DEVICES.map((d) => [d.addr, d])), extraDevices);
  const DEVICE = ALL();

  // every sighting of one device: a short trail around its strongest point
  const sightingCache = {};
  function sightings(addr) {
    if (sightingCache[addr]) return sightingCache[addr];
    let best = null;
    for (const r of RECORDINGS) for (const p of r.points) if (p.addr === addr && (!best || p.rssi > best.rssi)) best = p;
    if (!best) return (sightingCache[addr] = []);
    let h = 0; for (const c of addr) h = (h * 31 + c.charCodeAt(0)) >>> 0;
    const out = [{ at: best.at, rssi: best.rssi }];
    for (let k = 1; k <= 10; k++) {
      const a = (h % 360) * Math.PI / 180 + k * 0.5;
      out.push({ at: [+(best.at[0] + Math.cos(a) * 0.00028 * k).toFixed(6), +(best.at[1] + Math.sin(a) * 0.0002 * k).toFixed(6)],
        rssi: best.rssi - k * 3 });
    }
    return (sightingCache[addr] = out);
  }

  // ---- Discover ----

  const SETTINGS = {
    userId: "wyb",
    autoRecord: false, // the onboarding turns it on by default; the demo launches with it off (state 1)
    wifiOnly: true,
    codedPhy: false,
    pendingUploads: "3 files · 12.4 MB",
    hideUnnamedApple: true,
    version: "0.1.0 (debug)",
  };

  const GATT = {
    // one device with a finished discovery, for the GATT tab
    services: [
      { name: "Generic Access", uuid: "1800", chars: [["Device Name", "2A00", "Read", "JBL Flip 6"], ["Appearance", "2A01", "Read", "Speaker"]] },
      { name: "Device Information", uuid: "180A", chars: [["Manufacturer Name String", "2A29", "Read", "Harman International"], ["Model Number String", "2A24", "Read", "Flip 6"], ["Firmware Revision String", "2A26", "Read", "3.1.4"]] },
      { name: "Battery Service", uuid: "180F", chars: [["Battery Level", "2A19", "Read, Notify", "78 %"]] },
      { name: "Unknown service", uuid: "65786365-6c70-6f69-6e74-2e636f6d0000", chars: [["Unknown characteristic", "65786365-6c70-6f69-6e74-2e636f6d0001", "Write, Notify", "No value"]] },
    ],
  };

  // GATT already read: the JBL speaker
  /** A discovery's steps: Connecting, Discovering services, Reading characteristics, Disconnected. A failed step
   *  carries its reason and ends the list. */
  function gattSteps(services, fail) {
    const chars = services.reduce((n, sv) => n + sv.chars.length, 0);
    const steps = [
      { label: "Connecting…", detail: "0.8 s" },
      { label: "Discovering services…", detail: `${services.length} services` },
      { label: "Reading characteristics", detail: `${chars}/${chars}`, progress: `${Math.ceil(chars * 0.6)}/${chars}` },
      { label: "Disconnected", detail: "" },
    ];
    if (!fail) return steps;
    return [...steps.slice(0, fail.at), { ...steps[fail.at], failed: true, detail: fail.reason }];
  }
  const gattRead = {};
  DEVICES.filter((d) => d.name === "JBL Flip 6").forEach((d) => (gattRead[d.addr] = { ok: true, steps: gattSteps(GATT.services), services: GATT.services }));

  // ---- sessions: the stored recordings plus the ones recorded in this run ----
  let current = null, runs = 0, firstRun = false; // firstRun: the demo of a brand-new user, no stored recordings
  const finished = [];
  const stored = () => (firstRun ? [] : RECORDINGS.filter((r) => !r.current));
  /** The union of all local data: every session's devices (each at its strongest point) and every route. */
  function allSessions() {
    const all = [...(current ? [current] : []), ...finished, ...stored().filter((r) => r.devices > 0)];
    const best = {};
    for (const r of all) for (const p of r.points) if (!best[p.addr] || p.rssi > best[p.addr].rssi) best[p.addr] = p;
    const points = Object.values(best);
    return { id: "all", all: true, current: false, sessions: all.length, devices: points.length,
      route: [], routes: all.map((r) => r.route).filter((rt) => rt.length > 1), points };
  }
  const session = (id) => (id === "all" ? allSessions()
    : current && current.id === id ? current : finished.find((r) => r.id === id) || RECORDINGS.find((r) => r.id === id));

  // ---- search: one pure rule, mirrored by the Rust core ----
  /**
   * Does a device match the query? query is trimmed; empty matches nothing (the caller clears the search).
   * Name: case-insensitive substring. MAC: only when the query has a digit; both sides lose ":" and "-" and are
   * lowercased, then substring. Either rule matching is a match.
   */
  function matchDevice(query, name, addr) {
    const q = String(query || "").trim();
    if (!q) return false;
    if ((name || "").toLowerCase().includes(q.toLowerCase())) return true;
    if (!/[0-9]/.test(q)) return false;
    const norm = (x) => String(x).replace(/[:-]/g, "").toLowerCase();
    return norm(addr || "").includes(norm(q));
  }
  /** The addresses of a session's devices (scope: a session id, "all" for All sessions) that match the query, in
   *  the session's order; null for an empty query (no search). */
  function searchDevices(query, scope) {
    if (!String(query || "").trim()) return null;
    const r = session(scope);
    if (!r) return [];
    return r.points.map((p) => DEVICE[p.addr]).filter((d) => matchDevice(query, d.name, d.addr)).map((d) => d.addr);
  }

  // ---- the boundary ----
  const clone = (x) => JSON.parse(JSON.stringify(x));
  const WIKI = "https://wirelesswiki.org/p/"; // the wiki's family page route (wiki-site src/pages/p/[id])
  const withProduct = (d) => ({ ...clone(d), productName: d.product ? PRODUCT[d.product].name : null,
    wikiUrl: d.product ? WIKI + d.product : null,
    companyName: d.company != null ? COMPANY_NAMES[d.company] || null : null });

  window.CluetoothData = {
    getCategories: () => clone(CATEGORIES),
    /** The live scan list, in arrival order. */
    getDevices: () => DEVICES.map(withProduct),
    getDevice: (addr) => (DEVICE[addr] ? withProduct(DEVICE[addr]) : null),
    /** Every point where one device was scanned: [{at: [lon, lat], rssi}]. */
    getDeviceSightings: (addr) => clone(sightings(addr)),
    /** The last GATT discovery of a device, or null if it hasn't been read. */
    getDeviceGatt: (addr) => (gattRead[addr] ? clone(gattRead[addr]) : null),
    /** Runs a (static) GATT discovery and returns { ok, steps, services }. Non-connectable devices time out while
     *  connecting; "ELK-BLEDOM" drops the connection while its characteristics are read. */
    discoverGatt: (addr) => {
      const d = DEVICE[addr];
      if (!d) return null;
      const services = [
        { name: "Generic Access", uuid: "1800", chars: [["Device Name", "2A00", "Read", d.name || "(empty)"], ["Appearance", "2A01", "Read", "Unknown"]] },
        { name: "Device Information", uuid: "180A", chars: [["Manufacturer Name String", "2A29", "Read", COMPANY_NAMES[d.company] || "No value"]] },
        { name: "Unknown service", uuid: "0000fff0-0000-1000-8000-00805f9b34fb", chars: [["Unknown characteristic", "0000fff1-0000-1000-8000-00805f9b34fb", "Notify", "No value"]] },
      ];
      const fail = !d.connectable ? { at: 0, reason: "Timed out" } : d.name === "ELK-BLEDOM" ? { at: 2, reason: "Disconnected unexpectedly" } : null;
      const result = { ok: !fail, steps: gattSteps(services, fail), services: fail ? [] : services };
      if (!fail) gattRead[addr] = result;
      return clone(result);
    },
    /** "All sessions": every device and product of every session, frozen. Its id ("all") works wherever a session id does. */
    getAllSessionsView: () => { const v = allSessions(); const { points, ...meta } = v; return clone(meta); },
    /** The user's current location. */
    getMyLocation: () => ({ at: [CENTER.lon + 0.0004, CENTER.lat + 0.00012], lat: 32.88012, lon: -117.23421, accuracy: 8 }),
    /** All finished sessions, newest first: this run's recordings, then the stored ones (including ones without data). */
    getRecordings: () => [...finished, ...stored()].map(({ points, ...r }) => clone(r)),
    /** Demo only: behave like a brand-new install with no recordings. */
    useFirstRunDemo: () => { firstRun = true; },
    /** The session being recorded, or null. */
    getCurrentRecording: () => (current ? clone(current) : null),
    /** One session with its route and one strongest point per device. */
    getRecording: (id) => { const r = session(id); return r ? clone(r) : null; },
    /** Starts recording: a new current session (static: always the same demo data). Returns its id. */
    startRecording: () => {
      current = { ...clone(RECORDINGS[0]), id: `live${++runs}`, current: true };
      return current.id;
    },
    /** Stops recording: the current session becomes a finished one. Returns its id. */
    stopRecording: () => {
      if (!current) return null;
      const r = { ...current, current: false };
      finished.unshift(r);
      current = null;
      return r.id;
    },
    /** One row per device of a session; live values for the session being recorded, the strongest for others. */
    getSessionDevices: (id) => {
      const r = session(id);
      if (!r) return [];
      return r.points.map((p) => {
        const d = withProduct(DEVICE[p.addr]);
        return r.current ? { ...d, best: p.at } : { ...d, rssi: p.rssi, history: null, stale: false, best: p.at };
      });
    },
    /** Discover for one session: categories with product cards. mode is "nearby" (live only) or "all". */
    getProducts: (mode, id) => {
      const r = session(id);
      if (!r) return [];
      const live = !!r.current, count = {}, inRange = {};
      for (const p of r.points) {
        const d = DEVICE[p.addr], key = d.product || "unidentified";
        count[key] = (count[key] || 0) + 1;
        if (live && !d.stale) inRange[key] = (inRange[key] || 0) + 1;
      }
      const card = (pid, name, category, last) => ({ id: pid, name, category, live, count: count[pid] || 0, inRange: inRange[pid] || 0, last });
      const keep = (pid) => count[pid] && (mode === "all" || inRange[pid]);
      const groups = [];
      for (const c of CATEGORIES) {
        if (c.id === "unidentified") continue;
        const items = PRODUCTS.filter((p) => p.category === c.id && keep(p.id)).map((p) => card(p.id, p.name, p.category, p.last));
        if (items.length) groups.push({ category: c.id, label: c.label, icon: c.icon, products: items });
      }
      if (keep("unidentified")) groups.push({ category: "unidentified", label: "Unidentified", icon: "unknown",
        products: [card("unidentified", "Unidentified devices", "unidentified", "just now")] });
      return groups;
    },
    /** One product with its devices and where it was seen. */
    getProduct: (id) => {
      const isUnknown = id === "unidentified";
      const p = isUnknown ? { id, name: "Unidentified devices", category: "unidentified", description: "Devices no wiki family matches yet.", places: 31, last: "just now" } : PRODUCT[id];
      if (!p) return null;
      const devices = Object.values(DEVICE).filter((d) => (isUnknown ? !d.product : d.product === id)).map(withProduct);
      return clone({ ...p, identified: !isUnknown, devices });
    },
    getSettings: () => clone(SETTINGS),
    /** Search (see matchDevice): matching addresses within a session view, or null for an empty query. */
    searchDevices,
    matchDevice,
  };
})();
