/*
 * Cluetooth UI v2 prototype. Three parts, top to bottom:
 *   1. UI: stateless components (HTML string builders), one per design-system component.
 *   2. Screens: compose components; they get data only through window.CluetoothData (data.js).
 *   3. App: state, the page stack and click handling. Static: no timers, no live updates.
 */
(function () {
  "use strict";
  const D = window.CluetoothData;
  const $ = (s, r = document) => r.querySelector(s);
  const tok = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const dark = () => matchMedia("(prefers-color-scheme: dark)").matches;
  const MAP_STYLE = () => `https://tiles.openfreemap.org/styles/${dark() ? "dark" : "liberty"}`;

  // =============================================================================================
  // 1. UI components
  // =============================================================================================

  const ICONS = {
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    compass: '<circle cx="12" cy="12" r="9"/><path d="m15.5 8.5-2 5-5 2 2-5z"/>',
    collapse: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    back: '<path d="M15 18l-6-6 6-6"/>',
    chevron: '<path d="M9 6l6 6-6 6"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    density: '<path d="M12 3 2 8l10 5 10-5z"/><path d="m2 13 10 5 10-5"/>',
    history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 2"/>',
    settings: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"/>',
    map: '<path d="M9 4 3 6v14l6-2 6 2 6-2V4l-6 2z"/><path d="M9 4v14M15 6v14"/>',
    headphones: '<path d="M3 18v-6a9 9 0 0 1 18 0v6"/><path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z"/>',
    bulb: '<path d="M9 18h6M10 22h4"/><path d="M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.3 1 2.3h6c0-1 .4-1.8 1-2.3A7 7 0 0 0 12 2z"/>',
    thermo: '<path d="M14 14.8V4.5a2.5 2.5 0 0 0-5 0v10.3a4.5 4.5 0 1 0 5 0z"/>',
    tag: '<path d="M12 2H2v10l9.3 9.3a1 1 0 0 0 1.4 0l8.6-8.6a1 1 0 0 0 0-1.4z"/><circle cx="7" cy="7" r="1.5"/>',
    watch: '<circle cx="12" cy="12" r="6"/><path d="M12 10v2l1 1"/><path d="m16.1 7.7-.8-4a2 2 0 0 0-2-1.7h-2.6a2 2 0 0 0-2 1.6l-.8 4.1M7.9 16.4l.8 4a2 2 0 0 0 2 1.6h2.7a2 2 0 0 0 2-1.6l.8-4"/>',
    car: '<path d="M3 17v-4l2-6h14l2 6v4z"/><path d="M3 13h18"/><circle cx="7.5" cy="17" r="2"/><circle cx="16.5" cy="17" r="2"/>',
    medical: '<rect x="3" y="3" width="18" height="18" rx="4"/><path d="M12 8v8M8 12h8"/>',
    external: '<path d="M7 17 17 7M9 7h8v8"/>',
    devices: '<path d="M4 7.5l9 9-4.5 4.5V3l4.5 4.5-9 9"/><path d="M16.5 9.5a3.5 3.5 0 0 1 0 5M19.5 7a7 7 0 0 1 0 10"/>', // Bluetooth rune with scan waves
    panelClose: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18M16 15l-3-3 3-3"/>',
    pause: '<rect class="solid" x="6" y="5" width="4" height="14" rx="1"/><rect class="solid" x="14" y="5" width="4" height="14" rx="1"/>',
    play: '<path class="solid" d="M8 5.5v13a1 1 0 0 0 1.5.9l10.4-6.5a1 1 0 0 0 0-1.8L9.5 4.6A1 1 0 0 0 8 5.5z"/>',
    stop: '<rect class="solid" x="6" y="6" width="12" height="12" rx="2"/>',
    unknown: '<circle cx="12" cy="12" r="9"/><path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 2.5-3 4"/><path d="M12 17.5h.01"/>',
  };
  const icon = (n, cls = "") => `<span class="icon ${cls}" aria-hidden="true"><svg viewBox="0 0 24 24">${ICONS[n]}</svg></span>`;
  const minus = (n) => String(n).replace("-", "−");
  const band = (r) => (r >= -65 ? "strong" : r >= -80 ? "fair" : "weak");

  const UI = {
    /** FloatButton: the wiki's round 44 px button. */
    floatButton: ({ name, label, action, cls = "", value = "" }) =>
      `<button class="rb ${cls}" data-action="${action}" data-value="${esc(value)}" aria-label="${esc(label)}">${icon(name)}</button>`,

    /** MorphButton: Devices at the top-right; with the devices page open, its close button at the top-left. */
    morphButton: (open, cls = "morph") =>
      `<button class="rb ${cls} ${open ? "open" : ""}" data-action="devices" aria-label="${open ? "Close devices" : "Devices"}">${icon("devices", "i-a")}${icon("collapse", "i-b")}</button>`,

    /** MenuButton: ☰ at the map's top-left; with the sidebar out, it rides the sidebar's edge as "collapse sidebar". */
    menuButton: (open, cls = "") =>
      `<button class="rb menu-btn ${open ? "open" : ""} ${cls}" data-action="sidebar" aria-label="${open ? "Close sidebar" : "Sessions and settings"}" aria-expanded="${open}">${icon("menu", "i-a")}${icon("panelClose", "i-b")}</button>`,

    /** Recorder: idle FAB, recording capsule (dot, time, Pause/Resume, Stop) and the panel above it. */
    recorder: ({ state, paused, time, meta, mapId = "", cls = "" }) => `
      <div class="recorder ${state} ${cls}" data-action="recorder" role="group" aria-label="${state === "idle" ? "Record" : "Recording"}">
        <div class="rec-meta">${esc(meta)}</div>
        <div class="rec-map" data-action="open-session-map"><div ${mapId ? `id="${mapId}"` : ""} style="height:100%"></div></div>
        <div class="rec-row">
          <span class="rec-open" data-action="recorder-open"><span class="rec-dot ${paused ? "paused" : ""}"></span><span class="rec-time">${esc(time)}</span></span>
          ${UI.iconButton({ name: paused ? "play" : "pause", label: paused ? "Resume" : "Pause", action: "pause", kind: "neutral" })}
          ${UI.iconButton({ name: "stop", label: "Stop", action: "stop", kind: "rec" })}
        </div>
      </div>`,

    /** IconButton: round, 40 px; neutral (--fill2) or rec (--rec-red with a white glyph). */
    iconButton: ({ name, label, action, kind = "neutral", value = "" }) =>
      `<button class="ib ${kind}" data-action="${action}" data-value="${esc(value)}" aria-label="${esc(label)}">${icon(name)}</button>`,

    /** Chip and ChipRow. */
    chip: ({ label, on, action, value }) =>
      `<button class="chip ${on ? "on" : ""}" data-action="${action}" data-value="${esc(value)}" aria-pressed="${on}">${esc(label)}</button>`,
    chipRow: (cats, selected, action, cls = "") =>
      `<div class="chips ${cls}">${[{ id: "all", label: "All" }, ...cats]
        .map((c) => UI.chip({ label: c.label, on: c.id === selected, action, value: c.id })).join("")}</div>`,

    /** Sparkline: the last minute, 30 samples. */
    sparkline: (h) => {
      const w = 56, ht = 22, y = (r) => (1 + ((-30 - r) / 70) * (ht - 2)).toFixed(1);
      let d = "", pen = false;
      h.forEach((r, k) => {
        if (r == null) { pen = false; return; }
        d += `${pen ? "L" : "M"}${(k * (w / (h.length - 1))).toFixed(1)} ${y(Math.max(-100, Math.min(-30, r)))}`;
        pen = true;
      });
      return `<svg class="spark" viewBox="0 0 ${w} ${ht}" preserveAspectRatio="none"><path d="${d}"/></svg>`;
    },

    /** DeviceRow: name (or "No name") and MAC; the sparkline and the RSSI number at the end. */
    deviceRow: (d, { spark = true, sel = false, action = "device" } = {}) => `
      <button class="row ${d.stale ? "stale" : ""} ${sel ? "sel" : ""}" data-action="${action}" data-value="${d.addr}">
        <span><span class="row-name ${d.name ? "" : "none"}">${d.name ? esc(d.name) : "No name"}</span><span class="row-sub">${d.addr}</span></span>
        ${spark && d.history ? `<span class="band-${band(d.rssi)}">${UI.sparkline(d.history)}</span>` : "<span></span>"}
        <span class="row-rssi band-${band(d.rssi)}">${minus(d.rssi)}</span>
      </button>`,

    /** ProductCard. */
    productCard: (p, iconName) => `
      <button class="pcard" data-action="product" data-value="${p.id}">
        <span class="pcard-icon ${p.category === "unidentified" ? "muted" : ""}">${icon(iconName)}</span>
        <span class="pcard-name">${esc(p.name)}</span>
        <span class="pcard-status">${!p.live ? `${p.count} ${p.count === 1 ? "device" : "devices"}` : p.inRange ? `In range · ${p.inRange} ${p.inRange === 1 ? "device" : "devices"}` : `Last scanned ${esc(p.last)}`}</span>
      </button>`,

    /** CategorySection: a header with "›" and a row of product cards. */
    categorySection: (g) => `
      <section class="cat"><div class="cat-head">${esc(g.label)}<span class="chev">›</span></div>
        <div class="cards">${g.products.map((p) => UI.productCard(p, g.icon)).join("")}</div></section>`,

    /** Segmented control (wiki .seg). */
    segmented: (options, value, action) =>
      `<div class="seg" role="radiogroup">${options.map((o) =>
        `<button role="radio" aria-checked="${o.id === value}" data-action="${action}" data-value="${o.id}">${esc(o.label)}</button>`).join("")}</div>`,

    /** Button: primary (filled) or tonal. */
    button: ({ kind, label, action, value = "", cls = "" }) =>
      `<button class="btn ${kind} ${cls}" data-action="${action}" data-value="${esc(value)}">${esc(label)}</button>`,

    /** Switch. */
    switch: (on, action, value = "") =>
      `<button class="switch" role="switch" aria-checked="${on}" data-action="${action}" data-value="${value}"></button>`,

    /** SearchField (wiki pill). */
    searchField: (placeholder, id = "") =>
      `<label class="sf">${icon("search")}<input ${id ? `id="${id}"` : ""} type="search" placeholder="${esc(placeholder)}" autocomplete="off"></label>`,

    /** ListItem. */
    listItem: ({ title, sub = "", end = "", action = "", value = "", chevron = false, sel = false }) => {
      const tag = action ? "button" : "div";
      return `<${tag} class="item ${sel ? "sel" : ""}" ${action ? `data-action="${action}" data-value="${esc(value)}"` : ""}>
        <span class="item-text"><span class="item-title">${title}</span>${sub ? `<span class="item-sub">${sub}</span>` : ""}</span>
        ${end ? `<span class="item-end">${end}</span>` : ""}${chevron ? icon("chevron", "chev") : ""}</${tag}>`;
    },

    /** MapThumb: a clean map preview (points or route, Start/End only); the whole thumbnail is the tap target. */
    mapThumb: (action, value, id) =>
      `<button class="map-card" data-action="${action}" data-value="${esc(value)}" aria-label="Open the map"><div class="mini" id="${id}"></div></button>`,

    /** Section label. */
    sect: (t) => `<div class="sect">${esc(t)}</div>`,

    /** Sidebar content: the current session (pinned while recording), the history by day, Settings at the bottom. */
    sidebar: ({ current, all, days, selected }) => `
      <div class="side-head"><span class="brand">Cluetooth</span></div>
      <div class="side-list">
        ${current ? UI.listItem({ title: current.title, sub: current.sub, action: "session", value: current.id, sel: current.id === selected }) : ""}
        ${all ? UI.listItem({ title: "All sessions", sub: all.sub, action: "session", value: "all", sel: selected === "all" }) : ""}
        ${days.map((g) => UI.sect(g.label) + g.sessions.map((r) => UI.listItem({ title: r.title, sub: r.sub, action: "session", value: r.id, sel: r.id === selected })).join("")).join("")}
      </div>
      <div class="side-foot"><button class="nav" data-action="settings">${icon("settings")}Settings</button></div>`,

    /** RssiChart: the last minute, only the -65 and -80 ticks, an end marker in the number's color. */
    chart: (h, rssi, w) => {
      const ht = 96, plot = 76, x0 = 30, y = (r) => 4 + ((-30 - Math.max(-100, Math.min(-30, r))) / 70) * (plot - 8);
      const xs = (k) => x0 + (k * (w - x0 - 6)) / (h.length - 1);
      let d = "", pen = false, last = 0;
      h.forEach((r, k) => {
        if (r == null) { pen = false; return; }
        d += `${pen ? "L" : "M"}${xs(k).toFixed(1)} ${y(r).toFixed(1)}`; pen = true; last = k;
      });
      const tick = (v) => `<line class="tick" x1="${x0}" x2="${w}" y1="${y(v)}" y2="${y(v)}"/><text x="0" y="${y(v) + 4}">${minus(v)}</text>`;
      return `<svg class="chart" viewBox="0 0 ${w} ${ht}" width="${w}" height="${ht}">${tick(-65)}${tick(-80)}
        <path class="line" d="${d}"/>
        <circle cx="${xs(last)}" cy="${y(rssi)}" r="4.5" class="band-${band(rssi)}" fill="currentColor"/>
        <text x="${x0}" y="${ht - 2}">−1 min</text><text x="${w}" y="${ht - 2}" text-anchor="end">now</text></svg>`;
    },

    /** DeviceSheet: name, product link, MAC with RSSI, chart, map preview, Advertising / GATT tabs. */
    deviceSheet: (d, { tab, gatt, chartW, mapId = "" }) => `
      <div class="handle"></div>
        <div class="dev-head">
          <div class="dev-name"><b class="${d.name ? "" : "none"}">${d.name ? esc(d.name) : "No name"}</b></div>
          ${d.productName ? `<a class="dev-product" href="${esc(d.wikiUrl)}" target="_blank" rel="noopener">${esc(d.productName)}${icon("external")}</a>` : ""}
          <div class="dev-sub">${d.addr}<span class="dev-rssi band-${band(d.rssi)}">${minus(d.rssi)} dBm</span></div>
        </div>
      <div class="sheet-body">
        ${d.history ? `<div class="chart-wrap">${UI.chart(d.history, d.rssi, chartW)}</div>` : ""}
        ${mapId ? `<div class="dev-map">${UI.mapThumb("device-map", d.addr, mapId)}</div>` : ""}
        <div class="dev-tabs" id="dev-seg">${UI.segmented([{ id: "adv", label: "Advertising" }, { id: "gatt", label: "GATT" }], tab, "dev-tab")}</div>
        <div id="dev-tab">${tab === "adv" ? UI.advertising(d) : UI.gatt(gatt, d.addr)}</div>
      </div>`,

    /** Advertising tab: the Details list, then the raw Len / Type / Data table. */
    advertising: (d) => {
      const kv = [["Connectable", d.connectable ? "Yes" : "No"], ["Format", d.legacy ? "Legacy" : "Extended"]];
      if (d.companyName) kv.push(["Company", d.companyName]);
      const svc = d.structures.find((s) => s.type === "03");
      if (svc) kv.push(["Service", (svc.data.slice(2, 4) + svc.data.slice(0, 2)).toUpperCase()]);
      kv.push(["TX power", `${minus(d.txPower)} dBm`]);
      return `<div class="sheet-sect">Details</div>
        <dl class="kv">${kv.map(([k, v]) => `<div><dt>${k}</dt><dd>${esc(v)}</dd></div>`).join("")}</dl>
        <div class="sheet-sect">Raw data</div>
        <table class="raw"><colgroup><col class="len"><col class="type"><col></colgroup>
          <tr><th>Len</th><th>Type</th><th>Data</th></tr>
          ${d.structures.map((s) => `<tr><td class="num">${s.length}</td><td>0x${s.type}</td><td>${s.data}</td></tr>`).join("")}</table>`;
    },

    /** GATT tab: one line and the Discover GATT button until it has been read, then the services in place. */
    gatt: (g, addr = "") => g
      ? g.services.map((s) => `<div class="gatt-svc"><b>${esc(s.name)}</b><div class="dev-sub">${s.uuid}</div>
          ${s.chars.map(([n, u, p, v]) => `<div class="gatt-char">${esc(n)}<span>${u} · ${esc(p)}</span><span>${esc(v)}</span></div>`).join("")}</div>`).join("")
      : `<div class="gatt-empty"><p>GATT hasn't been read for this device yet.</p>${UI.button({ kind: "primary", label: "Discover GATT", action: "gatt", value: addr })}</div>`,

    /** MapMarker: one device point (its category icon) or a cluster (dominant icon and count). */
    markerPoint: (p, { sel, faded } = {}) =>
      `<div class="mk ${p.category === "unidentified" ? "muted" : ""} ${sel ? "sel" : ""} ${faded ? "faded" : ""}" data-action="map-point" data-value="${p.addr}">${icon(catIcon(p.category))}</div>`,
    markerCluster: (cat, count, id = "", { faded } = {}) =>
      `<div class="mk cluster ${cat === "unidentified" ? "muted" : ""} ${faded ? "faded" : ""}" data-action="map-cluster" data-value="${id}">${icon(catIcon(cat))}<span>${count}</span></div>`,
  };

  // =============================================================================================
  // 2. Screens
  // =============================================================================================

  const CATS = D.getCategories();
  const catIcon = (id) => (CATS.find((c) => c.id === id) || { icon: "unknown" }).icon;
  const SETTINGS = D.getSettings();
  const ME = D.getMyLocation();
  const locationText = () => `${ME.lat.toFixed(5)}, ${ME.lon.toFixed(5)} ±${ME.accuracy} m`;
  const hiddenByFilter = (d) => S.settings.hideUnnamedApple && d.company === 76 && !d.name;

  // ---- Formatting ----
  const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const TODAY = "2026-10-28", YESTERDAY = "2026-10-27";
  const monthDay = (day) => `${MONTHS[+day.slice(5, 7) - 1]} ${+day.slice(8, 10)}`;
  const dayLabel = (day) => (day === TODAY ? `Today · ${monthDay(day)}` : day === YESTERDAY ? `Yesterday · ${monthDay(day)}` : monthDay(day));
  const duration = (m) => (m >= 60 ? `${Math.floor(m / 60)} h${m % 60 ? ` ${m % 60} min` : ""}` : `${m} min`);
  const distance = (m) => (m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${m} m`);
  const recSummary = (r) => [duration(r.minutes), r.meters ? distance(r.meters) : null, `${r.devices} devices`].filter(Boolean).join(" · ");

  // ---- Main screen: the map, its float buttons, the Discover sheet and the recorder ----
  function renderMain() {
    phone.innerHTML = `
      <div class="main">
      <div class="map main-map" id="main-map"></div>
      <div class="map-filter" id="map-filter"></div>
      <section class="sheet disc-sheet" aria-label="Discover">
        <div class="handle"></div>
        <div class="disc-head" id="disc-head"></div>
        <div class="search-slot"><div>${UI.searchField("Search products", "disc-q")}</div></div>
        <div class="sheet-body"><div id="disc-groups"></div></div>
      </section>
      <div class="recorder-catch" data-action="recorder-close"></div>
      ${UI.recorder({ state: "idle", paused: false, time: "12:34", meta: "", mapId: "rec-map" })}
      <div class="main-dim" data-action="side-close"></div>
      </div>
      <nav class="side" aria-label="Sessions"></nav>
      ${UI.menuButton(false)}
      <div id="pages"></div>
      ${UI.morphButton(false)}
      <div class="scrim sheet-scrim" data-action="sheet-close"></div>
      <section class="sheet dev-sheet" aria-label="Device"></section>`;
  }

  /** The Discover sheet: Nearby / All only while the session is live; a finished session shows All. */
  function renderDiscover() {
    const sheet = $(".disc-sheet", phone);
    const sess = S.session && D.getRecording(S.session);
    sheet.classList.toggle("open", !!sess);
    if (!sess) return liftRecorder();
    const mode = S.live ? S.discoverMode : "all";
    $("#disc-head").innerHTML = `<span></span>
      <span class="disc-center">${S.live ? UI.segmented([{ id: "nearby", label: "Nearby" }, { id: "all", label: "All" }], mode, "disc-mode") : `<span class="disc-title">Discover</span>`}</span>
      ${UI.iconButton({ name: "search", label: "Search products", action: "disc-search" })}`;
    const q = S.discoverQuery.trim().toLowerCase();
    const groups = D.getProducts(mode, S.session)
      .map((g) => ({ ...g, products: g.products.filter((p) => !q || p.name.toLowerCase().includes(q)) }))
      .filter((g) => g.products.length);
    $("#disc-groups").innerHTML = groups.map(UI.categorySection).join("") || `<div class="empty">No products match</div>`;
    sheet.style.setProperty("--peek", `${$(".handle", sheet).offsetHeight + $("#disc-head").offsetHeight}px`);
    if (!sheet.classList.contains("expanded")) setSheetState(sheet, "collapsed");
    liftRecorder();
  }

  /** The recorder sits above the Discover sheet: at its peek, or above it when expanded. */
  function liftRecorder() {
    const sheet = $(".disc-sheet", phone);
    let lift = 0;
    if (sheet.classList.contains("open")) {
      lift = sheet.classList.contains("expanded") ? sheet.offsetHeight : parseFloat(getComputedStyle(sheet).getPropertyValue("--peek")) || 0;
    }
    phone.style.setProperty("--rec-lift", `${lift}px`);
  }

  // ---- Left sidebar: the current session (pinned while recording), the history, Settings ----
  function renderSidebar() {
    const cur = D.getCurrentRecording();
    const recs = D.getRecordings().filter((r) => r.devices > 0); // sessions without data are hidden
    const days = [...new Set(recs.map((r) => r.day))];
    $(".side", phone).innerHTML = UI.sidebar({
      current: cur && { id: cur.id, title: "Current session", sub: `Started ${cur.time} · ${cur.devices} devices` },
      all: (() => { const v = D.getAllSessionsView(); return { sub: `${v.sessions} sessions · ${v.devices} devices` }; })(),
      days: days.map((day) => ({ label: dayLabel(day), sessions: recs.filter((r) => r.day === day).map((r) => ({ id: r.id, title: r.time, sub: recSummary(r) })) })),
      selected: S.session,
    });
  }

  // ---- Right panel: the session's devices (the old Scan list) ----
  function sessionRows() {
    if (!S.session) return `<div class="empty">Start recording to scan devices</div>`;
    const byAddr = Object.fromEntries(D.getSessionDevices(S.session).map((d) => [d.addr, d]));
    const q = S.devQuery.trim().toLowerCase();
    const match = (d) => !q || (d.name || "").toLowerCase().includes(q) || d.addr.toLowerCase().includes(q) || d.addr.replace(/:/g, "").toLowerCase().includes(q);
    // the order was sorted once when the session was shown; filters only hide rows
    return S.order.map((a) => byAddr[a])
      .filter((d) => d && !hiddenByFilter(d) && (S.devCat === "all" || d.category === S.devCat) && match(d))
      .map((d) => UI.deviceRow(d, { spark: S.live })).join("") || `<div class="empty">No matching devices</div>`;
  }
  function devicesPage() {
    return `
      <div class="scroll scan-list"><div class="rows" id="dev-rows">${sessionRows()}</div></div>
      <div class="top-fade"></div>
      <div class="scan-search wide" ${S.session ? "" : "hidden"}>${UI.searchField("Search name or MAC", "dev-q")}</div>
      <div id="dev-chips" ${S.session ? "" : "hidden"}>${UI.chipRow(CATS, S.devCat, "dev-cat")}</div>`;
  }
  const refreshDevices = () => { const r = $("#dev-rows"); if (r) r.innerHTML = sessionRows(); };

  // ---- Settings ----
  function settingsPage() {
    const s = S.settings;
    const sw = (title, key, sub = "") => `<div class="item"><span class="item-text"><span class="item-title">${title}</span>${sub ? `<span class="item-sub">${sub}</span>` : ""}</span>${UI.switch(s[key], "setting", key)}</div>`;
    return `
      <div class="page-bar">${UI.floatButton({ name: "back", label: "Back", action: "back" })}<span class="page-title">Settings</span></div>
      <div class="scroll">
        ${UI.listItem({ title: "User ID", end: esc(s.userId) })}
        ${sw("Record when the app opens", "autoRecord", "Starts a recording each time Cluetooth opens")}
        ${sw("Upload over Wi-Fi only", "wifiOnly")}
        ${sw("Scan LE Coded PHY", "codedPhy", "Also scan long-range devices; applies within a minute")}
        <div class="item"><span class="item-text"><span class="item-title">Pending uploads</span><span class="item-sub">${esc(s.pendingUploads)}</span></span>
          ${UI.button({ kind: "tonal", label: "Upload now", action: "upload", cls: "small" })}</div>
        ${UI.sect("Filters")}
        ${sw("Hide unnamed Apple devices", "hideUnnamedApple", "Only what you see; scanning and recording keep everything")}
        ${UI.listItem({ title: "App version", end: esc(s.version) })}
      </div>`;
  }

  // ---- Product page ----
  function productPage(p) {
    const order = p.devices.slice().sort((a, b) => b.rssi - a.rssi); // sorted once, when the page appears
    return `
      <div class="scroll">
        <div class="hero">${icon(catIcon(p.category))}</div>
        <div class="prod-body">
          <h1 class="prod-name">${esc(p.name)}</h1>
          <p class="prod-desc">${esc(p.description)}</p>
          <p class="prod-meta">Seen at ${p.places} places · last ${esc(p.last)}</p>
          ${p.identified ? UI.button({ kind: "tonal", label: "Wiki", action: "wiki", value: p.id }) : ""}
          ${UI.mapThumb("product-map", p.id, "prod-mini")}
        </div>
        ${UI.sect("Devices")}
        <div class="rows">${order.map((d) => UI.deviceRow(d)).join("")}</div>
      </div>
      <div class="top-fade short when-scrolled"></div>
      ${UI.floatButton({ name: "back", label: "Back", action: "back", cls: "float tl" })}`;
  }
  /** One point per device: where it scanned strongest. */
  const bestPoint = (addr) => { const s = D.getDeviceSightings(addr); return s.length ? s.reduce((a, b) => (b.rssi > a.rssi ? b : a)) : null; };
  function productPoints(p) {
    return p.devices.map((d) => { const b = bestPoint(d.addr); return b && { addr: d.addr, at: b.at, rssi: b.rssi }; }).filter(Boolean);
  }

  // ---- Onboarding (#onboarding) ----
  const OB_STEPS = [
    { title: "Permissions", body: () => `<p>Cluetooth listens for Bluetooth devices around you. It needs:</p>
        ${[["Nearby devices", "To scan Bluetooth advertisements and read a device's services."], ["Precise location", "To put what you scan on the map. Android also needs it for Bluetooth scanning."], ["Notifications", "To show that a recording is running. Optional."]]
          .map(([t, s], i) => `<div class="item"><span class="item-text"><span class="item-title">${t}</span><span class="item-sub">${s}</span></span>${S.obAllowed[i] ? `<span class="item-end">Allowed</span>` : UI.button({ kind: "tonal", label: "Allow", action: "ob-allow", value: i, cls: "small" })}</div>`).join("")}` },
    { title: "Bluetooth", body: () => `<p>Bluetooth is on.</p>` },
    { title: "Your user ID", body: () => `<p>It marks your uploads. You can change it later in Settings.</p>
        <label class="sf"><input id="ob-user" value="${esc(S.settings.userId)}" autocomplete="off" aria-label="User ID"></label>
        <p class="ob-hint">Lowercase letters and numbers only, max 9 characters.</p>` },
    { title: "Record automatically?", body: () => `<p>A recording scans the devices around you, saves them with your route, and uploads them. Cluetooth only scans while it records.</p>
        <div class="item"><span class="item-text"><span class="item-title">Start recording automatically when the app opens</span><span class="item-sub">You can turn this off in Settings.</span></span>${UI.switch(S.obAuto, "ob-auto")}</div>` },
  ];
  function renderOnboarding() {
    const st = OB_STEPS[S.ob], last = S.ob === OB_STEPS.length - 1;
    phone.innerHTML = `<div class="ob">
      <div class="ob-step">${S.ob + 1} of ${OB_STEPS.length}</div>
      <h1 class="prod-name">${st.title}</h1>
      <div class="ob-body">${st.body()}</div>
      <div class="ob-actions">${S.ob ? UI.button({ kind: "tonal", label: "Back", action: "ob-back" }) : "<span></span>"}
        ${UI.button({ kind: "primary", label: last ? "Done" : "Next", action: last ? "ob-done" : "ob-next" })}</div>
    </div>`;
  }

  // ---- Maps (MapLibre) ----
  function boundsOf(coords) {
    const b = new maplibregl.LngLatBounds(coords[0], coords[0]);
    coords.forEach((c) => b.extend(c));
    return b;
  }
  function routeMarker(kind, at, map) {
    const el = document.createElement("div");
    el.className = `route-mark ${kind}`;
    el.textContent = kind === "start" ? "Start" : "End";
    // Start sits above its point and End below, so the two never cover each other on a loop
    return new maplibregl.Marker({ element: el, anchor: kind === "start" ? "bottom" : "top", offset: [0, kind === "start" ? -10 : 10] }).setLngLat(at).addTo(map);
  }
  const lineGeo = (route) => ({ type: "FeatureCollection", features: route.length > 1 ? [{ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: route } }] : [] });
  const routeLayer = () => ({ id: "route", type: "line", source: "route", layout: { "line-cap": "round", "line-join": "round" },
    paint: { "line-color": tok("--map-route"), "line-width": 4, "line-opacity": +tok("--map-route-opacity") } });
  const fc = (points) => ({ type: "FeatureCollection", features: points.map((p) => ({ type: "Feature", properties: { ...p, at: undefined }, geometry: { type: "Point", coordinates: p.at } })) });

  /** A small non-interactive preview: the route (recorder panel) or the points (product card, device sheet). */
  function miniMap(container, route, points = []) {
    const coords = route.length ? route : points.length ? points.map((p) => p.at) : [ME.at];
    const map = new maplibregl.Map({ container, style: MAP_STYLE(), interactive: false, attributionControl: false,
      bounds: boundsOf(coords), fitBoundsOptions: { padding: 28, maxZoom: 16 } });
    map.on("load", () => {
      if (route.length > 1) {
        map.addSource("route", { type: "geojson", data: lineGeo(route) });
        map.addLayer(routeLayer());
        routeMarker("start", route[0], map);
        routeMarker("end", route[route.length - 1], map);
      }
      if (points.length) {
        map.addSource("pts", { type: "geojson", data: fc(points) });
        map.addLayer({ id: "pts", type: "circle", source: "pts", paint: { "circle-radius": 4, "circle-color": tok("--blue"), "circle-stroke-width": 1.5, "circle-stroke-color": tok("--fill1") } });
      }
    });
    return map;
  }

  /** The main map: one instance for the whole app. A session shows its route and one point per device. */
  function createMainMap(container) {
    const map = new maplibregl.Map({ container, style: MAP_STYLE(), attributionControl: false, center: ME.at, zoom: 16 });
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-left");
    const M = { sel: null, cache: {}, shown: {}, points: [], at: {}, routeMarks: [], me: null, ready: false, pending: [] };
    const whenReady = (f) => (M.ready ? f() : M.pending.push(f));
    const pad = () => ({ top: 90, bottom: (parseFloat(phone.style.getPropertyValue("--rec-lift")) || 0) + 40, left: 40, right: 40 });

    map.on("load", () => {
      map.addSource("route", { type: "geojson", data: lineGeo([]) });
      map.addLayer(routeLayer());
      map.addSource("density", { type: "geojson", data: fc([]) });
      map.addLayer({ id: "density", type: "circle", source: "density",
        paint: { "circle-radius": +tok("--map-density-radius"), "circle-color": tok("--map-density"), "circle-opacity": +tok("--map-density-opacity") } });
      map.addSource("sightings", { type: "geojson", data: fc([]) });
      map.addLayer({ id: "sightings", type: "circle", source: "sightings", paint: {
        "circle-radius": 6, "circle-stroke-width": 2, "circle-stroke-color": tok("--fill1"),
        "circle-color": ["step", ["get", "rssi"], tok("--rssi-weak"), -80, tok("--rssi-fair"), -65, tok("--rssi-strong")] } });
      map.addSource("devices", { type: "geojson", data: fc([]), cluster: true, clusterRadius: 50, clusterMaxZoom: 18,
        clusterProperties: Object.fromEntries(CATS.map((c) => [c.id, ["+", ["case", ["==", ["get", "category"], c.id], 1, 0]]])) });
      // an invisible layer so the source is tiled and querySourceFeatures returns it
      map.addLayer({ id: "devices", type: "circle", source: "devices", paint: { "circle-radius": 0, "circle-opacity": 0 } });
      M.ready = true;
      M.pending.splice(0).forEach((f) => f());
    });

    function syncMarkers() {
      const next = {};
      for (const f of map.querySourceFeatures("devices")) {
        const p = f.properties, id = p.cluster ? `c${p.cluster_id}` : p.addr;
        if (next[id]) continue;
        let mk = M.cache[id];
        if (!mk) {
          const box = document.createElement("div");
          if (p.cluster) {
            const dom = CATS.reduce((a, c) => ((p[c.id] || 0) > (p[a.id] || 0) ? c : a), CATS[0]);
            box.innerHTML = UI.markerCluster(dom.id, p.point_count, p.cluster_id);
            box.firstElementChild.dataset.lng = f.geometry.coordinates[0];
            box.firstElementChild.dataset.lat = f.geometry.coordinates[1];
          } else box.innerHTML = UI.markerPoint({ addr: p.addr, category: p.category });
          mk = M.cache[id] = new maplibregl.Marker({ element: box.firstElementChild }).setLngLat(f.geometry.coordinates);
        }
        next[id] = mk;
        if (!M.shown[id]) mk.addTo(map);
      }
      for (const id in M.shown) if (!next[id]) M.shown[id].remove();
      M.shown = next;
      paintSelection();
    }
    function paintSelection() {
      for (const id in M.shown) {
        const e = M.shown[id].getElement();
        e.classList.toggle("sel", !!M.sel && id === M.sel);
        e.classList.toggle("faded", !!M.sel && id !== M.sel);
      }
    }
    let queued = false;
    map.on("render", () => {
      if (queued || !M.ready || !map.isSourceLoaded("devices")) return;
      queued = true;
      requestAnimationFrame(() => { queued = false; syncMarkers(); });
    });
    map.on("click", (e) => { if (!e.originalEvent.target.closest(".mk")) M.clear(); });

    function setPoints(points) {
      M.points = points;
      M.at = Object.fromEntries(points.map((p) => [p.addr, p.at]));
      for (const id in M.shown) M.shown[id].remove();
      M.shown = {}; M.cache = {};
      map.getSource("devices").setData(fc(points));
      map.getSource("density").setData(fc(points));
    }
    /** null: only the user's location. Otherwise the session's route and points, framed. */
    M.setSession = (sess, points) => whenReady(() => {
      M.clear(true);
      M.routeMarks.forEach((m) => m.remove());
      M.routeMarks = [];
      if (M.me) { M.me.remove(); M.me = null; }
      if (!sess) {
        setPoints([]);
        map.getSource("route").setData(lineGeo([]));
        const el = document.createElement("div");
        el.className = "me-dot";
        el.setAttribute("aria-label", "Your location");
        M.me = new maplibregl.Marker({ element: el }).setLngLat(ME.at).addTo(map);
        map.jumpTo({ center: ME.at, zoom: 16 });
        return;
      }
      setPoints(points);
      map.getSource("route").setData(sess.routes ? { type: "FeatureCollection", features: sess.routes.flatMap((rt) => lineGeo(rt).features) } : lineGeo(sess.route));
      map.setPaintProperty("route", "line-opacity", +tok(sess.routes ? "--map-routes-opacity" : "--map-route-opacity"));
      if (sess.route.length > 1) M.routeMarks = [routeMarker("start", sess.route[0], map), routeMarker("end", sess.route[sess.route.length - 1], map)];
      const coords = [...(sess.routes || [sess.route]).flat(), ...points.map((p) => p.at)];
      if (coords.length) map.fitBounds(boundsOf(coords), { padding: pad(), maxZoom: 17, duration: 0 });
    });
    M.fit = (coords) => whenReady(() => coords.length && map.fitBounds(boundsOf(coords), { padding: pad(), maxZoom: 17 }));
    M.select = (addr, move) => whenReady(() => {
      M.sel = addr;
      const sightings = D.getDeviceSightings(addr);
      map.getSource("sightings").setData(fc(sightings));
      const at = M.at[addr] || (sightings[0] && sightings[0].at);
      if (move && at) map.easeTo({ center: at, zoom: Math.max(map.getZoom(), 18.2), padding: { top: 0, bottom: 320, left: 0, right: 0 }, duration: 600 });
      paintSelection();
      openSheet(addr, { onMap: true });
    });
    M.clear = (quiet) => {
      if (!M.sel || !M.ready) return;
      M.sel = null;
      map.getSource("sightings").setData(fc([]));
      paintSelection();
      if (!quiet) closeSheet(true);
    };
    M.zoomCluster = async (id, lngLat) => {
      const z = await map.getSource("devices").getClusterExpansionZoom(+id);
      map.easeTo({ center: lngLat, zoom: z + 0.2 });
    };
    M.setPoints = (points) => whenReady(() => { M.clear(true); setPoints(points); });
    return M;
  }

  // =============================================================================================
  // 3. App
  // =============================================================================================

  const phone = $("#phone");
  const S = {
    session: null, live: false, order: [],
    recorder: "idle", paused: false, recMap: null,
    discoverMode: "nearby", discoverSearch: false, discoverQuery: "",
    devCat: "all", devQuery: "", productFilter: null,
    settings: { ...SETTINGS }, stack: [], map: null,
    ob: 0, obAuto: true, obAllowed: [false, false, false],
  };

  const top = () => (S.stack.length ? S.stack[S.stack.length - 1].id : "main");

  /** The devices button lives above the pages: on the map, and as the devices page's close button. */
  function updateChrome() {
    const t = top(), morph = $(".morph", phone);
    morph.classList.toggle("open", t === "devices");
    morph.classList.toggle("gone", t !== "main" && t !== "devices");
    morph.setAttribute("aria-label", t === "devices" ? "Close devices" : "Devices");
    $(".menu-btn", phone).classList.toggle("gone", t !== "main");
  }

  function push(id, html, { cls = "", mount, cleanup } = {}) {
    const el = document.createElement("section");
    el.className = `page ${cls}`;
    el.dataset.page = id;
    el.innerHTML = html;
    $("#pages", phone).append(el);
    el.getBoundingClientRect();
    el.classList.add("in");
    const entry = { id, el, cleanup };
    S.stack.push(entry);
    updateChrome();
    mount && mount(el, entry);
    return el;
  }
  function pop() {
    const e = S.stack.pop();
    if (!e) return;
    e.el.classList.remove("in");
    const done = () => { e.cleanup && e.cleanup(); e.el.remove(); };
    e.el.addEventListener("transitionend", done, { once: true });
    updateChrome();
  }
  const popAll = () => { while (S.stack.length) pop(); };

  /** Shows one session everywhere: the map, the Discover sheet, the right panel and the sidebar. */
  function showSession(id, live) {
    S.session = id;
    S.live = !!(id && live);
    S.productFilter = null;
    S.order = id ? D.getSessionDevices(id).sort((a, b) => b.rssi - a.rssi).map((d) => d.addr) : []; // sorted once
    const sess = id && D.getRecording(id);
    S.map.setSession(sess, id ? sessionPoints() : []);
    renderDiscover();
    refreshDevices();
    $("#map-filter").innerHTML = "";
    const r = $(".recorder", phone);
    $(".rec-meta", r).textContent = S.recorder === "idle" ? "" : `${D.getCurrentRecording().devices} devices · ${locationText()}`;
  }
  const sessionPoints = () => D.getSessionDevices(S.session).map((d) => ({ addr: d.addr, at: d.best, rssi: d.rssi, category: d.category, product: d.product || "unidentified" }));

  /** The recorder: "idle" (FAB), "capsule" or "panel". The capsule's width and the panel's height are measured. */
  function setRecorder(state) {
    S.recorder = state;
    const r = $(".recorder", phone), row = $(".rec-row", r);
    if (state === "capsule") r.style.setProperty("--capsule-w", `${row.scrollWidth}px`);
    if (state === "panel") r.style.setProperty("--panel-h", `${[...r.children].reduce((h, c) => h + c.offsetHeight + parseFloat(getComputedStyle(c).marginTop) + parseFloat(getComputedStyle(c).marginBottom), 0) + 2}px`);
    r.classList.remove("idle", "capsule", "panel");
    r.classList.add(state);
    r.setAttribute("aria-label", state === "idle" ? "Record" : "Recording");
    $(".recorder-catch", phone).classList.toggle("on", state === "panel");
    if (state === "panel" && !S.recMap) {
      r.addEventListener("transitionend", function once(e) {
        if (e.propertyName !== "height") return;
        r.removeEventListener("transitionend", once);
        S.recMap = miniMap($("#rec-map"), D.getCurrentRecording().route);
      });
    }
  }
  function startRecording() {
    S.paused = false;
    const id = D.startRecording();
    $(".rec-meta", phone).textContent = `${D.getCurrentRecording().devices} devices · ${locationText()}`;
    setRecorder("capsule");
    showSession(id, true);
  }
  function stopRecording() {
    S.paused = false;
    const r = $(".recorder", phone);
    $(".rec-dot", r).classList.remove("paused");
    $('[data-action="pause"]', r).outerHTML = UI.iconButton({ name: "pause", label: "Pause", action: "pause", kind: "neutral" });
    if (S.recMap) { S.recMap.remove(); S.recMap = null; }
    const id = D.stopRecording();
    setRecorder("idle");
    // the UI stays on the session that just ended, now frozen history
    if (!S.session || S.session === id) showSession(id, false);
  }

  // ---- Device sheet ----
  function openSheet(addr, { onMap = false } = {}) {
    const d = D.getDevice(addr);
    S.sheet = { addr, onMap, tab: "adv" };
    const sheet = $(".dev-sheet", phone);
    const sightings = D.getDeviceSightings(addr);
    if (S.sheetMap) { S.sheetMap.remove(); S.sheetMap = null; }
    sheet.innerHTML = UI.deviceSheet(d, { tab: "adv", gatt: D.getDeviceGatt(addr), chartW: phone.clientWidth - 32, mapId: sightings.length ? "dev-mini" : "" });
    if (sightings.length) S.sheetMap = miniMap($("#dev-mini", sheet), [], sightings);
    $(".sheet-body", sheet).scrollTop = 0;
    sheet.style.setProperty("--peek", `${$(".handle", sheet).offsetHeight + $(".dev-head", sheet).offsetHeight}px`);
    // on the map it opens collapsed so the selection stays visible; elsewhere expanded
    setSheetState(sheet, onMap ? "collapsed" : "expanded");
    sheet.classList.add("open");
    $(".sheet-scrim", phone).classList.toggle("on", !onMap);
  }
  function closeSheet(fromMap = false) {
    if (!S.sheet) return;
    const wasMap = S.sheet.onMap;
    S.sheet = null;
    $(".dev-sheet", phone).classList.remove("open");
    $(".sheet-scrim", phone).classList.remove("on");
    if (wasMap && !fromMap) S.map.clear(true);
  }

  // ---- Sheet snapping: two states, chosen on release from position and velocity ----
  function setSheetState(sheet, state) {
    sheet.classList.toggle("collapsed", state === "collapsed");
    sheet.classList.toggle("expanded", state === "expanded");
    if (sheet.classList.contains("disc-sheet")) liftRecorder();
  }
  phone.addEventListener("pointerdown", (e) => {
    const sheet = e.target.closest(".sheet.open:not(.static)");
    if (!sheet || !e.target.closest(".handle, .dev-head, .disc-head") || e.target.closest("a, button")) return;
    const full = sheet.offsetHeight, peek = parseFloat(getComputedStyle(sheet).getPropertyValue("--peek")) || 92;
    const travel = full - peek, base = sheet.classList.contains("expanded") ? 0 : travel;
    const y0 = e.clientY, samples = [[e.timeStamp, e.clientY]];
    let y = base, moved = false;
    sheet.setPointerCapture(e.pointerId);
    const move = (ev) => {
      if (Math.abs(ev.clientY - y0) > 4) moved = true;
      if (!moved) return;
      sheet.classList.add("dragging");
      y = Math.max(0, Math.min(travel, base + ev.clientY - y0));
      sheet.style.transform = `translateY(${y}px)`;
      samples.push([ev.timeStamp, ev.clientY]);
      if (samples.length > 5) samples.shift();
    };
    const up = () => {
      sheet.removeEventListener("pointermove", move);
      sheet.removeEventListener("pointerup", up);
      sheet.removeEventListener("pointercancel", up);
      sheet.classList.remove("dragging");
      sheet.style.transform = "";
      if (!moved) return setSheetState(sheet, sheet.classList.contains("expanded") ? "collapsed" : "expanded"); // a tap toggles
      const [t0, p0] = samples[0], [t1, p1] = samples[samples.length - 1];
      const v = (p1 - p0) / Math.max(1, t1 - t0); // px per ms, positive is down
      setSheetState(sheet, Math.abs(v) > 0.4 ? (v < 0 ? "expanded" : "collapsed") : (y < travel / 2 ? "expanded" : "collapsed"));
    };
    sheet.addEventListener("pointermove", move);
    sheet.addEventListener("pointerup", up);
    sheet.addEventListener("pointercancel", up);
  });

  /** The sidebar pushes the map 82% to the right; ☰ rides its edge and turns into "collapse sidebar". */
  function setDrawer(open) {
    if (open) renderSidebar();
    phone.classList.toggle("side-open", open);
    const b = $(".menu-btn", phone);
    b.classList.toggle("open", open);
    b.setAttribute("aria-label", open ? "Close sidebar" : "Sessions and settings");
    b.setAttribute("aria-expanded", open);
  }
  /** The product page's map preview filters the main map to that product. */
  function filterToProduct(id) {
    const p = D.getProduct(id);
    popAll();
    closeSheet();
    let pts = S.session ? sessionPoints().filter((x) => x.product === id) : [];
    if (!pts.length) pts = productPoints(p).map((x) => ({ ...x, category: p.category }));
    S.productFilter = id;
    S.map.setPoints(pts);
    S.map.fit(pts.map((x) => x.at));
    $("#map-filter").innerHTML = UI.chip({ label: p.name, on: true, action: "clear-filter", value: id });
  }

  const ACTIONS = {
    back: () => { closeSheet(); pop(); },
    sidebar: () => setDrawer(!phone.classList.contains("side-open")),
    "side-close": () => setDrawer(false),
    session: (el) => {
      setDrawer(false);
      const cur = D.getCurrentRecording();
      showSession(el.dataset.value, !!cur && cur.id === el.dataset.value);
    },
    devices: () => (top() === "devices" ? pop() : push("devices", devicesPage(), { cls: "devices-page" })),
    settings: () => { setDrawer(false); push("settings", settingsPage()); },
    setting: (el) => {
      const k = el.dataset.value;
      S.settings[k] = !S.settings[k];
      el.setAttribute("aria-checked", S.settings[k]);
      if (k === "hideUnnamedApple") refreshDevices();
    },
    upload: () => {},
    wiki: (el) => open(`https://wirelesswiki.org/p/${el.dataset.value}`, "_blank", "noopener"),
    "open-session-map": () => { setRecorder("capsule"); popAll(); S.map.fit(D.getCurrentRecording().route); },
    "product-map": (el) => filterToProduct(el.dataset.value),
    "clear-filter": () => { S.productFilter = null; $("#map-filter").innerHTML = ""; S.map.setPoints(sessionPoints()); },
    product: (el) => {
      const p = D.getProduct(el.dataset.value);
      push("product", productPage(p), { mount: (pg, entry) => {
        const mini = miniMap($("#prod-mini", pg), [], productPoints(p));
        entry.cleanup = () => mini.remove();
      } });
    },
    device: (el) => openSheet(el.dataset.value),
    "sheet-close": () => closeSheet(),
    "dev-tab": (el) => {
      S.sheet.tab = el.dataset.value;
      const d = D.getDevice(S.sheet.addr);
      $("#dev-seg").innerHTML = UI.segmented([{ id: "adv", label: "Advertising" }, { id: "gatt", label: "GATT" }], S.sheet.tab, "dev-tab");
      $("#dev-tab").innerHTML = S.sheet.tab === "adv" ? UI.advertising(d) : UI.gatt(D.getDeviceGatt(S.sheet.addr), S.sheet.addr);
    },
    gatt: (el) => { D.discoverGatt(el.dataset.value); $("#dev-tab").innerHTML = UI.gatt(D.getDeviceGatt(el.dataset.value), el.dataset.value); },
    "device-map": (el) => { const a = el.dataset.value; popAll(); closeSheet(); S.map.select(a, true); },
    "map-point": (el) => S.map.select(el.dataset.value, false),
    "map-cluster": (el) => S.map.zoomCluster(el.dataset.value, [+el.dataset.lng, +el.dataset.lat]),
    "dev-cat": (el) => {
      S.devCat = el.dataset.value;
      $("#dev-chips").innerHTML = UI.chipRow(CATS, S.devCat, "dev-cat");
      refreshDevices();
    },
    recorder: () => { if (S.recorder === "idle") startRecording(); },
    "recorder-open": () => { if (S.recorder === "capsule") setRecorder("panel"); },
    "recorder-close": () => setRecorder("capsule"),
    pause: (el) => {
      S.paused = !S.paused;
      el.outerHTML = UI.iconButton({ name: S.paused ? "play" : "pause", label: S.paused ? "Resume" : "Pause", action: "pause", kind: "neutral" });
      $(".recorder .rec-dot", phone).classList.toggle("paused", S.paused);
      if (S.recorder === "capsule") setRecorder("capsule");
    },
    stop: () => stopRecording(),
    "disc-mode": (el) => { S.discoverMode = el.dataset.value; renderDiscover(); },
    "disc-search": () => {
      S.discoverSearch = !S.discoverSearch;
      $(".disc-sheet .search-slot", phone).classList.toggle("open", S.discoverSearch);
      if (S.discoverSearch) { setSheetState($(".disc-sheet", phone), "expanded"); $("#disc-q").focus(); }
      else { $("#disc-q").value = ""; S.discoverQuery = ""; renderDiscover(); }
    },
    "ob-allow": (el) => { S.obAllowed[+el.dataset.value] = true; renderOnboarding(); },
    "ob-auto": (el) => { S.obAuto = !S.obAuto; el.setAttribute("aria-checked", S.obAuto); },
    "ob-next": () => { S.ob++; renderOnboarding(); },
    "ob-back": () => { S.ob--; renderOnboarding(); },
    "ob-done": () => { location.href = `${location.pathname}?state=${S.obAuto ? 2 : 1}`; },
  };

  phone.addEventListener("click", (e) => {
    const t = e.target.closest("[data-action]");
    if (!t || !phone.contains(t)) return;
    const f = ACTIONS[t.dataset.action];
    if (f) { e.stopPropagation(); f(t, e); }
  });
  // A .when-scrolled fade shows only once its page's scroller has left the top (the product hero stays clean).
  phone.addEventListener("scroll", (e) => {
    const f = e.target.parentElement && e.target.parentElement.querySelector(":scope > .top-fade.when-scrolled");
    if (f) f.classList.toggle("on", e.target.scrollTop > 0);
  }, true);

  // Escape stands in for system back: the topmost layer closes first.
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape" || location.hash) return;
    if (S.sheet) closeSheet();
    else if (phone.classList.contains("side-open")) setDrawer(false);
    else if (S.recorder === "panel") setRecorder("capsule");
    else if (S.stack.length) pop();
  });
  phone.addEventListener("input", (e) => {
    if (e.target.id === "dev-q") { S.devQuery = e.target.value; refreshDevices(); }
    if (e.target.id === "disc-q") { S.discoverQuery = e.target.value; renderDiscover(); }
  });

  /** Launch. ?state=1..4 jumps to a demo state; without it, "Record when the app opens" decides between 1 and 2. */
  function boot() {
    renderMain();
    updateChrome();
    S.map = createMainMap($("#main-map"));
    const state = +new URLSearchParams(location.search).get("state") || (S.settings.autoRecord ? 2 : 1);
    if (state === 2 || state === 3) startRecording();
    if (state === 3) stopRecording();
    if (state === 4) showSession("r1", false);
    if (state === 5) showSession("all", false);
    if (state === 1) showSession(null, false);
  }

  // ---- Gallery (#gallery): every component in each state, built from the same UI functions ----
  function renderGallery() {
    document.body.classList.add("is-gallery");
    const devs = D.getDevices();
    const normal = devs.find((d) => d.name && !d.stale), stale = devs.find((d) => d.name && d.stale), noName = devs.find((d) => !d.name && !d.stale);
    const groups = D.getProducts("all", "now");
    const inRange = groups[0].products[0], before = groups.flatMap((g) => g.products).find((p) => !p.inRange), unknown = groups[groups.length - 1].products[0];
    const sheetDev = devs.find((d) => d.name === "JBL Flip 6");
    const noMap = devs.find((d) => d.name && !D.getDeviceSightings(d.addr).length) || devs[devs.length - 1];
    const cell = (label, html, style = "") => `<div class="g-cell"><div class="g-box" style="${style}">${html}</div><small>${label}</small></div>`;
    const sect = (name, toks) => `<div class="g-sect">${name}<span class="g-tok">${toks}</span></div>`;
    const swatch = (t) => `<div class="g-cell"><div class="g-sw" style="background: var(${t})"></div><small>${t}</small></div>`;
    const shadow = (t) => `<div class="g-cell"><div class="g-sw" style="background: var(--fill1); box-shadow: var(${t})"></div><small>${t}</small></div>`;
    $("#stage").innerHTML = `<div class="gallery">
      <h1>Cluetooth UI v2 components</h1>
      <p>Each component in each state, rendered by the same functions as the app. <a href="./">Open the app</a></p>

      ${sect("Demo states", "?state=1..5 · #onboarding")}
      <div class="g-row g-links">
        <a href="./?state=1">1 · Just opened, not recording</a>
        <a href="./?state=2">2 · Recording</a>
        <a href="./?state=3">3 · After Stop (frozen)</a>
        <a href="./?state=4">4 · History session</a>
        <a href="./?state=5">5 · All sessions</a>
        <a href="./#onboarding">Onboarding</a>
      </div>

      ${sect("Color", "tokens.css")}
      <div class="g-row">${["--bg", "--fill1", "--fill2", "--panel", "--t1", "--t2", "--t3", "--div", "--blue", "--chip-on-bg", "--chip-on-fg", "--seg-on-bg", "--rssi-strong", "--rssi-fair", "--rssi-weak", "--rec", "--map-muted"].map(swatch).join("")}</div>
      ${sect("Shadow", "--shadow-float, --shadow-raised, --shadow-drawer, --shadow-sheet, --shadow-thumb")}
      <div class="g-row">${["--shadow-float", "--shadow-raised", "--shadow-drawer", "--shadow-sheet", "--shadow-thumb"].map(shadow).join("")}</div>

      ${sect("FloatButton", ".rb · --rb, --rb-icon, --fill1, --shadow-float; on: --fill2")}
      <div class="g-row">
        ${cell("menu", UI.floatButton({ name: "menu", label: "Menu", action: "" }))}
        ${cell("discover", UI.floatButton({ name: "compass", label: "Discover", action: "" }))}
        ${cell("back", UI.floatButton({ name: "back", label: "Back", action: "" }))}
        ${cell("search", UI.floatButton({ name: "search", label: "Search", action: "" }))}
        ${cell("density on", UI.floatButton({ name: "density", label: "Density", action: "", cls: "on" }))}
        ${cell("density off", UI.floatButton({ name: "density", label: "Density", action: "" }))}
      </div>
      ${sect("MorphButton", ".morph · --t-morph, --ease; Devices (Bluetooth scan icon, top-right) → close devices page (top-left)")}
      <div class="g-row">
        ${cell("closed", UI.morphButton(false), "position: relative; width: 240px; height: 68px; container-type: inline-size")}
        ${cell("open", UI.morphButton(true), "position: relative; width: 240px; height: 68px; container-type: inline-size")}
        ${cell("tap to play", UI.morphButton(false, "morph g-play"), "position: relative; width: 240px; height: 68px; container-type: inline-size")}
      </div>
      ${sect("MenuButton", ".menu-btn · ☰ → collapse sidebar, rides the sidebar edge; --side-w, --t-std")}
      <div class="g-row">${cell("closed", UI.menuButton(false, "static"))}${cell("open", UI.menuButton(true, "static"))}</div>
      ${sect("IconButton", ".ib · --icon-btn, --icon-btn-glyph; neutral --fill2/--t1; rec --rec-red/--on-rec")}
      <div class="g-row">
        ${cell("Pause", UI.iconButton({ name: "pause", label: "Pause", action: "" }))}
        ${cell("Resume", UI.iconButton({ name: "play", label: "Resume", action: "" }))}
        ${cell("Stop", UI.iconButton({ name: "stop", label: "Stop", action: "", kind: "rec" }))}
      </div>
      ${sect("Recorder (main map only)", ".recorder · --fab, --fab-dot, --rec, --on-rec, --recorder-pad, --recorder-map-h, --shadow-float → --shadow-raised, --t-morph; BlinkingDot .rec-dot · --rec-dot, --t-blink")}
      <div class="g-row">
        ${cell("idle", UI.recorder({ state: "idle", paused: false, time: "12:34", meta: "", cls: "static" }), "width: 120px; height: 90px")}
        ${cell("capsule", UI.recorder({ state: "capsule", paused: false, time: "12:34", meta: "", cls: "static" }), "width: 240px; height: 90px")}
        ${cell("capsule, paused", UI.recorder({ state: "capsule", paused: true, time: "12:34", meta: "", cls: "static" }), "width: 280px; height: 90px")}
        ${cell("panel", UI.recorder({ state: "panel", paused: false, time: "12:34", meta: "26 devices · 32.88012, -117.23421 ±8 m", mapId: "g-rec-map", cls: "static" }), "width: 412px; height: 260px")}
      </div>
      ${sect("ChipRow", ".chips / .chip · --chip-h, --chip-bg, --chip-fg, --chip-on-bg, --chip-on-fg")}
      <div class="g-row">${cell("All selected", UI.chipRow(CATS.slice(0, 4), "all", "", "static"), "width: 412px; overflow: hidden")}
        ${cell("Audio selected", UI.chipRow(CATS.slice(0, 4), "audio", "", "static"), "width: 412px; overflow: hidden")}</div>
      ${sect("DeviceRow", ".row · --row-h, --spark-w, --rssi-w, --rssi-strong/fair/weak, --dim")}
      <div class="g-row">
        ${cell("normal", UI.deviceRow(normal), "width: 412px; padding: 0")}
        ${cell("stale", UI.deviceRow(stale), "width: 412px; padding: 0")}
        ${cell("no name", UI.deviceRow(noName), "width: 412px; padding: 0")}
        ${cell("map list (no sparkline), selected", UI.deviceRow(normal, { spark: false, sel: true }), "width: 412px; padding: 0")}
      </div>
      ${sect("ProductCard and CategorySection", ".pcard · --card-w, --card-icon, --fill2 on --bg, no shadow")}
      <div class="g-row">
        ${cell("in range", UI.productCard(inRange, groups[0].icon))}
        ${cell("seen before", UI.productCard(before, catIcon(before.category)))}
        ${cell("unidentified", UI.productCard(unknown, "unknown"))}
        ${cell("category section", UI.categorySection({ ...groups[1], products: groups[1].products.slice(0, 2) }), "width: 412px; padding: 0")}
      </div>
      ${sect("Segmented", ".seg · --seg-bg, --seg-fg, --seg-on-bg, --seg-on-fg, --seg-on-shadow")}
      <div class="g-row">
        ${cell("Nearby", `<div style="width: 184px">${UI.segmented([{ id: "nearby", label: "Nearby" }, { id: "all", label: "All" }], "nearby", "")}</div>`)}
        ${cell("All", `<div style="width: 184px">${UI.segmented([{ id: "nearby", label: "Nearby" }, { id: "all", label: "All" }], "all", "")}</div>`)}
        ${cell("device tabs", `<div style="width: 380px">${UI.segmented([{ id: "adv", label: "Advertising" }, { id: "gatt", label: "GATT" }], "adv", "")}</div>`)}
      </div>
      ${sect("Button", ".btn · --btn-h, --r-pill; primary --blue/--on-blue; tonal --fill2/--t1")}
      <div class="g-row">
        ${cell("primary", UI.button({ kind: "primary", label: "Discover GATT", action: "" }))}
        ${cell("tonal", UI.button({ kind: "tonal", label: "View on map", action: "" }))}
        ${cell("tonal", UI.button({ kind: "tonal", label: "Wiki", action: "" }))}
        ${cell("small", UI.button({ kind: "tonal", label: "Pause", action: "", cls: "small" }) + " " + UI.button({ kind: "tonal", label: "Upload now", action: "", cls: "small" }))}
      </div>
      ${sect("Switch", ".switch · --switch-w, --switch-h, --switch-knob, --fill2, --blue")}
      <div class="g-row">${cell("on", UI.switch(true, ""))}${cell("off", UI.switch(false, ""))}</div>
      ${sect("SearchField", ".sf · --search-h, --r-pill, --fill2; focused --field + inset --focus-color")}
      <div class="g-row">${cell("empty", `<div style="width: 380px">${UI.searchField("Search products")}</div>`)}
        ${cell("focused", `<div style="width: 380px">${UI.searchField("Search products", "g-q")}</div>`)}</div>
      ${sect("ListItem", ".item · --r-item")}
      <div class="g-row">
        ${cell("History rows", UI.sect("Today · Oct 28") + UI.listItem({ title: "6:05 PM", sub: "23 min · 1.8 km · 214 devices", action: "x", chevron: true }) + UI.listItem({ title: "12:10 PM", sub: "8 min · 41 devices", action: "x", chevron: true }), "width: 412px; padding: 0")}
        ${cell("Settings rows", `<div class="item"><span class="item-text"><span class="item-title">Upload over Wi-Fi only</span></span>${UI.switch(true, "")}</div>` + UI.listItem({ title: "App version", end: "0.1.0 (debug)" }), "width: 412px; padding: 0")}
      </div>
      ${sect("Sidebar", ".side · --side-w (82%), --panel, flush, no shadow; pushes the map; --dim-strip on the map strip")}
      <div class="g-row">${cell("recording, current session selected", `<nav class="side static">${UI.sidebar({ current: { id: "c", title: "Current session", sub: "Started 7:12 PM · 26 devices" }, selected: "c",
          days: [{ label: "Today · Oct 28", sessions: [{ id: "a", title: "6:05 PM", sub: "23 min · 1.8 km · 214 devices" }, { id: "b", title: "12:10 PM", sub: "8 min · 41 devices" }] }] })}</nav>`, "width: 338px; height: 420px; padding: 0")}
        ${cell("not recording, history session selected", `<nav class="side static">${UI.sidebar({ current: null, selected: "a",
          days: [{ label: "Today · Oct 28", sessions: [{ id: "a", title: "6:05 PM", sub: "23 min · 1.8 km · 214 devices" }, { id: "b", title: "12:10 PM", sub: "8 min · 41 devices" }] }] })}</nav>`, "width: 338px; height: 420px; padding: 0")}</div>
      ${sect("DeviceSheet", ".sheet · --r-sheet, --panel, --shadow-sheet, --chart-h; product link --blue + ↗; RssiChart ticks −65/−80 only; MapThumb")}
      <div class="g-row">
        ${cell("identified, Advertising", `<section class="sheet static">${UI.deviceSheet(sheetDev, { tab: "adv", gatt: D.getDeviceGatt(sheetDev.addr), chartW: 380, mapId: "g-dev-1" })}</section>`, "width: 412px; padding: 0")}
        ${cell("GATT read", `<section class="sheet static">${UI.deviceSheet(sheetDev, { tab: "gatt", gatt: D.getDeviceGatt(sheetDev.addr), chartW: 380, mapId: "g-dev-2" })}</section>`, "width: 412px; padding: 0")}
        ${cell("unidentified, no name, GATT not read", `<section class="sheet static">${UI.deviceSheet(noName, { tab: "gatt", gatt: null, chartW: 380, mapId: "g-dev-3" })}</section>`, "width: 412px; padding: 0")}
        ${cell("no located sightings: no map card", `<section class="sheet static">${UI.deviceSheet(noMap, { tab: "adv", gatt: null, chartW: 380 })}</section>`, "width: 412px; padding: 0")}
      </div>
      ${sect("MapMarker", ".mk · --marker, --marker-icon, --fill1, --shadow-float; muted --map-muted; faded --dim; RouteMark .route-mark")}
      <div class="g-row">
        ${cell("point", UI.markerPoint({ addr: "", category: "lighting" }))}
        ${cell("unidentified", UI.markerPoint({ addr: "", category: "unidentified" }))}
        ${cell("selected", UI.markerPoint({ addr: "", category: "audio" }, { sel: true }))}
        ${cell("faded", UI.markerPoint({ addr: "", category: "audio" }, { faded: true }))}
        ${cell("cluster", UI.markerCluster("audio", 50))}
        ${cell("cluster, unidentified", UI.markerCluster("unidentified", 26))}
        ${cell("route", `<span class="route-mark start">Start</span> <span class="route-mark end">End</span>`)}
      </div>
      ${sect("MapThumb", ".map-card · --map-preview-h, --r-item, --fill2; a clean map, no caption")}
      <div class="g-row">${cell("product preview", UI.mapThumb("", "", "g-thumb"), "width: 380px")}</div>
      ${sect("TopFade", ".top-fade · --fade-h, --fade-solid; solid gradient to --bg, no blur")}
      <div class="g-row">${cell("over a list", `<div style="position: relative; height: 150px; overflow: hidden">${devs.slice(0, 3).map((d) => UI.deviceRow(d)).join("")}<div class="top-fade" style="height: 90px"></div></div>`, "width: 412px; padding: 0")}</div>
    </div>`;
    miniMap($("#g-rec-map"), D.getRecording("now").route);
    const p = D.getProduct("govee");
    miniMap($("#g-thumb"), [], productPoints(p));
    ["g-dev-1", "g-dev-2"].forEach((id) => miniMap($(`#${id}`), [], D.getDeviceSightings(sheetDev.addr)));
    miniMap($("#g-dev-3"), [], D.getDeviceSightings(noName.addr));
    $("#g-q").focus();
    $(".g-play").addEventListener("click", (e) => e.currentTarget.classList.toggle("open"));
  }

  window.CluetoothUI = { UI, icon, band, minus };
  addEventListener("hashchange", () => location.reload());
  if (location.hash === "#gallery") renderGallery();
  else if (location.hash === "#onboarding") renderOnboarding();
  else boot();
})();
