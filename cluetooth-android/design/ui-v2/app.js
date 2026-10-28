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
    close: '<path d="M6 6l12 12M18 6 6 18"/>',
    list: '<path d="M9 6h12M9 12h12M9 18h12"/><path d="M4 6h.01M4 12h.01M4 18h.01"/>',
    grid: '<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/>',
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

    /** TopSearch: the top-right search button; open, it is the wiki search field across the top row. Name only. */
    topSearch: (open, cls = "") => `
      <div class="top-search ${open ? "open" : ""} ${cls}">
        <button class="ts-toggle" data-action="search" aria-label="Search by name" aria-expanded="${open}">${icon("search")}</button>
        <input id="top-q" type="search" placeholder="Search by name" autocomplete="off" aria-label="Search by name" tabindex="${open ? 0 : -1}">
      </div>`,

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

    /** DiscoverHead: the view switch (it shows the view it switches TO), then Nearby / All while live, else the view's name.
     *  In the product view it is the back button. */
    discoverHead: ({ view, live, mode, product }) => product
      ? `${UI.iconButton({ name: "back", label: "Back", action: "sheet-back" })}<span></span><span></span>`
      : `${UI.iconButton({ name: view === "list" ? "grid" : "list", label: view === "list" ? "Show categories" : "Show the device list", action: "disc-view" })}
         ${live ? UI.segmented([{ id: "nearby", label: "Nearby" }, { id: "all", label: "All" }], mode, "disc-mode") : `<span class="disc-title">${view === "list" ? "Devices" : "Products"}</span>`}
         <span></span>`,

    /** ProductView: inside the Discover sheet; the background map shows the product's points. */
    productView: (p, rows) => `
      <div class="hero">${icon(catIcon(p.category))}</div>
      <div class="prod-body">
        <h1 class="prod-name">${esc(p.name)}</h1>
        <p class="prod-desc">${esc(p.description)}</p>
        <p class="prod-meta">Seen at ${p.places} places · last ${esc(p.last)}</p>
        ${p.identified ? `<a class="dev-product" href="https://wirelesswiki.org/p/${esc(p.id)}" target="_blank" rel="noopener">Open in Wiki${icon("external")}</a>` : ""}
      </div>
      ${UI.sect("Devices")}
      <div class="rows">${rows}</div>`,

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

    /** DeviceHead: name, product wiki link, MAC with RSSI, and the neutral close button at the top right. */
    deviceHead: (d) => `
        <div class="dev-head">
          ${UI.iconButton({ name: "close", label: "Close", action: "device-close" })}
          <div class="dev-name"><b class="${d.name ? "" : "none"}">${d.name ? esc(d.name) : "No name"}</b></div>
          ${d.productName ? `<a class="dev-product" href="${esc(d.wikiUrl)}" target="_blank" rel="noopener">${esc(d.productName)}${icon("external")}</a>` : ""}
          <div class="dev-sub">${d.addr}<span class="dev-rssi band-${band(d.rssi)}">${minus(d.rssi)} dBm</span></div>
        </div>`,

    /** DeviceBody: the 1-minute chart, then the Advertising / GATT tabs. The map behind shows its sightings. */
    deviceBody: (d, { tab, gatt, chartW }) => `
        ${d.history ? `<div class="chart-wrap">${UI.chart(d.history, d.rssi, chartW)}</div>` : ""}
        <div class="dev-tabs" id="dev-seg">${UI.segmented([{ id: "adv", label: "Advertising" }, { id: "gatt", label: "GATT" }], tab, "dev-tab")}</div>
        <div id="dev-tab">${tab === "adv" ? UI.advertising(d) : UI.gattTab(gatt ? { phase: "done", result: gatt, shown: gatt.steps.length, addr: d.addr } : { phase: "idle", addr: d.addr })}</div>`,

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

    /** GATT services and characteristics. */
    gattServices: (services) => services.map((s) => `<div class="gatt-svc"><b>${esc(s.name)}</b><div class="dev-sub">${s.uuid}</div>
          ${s.chars.map(([n, u, p, v]) => `<div class="gatt-char">${esc(n)}<span>${u} · ${esc(p)}</span><span>${esc(v)}</span></div>`).join("")}</div>`).join(""),

    /** GATT steps: rows 0..shown-1 finished (done or failed), row `running` with a spinner (and its progress). */
    gattSteps: (steps, shown, running = -1, progress = "") => `<div class="gsteps">${steps.map((st, i) => {
      if (i === running) return `<div class="gstep running"><span class="gmark" aria-label="Running"></span><span>${esc(st.label)}</span><span class="gdetail">${esc(progress)}</span></div>`;
      if (i >= shown) return "";
      return `<div class="gstep ${st.failed ? "failed" : "done"}"><span class="gmark" aria-label="${st.failed ? "Failed" : "Done"}"></span><span>${esc(st.label)}</span><span class="gdetail">${esc(st.detail)}</span></div>`;
    }).join("")}</div>`,

    /** GATT tab. idle: the note and one full-width "Discover GATT"; confirm: the same row turns into Cancel | I am
     *  authorized (the note stays, nothing moves); running / failed / done: the steps, then Try again or the results. */
    gattTab: ({ phase, result, shown = 0, running = -1, progress = "", addr = "" }) => {
      if (phase === "idle" || phase === "confirm") return `
        <div class="gatt-flow ${phase === "confirm" ? "confirm" : ""}" data-addr="${addr}">
          <p class="gatt-note">Only connect to devices you own or have permission to test.</p>
          <div class="gatt-ctl">
            <button class="btn tonal gatt-cancel" data-action="gatt-cancel" tabindex="${phase === "confirm" ? 0 : -1}">Cancel</button>
            <button class="btn primary gatt-go" data-action="gatt-go" data-value="${addr}">${phase === "confirm" ? "I am authorized" : "Discover GATT"}</button>
          </div>
        </div>`;
      const failed = phase === "failed";
      return `<div class="gatt-flow">${UI.gattSteps(result.steps, shown, running, progress)}
        ${failed ? `<div class="gatt-retry">${UI.button({ kind: "tonal", label: "Try again", action: "gatt-retry", value: addr })}</div>` : ""}
        ${phase === "done" ? UI.gattServices(result.services) : ""}</div>`;
    },

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
      <section class="sheet disc-sheet" aria-label="Discover" data-snaps="collapsed half full">
        <div class="handle"></div>
        <div class="disc-head" id="disc-head"></div>
        <div class="disc-chips" id="disc-chips"></div>
        <div class="sheet-body" id="disc-body"></div>
      </section>
      <div class="recorder-catch" data-action="recorder-close"></div>
      ${UI.recorder({ state: "idle", paused: false, time: "12:34", meta: "", mapId: "rec-map" })}
      <div class="main-dim" data-action="side-close"></div>
      </div>
      <nav class="side" aria-label="Sessions"></nav>
      ${UI.menuButton(false)}
      ${UI.topSearch(false)}
      <div id="pages"></div>
      <div class="scrim dev-scrim" data-action="device-close"></div>
      <section class="sheet dev-sheet" aria-label="Device" data-snaps="dismissed expanded">
        <div class="handle"></div>
        <div class="dev-top" id="dev-top"></div>
        <div class="sheet-body" id="dev-body"></div>
      </section>`;
  }

  /** The Discover sheet: categories or the device list (Nearby / All only while live), or one product. */
  function renderDiscover() {
    const sheet = $(".disc-sheet", phone);
    const sess = S.session && D.getRecording(S.session);
    sheet.classList.toggle("open", !!sess);
    if (!sess) return liftRecorder();
    const mode = S.live ? S.discoverMode : "all";
    const q = S.query.trim().toLowerCase();
    const head = $("#disc-head"), chips = $("#disc-chips"), body = $("#disc-body");
    const top = S.nav[S.nav.length - 1];
    head.innerHTML = UI.discoverHead({ view: S.discView, live: S.live, mode, product: !!top });
    if (top) {
      const p = D.getProduct(top.id);
      chips.innerHTML = "";
      body.innerHTML = UI.productView(p, productDevices(p).filter((d) => nameMatch(d.name, q)).map((d) => UI.deviceRow(d, { spark: S.live })).join("") || `<div class="empty">No matching devices</div>`);
    } else if (S.discView === "list") {
      chips.innerHTML = UI.chipRow(CATS, S.devCat, "dev-cat", "inline");
      body.innerHTML = `<div class="rows">${sessionRows(mode)}</div>`;
    } else {
      chips.innerHTML = "";
      const groups = D.getProducts(mode, S.session)
        .map((g) => ({ ...g, products: g.products.filter((p) => nameMatch(p.name, q)) }))
        .filter((g) => g.products.length);
      body.innerHTML = groups.map(UI.categorySection).join("") || `<div class="empty">No products match</div>`;
    }
    sheet.style.setProperty("--peek", `${$(".handle", sheet).offsetHeight + head.offsetHeight}px`);
    if (!sheetState(sheet)) setSheetState(sheet, "collapsed");
    liftRecorder();
  }
  const nameMatch = (name, q) => !q || (name || "").toLowerCase().includes(q);

  /** The record FAB sits above the Discover sheet's peek and hides while the sheet is raised (Half or Full). */
  function liftRecorder() {
    const sheet = $(".disc-sheet", phone), open = sheet.classList.contains("open");
    phone.style.setProperty("--rec-lift", `${open ? parseFloat(getComputedStyle(sheet).getPropertyValue("--peek")) || 0 : 0}px`);
    $(".recorder", phone).classList.toggle("sheet-up", open && sheetState(sheet) !== "collapsed");
  }

  // ---- Left sidebar: the current session (pinned while recording), the history, Settings ----
  function renderSidebar() {
    const cur = D.getCurrentRecording();
    const recs = D.getRecordings().filter((r) => r.devices > 0); // sessions without data are hidden
    const days = [...new Set(recs.map((r) => r.day))];
    $(".side", phone).innerHTML = UI.sidebar({
      current: cur && { id: cur.id, title: "Current session", sub: `Started ${cur.time} · ${cur.devices} devices` },
      all: (() => { const v = D.getAllSessionsView(); return v.sessions ? { sub: `${v.sessions} sessions · ${v.devices} devices` } : null; })(),
      days: days.map((day) => ({ label: dayLabel(day), sessions: recs.filter((r) => r.day === day).map((r) => ({ id: r.id, title: r.time, sub: recSummary(r) })) })),
      selected: S.session,
    });
  }

  // ---- List view: the session's devices (the old Scan list), in the order sorted once ----
  function sessionRows(mode) {
    const byAddr = Object.fromEntries(D.getSessionDevices(S.session).map((d) => [d.addr, d]));
    const q = S.query.trim().toLowerCase();
    return S.order.map((a) => byAddr[a])
      .filter((d) => d && !hiddenByFilter(d) && (mode === "all" || !d.stale) && (S.devCat === "all" || d.category === S.devCat) && nameMatch(d.name, q))
      .map((d) => UI.deviceRow(d, { spark: S.live })).join("") || `<div class="empty">No matching devices</div>`;
  }
  /** A product's devices: those of the shown session if it has any, else every device of the product. Sorted once. */
  function productDevices(p) {
    const inSession = S.session ? D.getSessionDevices(S.session).filter((d) => (d.product || "unidentified") === p.id) : [];
    return (inSession.length ? inSession : p.devices).slice().sort((a, b) => b.rssi - a.rssi);
  }

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
    // frame what the Discover sheet leaves visible above it
    const pad = () => ({ top: 90, bottom: sheetCover() + 40, left: 40, right: 40 });

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
    // the user panning or zooming the map (drag, pinch, wheel, double-tap) collapses the Discover sheet;
    // camera moves made by the app have no originalEvent
    map.on("movestart", (e) => {
      const ds = $(".disc-sheet", phone);
      if (e.originalEvent && ds.classList.contains("open") && sheetState(ds) !== "collapsed") setSheetState(ds, "collapsed");
    });

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
      const below = Math.max(sheetCover(), 320);
      if (move && at) map.easeTo({ center: at, zoom: Math.max(map.getZoom(), 18.2), padding: { top: 0, bottom: below, left: 0, right: 0 }, duration: 600 });
      paintSelection();
    });
    /** Removes the highlight; unless quiet, a tap on empty map also leaves the device view. */
    M.clear = (quiet) => {
      if (!M.sel || !M.ready) return;
      M.sel = null;
      map.getSource("sightings").setData(fc([]));
      paintSelection();
      if (!quiet) closeDevice();
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
    discoverMode: "nearby", discView: "categories", nav: [], device: null, // nav: the sheet's stack above the base view (a product)
    devCat: "all", query: "",
    settings: { ...SETTINGS }, stack: [], map: null,
    ob: 0, obAuto: true, obAllowed: [false, false, false],
  };

  const top = () => (S.stack.length ? S.stack[S.stack.length - 1].id : "main");

  /** ☰ and search show on the map only (not over Settings). */
  function updateChrome() {
    const t = top();
    $(".menu-btn", phone).classList.toggle("gone", t !== "main");
    $(".top-search", phone).classList.toggle("gone", t !== "main" || !S.session);
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
    S.nav = [];
    S.order = id ? D.getSessionDevices(id).sort((a, b) => b.rssi - a.rssi).map((d) => d.addr) : []; // sorted once
    const sess = id && D.getRecording(id);
    S.map.setSession(sess, id ? sessionPoints() : []);
    renderDiscover();
    updateChrome();
    const r = $(".recorder", phone);
    $(".rec-meta", r).textContent = S.recorder === "idle" ? "" : `${D.getCurrentRecording().devices} devices · ${locationText()}`;
  }
  const sessionPoints = () => D.getSessionDevices(S.session).map((d) => ({ addr: d.addr, at: d.best, rssi: d.rssi, category: d.category, product: d.product || "unidentified" }));

  /** The recorder: "idle" (FAB), "capsule" or "panel". The capsule's width and the panel's height are measured. */
  function setRecorder(state) {
    S.recorder = state;
    const r = $(".recorder", phone), row = $(".rec-row", r);
    // the row is max-content wide, so this is the content width even when measured from the open panel
    if (state === "capsule") r.style.setProperty("--capsule-w", `${row.offsetWidth}px`);
    if (state === "panel") r.style.setProperty("--panel-h", `${[...r.children].reduce((h, c) => h + c.offsetHeight + parseFloat(getComputedStyle(c).marginTop) + parseFloat(getComputedStyle(c).marginBottom), 0) + 2}px`);
    // idle is the FAB at the bottom right; recording is always the island at the top center
    const top = state !== "idle", a = r.getBoundingClientRect(), moving = r.classList.contains("top") !== top;
    r.classList.remove("idle", "capsule", "panel");
    r.classList.add(state);
    r.classList.toggle("top", top);
    if (moving) {
      const b = r.getBoundingClientRect();
      r.animate([{ transform: `translate(${a.left - b.left}px, ${a.top - b.top}px)` }, { transform: "none" }],
        { duration: parseFloat(tok("--t-morph")) * 1000, easing: tok("--ease") });
    }
    r.setAttribute("aria-label", state === "idle" ? "Record" : "Recording");
    $(".recorder-catch", phone).classList.toggle("on", state === "panel");
    phone.classList.toggle("rec-top-open", state === "panel");
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

  // ---- Sheet snapping ----
  // Each sheet lists its snap states in data-snaps: the device sheet "collapsed expanded", the Discover sheet
  // "collapsed half full". The sheet is always its full height; a state is a translateY offset (px from the top).
  const SNAP_STATES = ["dismissed", "collapsed", "half", "full", "expanded"];
  const sheetState = (sheet) => SNAP_STATES.find((st) => sheet.classList.contains(st)) || null;
  function setSheetState(sheet, state) {
    if (state === "dismissed") return closeDevice();
    SNAP_STATES.forEach((st) => sheet.classList.toggle(st, st === state));
    if (sheet.classList.contains("disc-sheet")) liftRecorder();
  }
  function snapOffsets(sheet) {
    const h = sheet.offsetHeight, peek = parseFloat(getComputedStyle(sheet).getPropertyValue("--peek")) || 92;
    const hv = tok("--discover-sheet-half"); // e.g. "67cqh": a share of the phone's height
    const half = hv.endsWith("cqh") ? (phone.clientHeight * parseFloat(hv)) / 100 : parseFloat(hv) || h;
    const at = { dismissed: h + 8, collapsed: h - peek, half: Math.max(0, h - half), full: 0, expanded: 0 };
    return sheet.dataset.snaps.split(" ").map((st) => ({ st, y: at[st] })).sort((a, b) => b.y - a.y); // lowest first
  }
  /** How much of the screen bottom the Discover sheet covers. */
  function sheetCover() {
    const ds = $(".disc-sheet", phone);
    if (!ds || !ds.classList.contains("open")) return 0;
    const o = snapOffsets(ds).find((x) => x.st === sheetState(ds));
    return ds.offsetHeight - (o ? o.y : 0);
  }
  /** On release: a flick moves exactly one state from where the drag started; otherwise the nearest state. */
  function snapTo(sheet, y, v, from) {
    const offs = snapOffsets(sheet), i = Math.max(0, offs.findIndex((o) => o.st === from));
    let pick;
    if (Math.abs(v) > 0.4) pick = offs[v < 0 ? Math.min(i + 1, offs.length - 1) : Math.max(i - 1, 0)];
    else pick = offs.reduce((a, b) => (Math.abs(b.y - y) < Math.abs(a.y - y) ? b : a));
    setSheetState(sheet, pick.st);
  }
  function nextUp(sheet) { const offs = snapOffsets(sheet), i = offs.findIndex((o) => o.st === sheetState(sheet)); return offs[Math.min(i + 1, offs.length - 1)].st; }
  function nextDown(sheet) { const offs = snapOffsets(sheet), i = offs.findIndex((o) => o.st === sheetState(sheet)); return offs[Math.max(i - 1, 0)].st; }

  // Dragging. On the handle and header the sheet moves directly. On the Discover sheet's content (Google Maps
  // style) dragging up first raises the sheet to Full and only then scrolls; dragging down first scrolls the
  // content back to its top and then lowers the sheet.
  let suppressClick = false;
  phone.addEventListener("click", (e) => { if (suppressClick) { suppressClick = false; e.stopPropagation(); e.preventDefault(); } }, true);
  phone.addEventListener("pointerdown", (e) => {
    const sheet = e.target.closest(".sheet.open:not(.static)");
    if (!sheet || e.button > 0) return;
    const onHead = !!e.target.closest(".handle, .dev-top, .disc-head");
    // touches on the Discover sheet's content are handled by the touch listeners below (native scrolling)
    const body = sheet.classList.contains("disc-sheet") && e.pointerType !== "touch" && e.target.closest(".sheet-body, .disc-chips");
    if (!onHead && !body) return;
    if (onHead && e.target.closest("a, button") && !e.target.closest(".handle")) return; // header controls are taps
    const scroller = $(".sheet-body", sheet);
    const offs = snapOffsets(sheet), top = offs[offs.length - 1].y, bottom = offs[0].y;
    const from = sheetState(sheet);
    let y = (offs.find((o) => o.st === from) || offs[0]).y, lastY = e.clientY, moved = false, sheetMoved = false;
    const y0 = e.clientY, samples = [[performance.now(), e.clientY]];
    const move = (ev) => {
      if (!moved && Math.abs(ev.clientY - y0) < 6) return;
      if (!moved) { moved = true; sheet.setPointerCapture(ev.pointerId); sheet.classList.add("dragging"); }
      let dy = ev.clientY - lastY;
      lastY = ev.clientY;
      if (body && !onHead) {
        if (dy < 0 && y <= top) { scroller.scrollTop -= dy; dy = 0; }            // at Full: scroll the content
        else if (dy > 0 && scroller.scrollTop > 0) {                             // content first back to its top
          const used = Math.min(dy, scroller.scrollTop);
          scroller.scrollTop -= used; dy -= used;
        }
      }
      if (dy) { y = Math.max(top, Math.min(bottom, y + dy)); sheetMoved = true; sheet.style.transform = `translateY(${y}px)`; }
      samples.push([performance.now(), ev.clientY]);
      if (samples.length > 5) samples.shift();
    };
    const up = () => {
      sheet.removeEventListener("pointermove", move);
      sheet.removeEventListener("pointerup", up);
      sheet.removeEventListener("pointercancel", up);
      sheet.classList.remove("dragging");
      sheet.style.transform = "";
      if (!moved) {
        // a tap on the handle: collapsed rises one step, anything else goes back down
        if (onHead && sheet.classList.contains("disc-sheet") && !e.target.closest("a, button"))
          setSheetState(sheet, sheetState(sheet) === "collapsed" ? nextUp(sheet) : "collapsed");
        return;
      }
      suppressClick = true;
      setTimeout(() => (suppressClick = false), 0);
      if (!sheetMoved) return;
      const [t0, p0] = samples[0], [t1, p1] = samples[samples.length - 1];
      snapTo(sheet, y, (p1 - p0) / Math.max(1, t1 - t0), from); // px per ms, positive is down
    };
    sheet.addEventListener("pointermove", move);
    sheet.addEventListener("pointerup", up);
    sheet.addEventListener("pointercancel", up);
  });
  // Touch on a sheet's content (the Discover sheet and the device sheet). At Full the browser scrolls natively (momentum, glide). The gesture is
  // taken over only when the sheet is not at Full (dragging moves it), or at scrollTop 0 when the finger moves down
  // (that lowers it). The decision is made on the first move, while touchmove can still be cancelled.
  phone.addEventListener("touchstart", (e) => {
    const sheet = e.target.closest(".disc-sheet.open, .dev-sheet.open");
    if (!sheet || e.touches.length > 1 || !e.target.closest(".sheet-body, .disc-chips")) return;
    const scroller = $(".sheet-body", sheet), offs = snapOffsets(sheet), from = sheetState(sheet);
    const top = offs[offs.length - 1].y, bottom = offs[0].y;
    const t = e.touches[0], y0 = t.clientY, samples = [[performance.now(), y0]];
    let mode = null, y = (offs.find((o) => o.st === from) || offs[0]).y, lastY = y0;
    const move = (ev) => {
      const cy = ev.touches[0].clientY;
      if (!mode) {
        if (Math.abs(cy - y0) < 6) return;
        const atTop = from === offs[offs.length - 1].st; // Full, or the device sheet's open state
        mode = !atTop || (scroller.scrollTop <= 0 && cy > y0) ? "sheet" : "native";
        if (mode === "sheet") sheet.classList.add("dragging");
      }
      if (mode === "native") return;
      ev.preventDefault();
      let dy = cy - lastY;
      lastY = cy;
      if (dy < 0 && y <= top) { scroller.scrollTop -= dy; dy = 0; } // reached Full within this gesture: scroll
      if (dy) { y = Math.max(top, Math.min(bottom, y + dy)); sheet.style.transform = `translateY(${y}px)`; }
      samples.push([performance.now(), cy]);
      if (samples.length > 5) samples.shift();
    };
    const end = () => {
      phone.removeEventListener("touchmove", move);
      phone.removeEventListener("touchend", end);
      phone.removeEventListener("touchcancel", end);
      if (mode !== "sheet") return;
      sheet.classList.remove("dragging");
      sheet.style.transform = "";
      suppressClick = true;
      setTimeout(() => (suppressClick = false), 0);
      const [t0, p0] = samples[0], [t1, p1] = samples[samples.length - 1];
      snapTo(sheet, y, (p1 - p0) / Math.max(1, t1 - t0), from);
    };
    phone.addEventListener("touchmove", move, { passive: false });
    phone.addEventListener("touchend", end);
    phone.addEventListener("touchcancel", end);
  }, { passive: true });

  // A wheel or trackpad does the same on the Discover sheet: down raises it to Full first, up at the top lowers it.
  let wheelLock = 0;
  phone.addEventListener("wheel", (e) => {
    const sheet = e.target.closest(".disc-sheet.open");
    if (!sheet || !e.target.closest(".sheet-body, .disc-chips, .disc-head, .handle")) return;
    const scroller = $(".sheet-body", sheet), st = sheetState(sheet);
    if (e.deltaY > 0 && st !== "full") { e.preventDefault(); if (Date.now() > wheelLock) { setSheetState(sheet, "full"); wheelLock = Date.now() + 400; } }
    else if (e.deltaY < 0 && scroller.scrollTop <= 0 && st !== "collapsed") { e.preventDefault(); if (Date.now() > wheelLock) { setSheetState(sheet, nextDown(sheet)); wheelLock = Date.now() + 400; } }
  }, { passive: false });

  /** The sidebar pushes the map 82% to the right; ☰ rides its edge and turns into "collapse sidebar". */
  function setDrawer(open) {
    if (open) renderSidebar();
    phone.classList.toggle("side-open", open);
    const b = $(".menu-btn", phone);
    b.classList.toggle("open", open);
    b.setAttribute("aria-label", open ? "Close sidebar" : "Sessions and settings");
    b.setAttribute("aria-expanded", open);
  }
  // ---- Navigation inside the Discover sheet: the base view (categories or list), then a product ----
  /** Pushes a level; the scroll position of the level below is kept for Back. */
  function pushSheet(entry) {
    const body = $("#disc-body");
    entry.below = body.scrollTop;
    S.nav.push(entry);
    renderDiscover();
    body.scrollTop = 0;
  }
  /** Back: pops one level and restores exactly what was below (content, scroll, filters). */
  function popSheet() {
    const e = S.nav.pop();
    if (!e) return;
    if (e.kind === "product") S.map.setPoints(sessionPoints());
    renderDiscover();
    $("#disc-body").scrollTop = e.below;
  }
  /** The product view: the background map shows only that product's points. */
  function openProduct(id) {
    const p = D.getProduct(id);
    const pts = productDevices(p).map((d) => { const b = d.best ? { at: d.best, rssi: d.rssi } : bestPoint(d.addr); return b && { addr: d.addr, at: b.at, rssi: b.rssi, category: d.category }; }).filter(Boolean);
    S.map.setPoints(pts);
    S.map.fit(pts.map((x) => x.at));
    pushSheet({ kind: "product", id });
  }

  // ---- The device sheet: a second sheet over the Discover sheet (Google Maps' place sheet) ----
  // Open (up to below the top row) or dismissed, nothing in between. A solid scrim dims everything behind it;
  // the Discover sheet underneath keeps its state, view, scroll and filters.
  function openDevice(addr, move) {
    const d = D.getDevice(addr), sheet = $(".dev-sheet", phone);
    stopGatt();
    S.device = { addr, tab: "adv" };
    $("#dev-top").innerHTML = UI.deviceHead(d);
    $("#dev-body").innerHTML = UI.deviceBody(d, { tab: "adv", gatt: D.getDeviceGatt(addr), chartW: phone.clientWidth - 32 });
    $("#dev-body").scrollTop = 0;
    S.map.select(addr, move);
    setSheetState(sheet, "expanded");
    sheet.classList.add("open");
    $(".dev-scrim", phone).classList.add("on");
  }
  // The GATT discovery's steps are revealed on a short timer chain: the prototype's only timer, to show the sequence.
  function gattTabNow(addr) {
    const run = S.gattRun && S.gattRun.addr === addr ? S.gattRun : null;
    if (run) return UI.gattTab(run.view);
    const g = D.getDeviceGatt(addr);
    return UI.gattTab(g ? { phase: "done", result: g, shown: g.steps.length, addr } : { phase: "idle", addr });
  }
  function runGatt(addr) {
    stopGatt();
    const result = D.discoverGatt(addr);
    const frames = [];
    result.steps.forEach((st, i) => {
      frames.push({ phase: "running", shown: i, running: i });
      if (st.progress && !st.failed) frames.push({ phase: "running", shown: i, running: i, progress: st.progress });
      if (st.failed) frames.push({ phase: "failed", shown: i + 1 });
    });
    if (result.ok) frames.push({ phase: "done", shown: result.steps.length });
    const run = (S.gattRun = { addr, timers: [], view: null });
    const show = (f) => {
      run.view = { ...f, result, addr };
      const tab = $("#dev-tab");
      if (S.device && S.device.addr === addr && S.device.tab === "gatt" && tab) tab.innerHTML = UI.gattTab(run.view);
      if (f.phase !== "running") S.gattRun = f.phase === "failed" ? run : null; // a finished run is read from the data next time
    };
    frames.forEach((f, k) => run.timers.push(setTimeout(() => show(f), k === 0 ? 0 : 450 + k * 450)));
  }
  function stopGatt() { if (S.gattRun) S.gattRun.timers.forEach(clearTimeout); S.gattRun = null; }

  function closeDevice() {
    if (!S.device) return;
    stopGatt();
    S.device = null;
    const sheet = $(".dev-sheet", phone);
    sheet.classList.remove("open", "expanded");
    $(".dev-scrim", phone).classList.remove("on");
    S.map.clear(true);
  }

  /** The top-right search: the button opens the field across the top row; an empty field closes again. */
  function setSearch(open) {
    const el = $(".top-search", phone), input = $("#top-q");
    el.classList.toggle("open", open);
    $(".ts-toggle", el).setAttribute("aria-expanded", open);
    input.tabIndex = open ? 0 : -1;
    if (open) input.focus();
    else if (S.query) { input.value = ""; S.query = ""; renderDiscover(); }
  }

  const ACTIONS = {
    back: () => pop(),
    sidebar: () => setDrawer(!phone.classList.contains("side-open")),
    "side-close": () => setDrawer(false),
    session: (el) => {
      setDrawer(false);
      const cur = D.getCurrentRecording();
      showSession(el.dataset.value, !!cur && cur.id === el.dataset.value);
    },
    settings: () => { setDrawer(false); push("settings", settingsPage()); },
    setting: (el) => {
      const k = el.dataset.value;
      S.settings[k] = !S.settings[k];
      el.setAttribute("aria-checked", S.settings[k]);
      if (k === "hideUnnamedApple") renderDiscover();
    },
    upload: () => {},
    wiki: (el) => open(`https://wirelesswiki.org/p/${el.dataset.value}`, "_blank", "noopener"),
    "open-session-map": () => { setRecorder("capsule"); popAll(); S.map.fit(D.getCurrentRecording().route); },
    product: (el) => openProduct(el.dataset.value),
    "sheet-back": () => popSheet(),
    "device-close": () => closeDevice(),
    device: (el) => openDevice(el.dataset.value, true),
    "dev-tab": (el) => {
      const t = S.device;
      t.tab = el.dataset.value;
      const d = D.getDevice(t.addr);
      $("#dev-seg").innerHTML = UI.segmented([{ id: "adv", label: "Advertising" }, { id: "gatt", label: "GATT" }], t.tab, "dev-tab");
      $("#dev-tab").innerHTML = t.tab === "adv" ? UI.advertising(d) : gattTabNow(t.addr);
    },
    // GATT: the button turns into Cancel | I am authorized (the prompt every time); authorized starts the discovery
    "gatt-go": (el) => {
      const flow = el.closest(".gatt-flow");
      if (!flow.classList.contains("confirm")) {
        flow.classList.add("confirm");
        el.textContent = "I am authorized";
        $(".gatt-cancel", flow).tabIndex = 0;
      } else runGatt(el.dataset.value);
    },
    "gatt-cancel": (el) => {
      const flow = el.closest(".gatt-flow");
      flow.classList.remove("confirm");
      $(".gatt-go", flow).textContent = "Discover GATT";
      el.tabIndex = -1;
    },
    "gatt-retry": (el) => {
      $("#dev-tab").innerHTML = UI.gattTab({ phase: "idle", addr: el.dataset.value });
      const go = $(".gatt-go", phone);
      requestAnimationFrame(() => ACTIONS["gatt-go"](go)); // straight to the prompt
    },
    "map-point": (el) => openDevice(el.dataset.value, false),
    "map-cluster": (el) => S.map.zoomCluster(el.dataset.value, [+el.dataset.lng, +el.dataset.lat]),
    "dev-cat": (el) => {
      S.devCat = el.dataset.value;
      renderDiscover();
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
    "disc-view": () => { S.discView = S.discView === "list" ? "categories" : "list"; renderDiscover(); $("#disc-body").scrollTop = 0; },
    search: () => setSearch(!$(".top-search", phone).classList.contains("open") || !!S.query),
    "ob-allow": (el) => { S.obAllowed[+el.dataset.value] = true; renderOnboarding(); },
    "ob-auto": (el) => { S.obAuto = !S.obAuto; el.setAttribute("aria-checked", S.obAuto); },
    "ob-next": () => { S.ob++; renderOnboarding(); },
    "ob-back": () => { S.ob--; renderOnboarding(); },
    // a new user has no recordings yet: first run, recording straight away if they chose to
    "ob-done": () => { location.href = `${location.pathname}?state=${S.obAuto ? "2&first=1" : "first"}`; },
  };

  phone.addEventListener("click", (e) => {
    const t = e.target.closest("[data-action]");
    if (!t || !phone.contains(t)) return;
    const f = ACTIONS[t.dataset.action];
    // any control in the Discover sheet's header also raises a collapsed sheet to Half
    const ds = t.closest(".disc-sheet");
    const raise = ds && t.closest(".disc-head") && sheetState(ds) === "collapsed";
    if (f) { e.stopPropagation(); f(t, e); }
    if (raise) setSheetState(ds, "half");
  });
  // A .when-scrolled fade shows only once its page's scroller has left the top (the product hero stays clean).
  phone.addEventListener("scroll", (e) => {
    const f = e.target.parentElement && e.target.parentElement.querySelector(":scope > .top-fade.when-scrolled");
    if (f) f.classList.toggle("on", e.target.scrollTop > 0);
  }, true);

  // an empty search field closes when it loses focus
  phone.addEventListener("focusout", (e) => { if (e.target.id === "top-q" && !e.target.value) setTimeout(() => !S.query && setSearch(false), 120); });

  // Escape stands in for system back: the topmost layer closes first.
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape" || location.hash) return;
    if (S.device) closeDevice();
    else if (S.nav.length) popSheet();
    else if (phone.classList.contains("side-open")) setDrawer(false);
    else if ($(".top-search.open", phone)) setSearch(false);
    else if (S.recorder === "panel") setRecorder("capsule");
    else if (S.stack.length) pop();
  });
  phone.addEventListener("input", (e) => {
    if (e.target.id === "top-q") { S.query = e.target.value; renderDiscover(); }
  });

  /** Launch. Recording when "Record when the app opens" is on; otherwise All sessions, or only the location for a
   *  brand-new user. Demo states: ?state=1 (All sessions), 2 (recording), 3 (after Stop), 4 (a history session),
   *  first (first run, no recordings ever); 5 is the old name of 1. */
  function boot() {
    renderMain();
    updateChrome();
    S.map = createMainMap($("#main-map"));
    const q = new URLSearchParams(location.search).get("state");
    if (q === "first" || new URLSearchParams(location.search).get("first")) D.useFirstRunDemo();
    const state = q === "first" ? 1 : +q || (S.settings.autoRecord ? 2 : 1);
    if (state === 2 || state === 3) startRecording();
    if (state === 3) stopRecording();
    if (state === 4) showSession("r1", false);
    if (state === 1 || state === 5) showDefault();
  }
  /** Not recording: All sessions, or nothing but the location when there are no recordings at all. */
  function showDefault() {
    showSession(D.getAllSessionsView().sessions ? "all" : null, false);
  }

  // ---- Gallery (#gallery): every component in each state, built from the same UI functions ----
  function renderGallery() {
    document.body.classList.add("is-gallery");
    const devs = D.getDevices();
    const normal = devs.find((d) => d.name && !d.stale), stale = devs.find((d) => d.name && d.stale), noName = devs.find((d) => !d.name && !d.stale);
    const groups = D.getProducts("all", "now");
    const inRange = groups[0].products[0], before = groups.flatMap((g) => g.products).find((p) => !p.inRange), unknown = groups[groups.length - 1].products[0];
    const sheetDev = devs.find((d) => d.name === "JBL Flip 6");
    const cell = (label, html, style = "") => `<div class="g-cell"><div class="g-box" style="${style}">${html}</div><small>${label}</small></div>`;
    const sect = (name, toks) => `<div class="g-sect">${name}<span class="g-tok">${toks}</span></div>`;
    const swatch = (t) => `<div class="g-cell"><div class="g-sw" style="background: var(${t})"></div><small>${t}</small></div>`;
    const shadow = (t) => `<div class="g-cell"><div class="g-sw" style="background: var(--fill1); box-shadow: var(${t})"></div><small>${t}</small></div>`;
    $("#stage").innerHTML = `<div class="gallery">
      <h1>Cluetooth UI v2 components</h1>
      <p>Each component in each state, rendered by the same functions as the app. <a href="./">Open the app</a></p>

      ${sect("Demo states", "?state=1..4, ?state=first · #onboarding")}
      <div class="g-row g-links">
        <a href="./?state=1">1 · Opened, not recording: All sessions</a>
        <a href="./?state=2">2 · Recording</a>
        <a href="./?state=3">3 · After Stop (frozen)</a>
        <a href="./?state=4">4 · History session</a>
        <a href="./?state=first">First run (no recordings yet)</a>
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
      ${sect("TopSearch", ".top-search · --rb → wiki .sf (--fill2, --search-size, inset --focus-color); filters by name")}
      <div class="g-row">${cell("closed", UI.topSearch(false, "static"), "width: 80px")}${cell("open", UI.topSearch(true, "static"), "width: 340px")}</div>
      ${sect("MenuButton", ".menu-btn · ☰ → collapse sidebar, rides the sidebar edge; --side-w, --t-std")}
      <div class="g-row">${cell("closed", UI.menuButton(false, "static"))}${cell("open", UI.menuButton(true, "static"))}</div>
      ${sect("IconButton", ".ib · --icon-btn, --icon-btn-glyph; neutral --fill2/--t1; rec --rec-red/--on-rec")}
      <div class="g-row">
        ${cell("Pause", UI.iconButton({ name: "pause", label: "Pause", action: "" }))}
        ${cell("Resume", UI.iconButton({ name: "play", label: "Resume", action: "" }))}
        ${cell("Stop", UI.iconButton({ name: "stop", label: "Stop", action: "", kind: "rec" }))}
      </div>
      ${sect("Recorder (main map only: idle FAB bottom-right, recording = top-center island)", ".recorder · --fab, --fab-dot, --rec, --on-rec, --recorder-pad, --recorder-map-h, --shadow-float → --shadow-raised, --t-morph; BlinkingDot .rec-dot · --rec-dot, --t-blink")}
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
      ${sect("Device sheet (over the Discover sheet)", ".dev-sheet · open or dismissed; --sheet-expanded-h; close button .ib neutral; --scrim behind; RssiChart ticks −65/−80 only")}
      <div class="g-row">
        ${[["identified, Advertising", sheetDev, "adv", D.getDeviceGatt(sheetDev.addr)], ["GATT read", sheetDev, "gatt", D.getDeviceGatt(sheetDev.addr)], ["unidentified, no name, GATT not read", noName, "gatt", null]]
          .map(([label, d, tab, gatt]) => cell(label, `<section class="sheet static dev-sheet"><div class="handle"></div><div class="dev-top">${UI.deviceHead(d)}</div><div class="sheet-body">${UI.deviceBody(d, { tab, gatt, chartW: 380 })}</div></section>`, "width: 412px; padding: 0")).join("")}
      </div>
      ${sect("GATT tab flow", ".gatt-flow · note always shown; Discover GATT → Cancel | I am authorized; steps .gstep (spinner, done, failed --rssi-weak); Try again")}
      <div class="g-row">
        ${(() => { const r = { ok: true, steps: [{ label: "Connecting…", detail: "0.8 s" }, { label: "Discovering services…", detail: "5 services" }, { label: "Reading characteristics", detail: "18/18", progress: "12/18" }, { label: "Disconnected", detail: "" }], services: (D.getDeviceGatt(sheetDev.addr) || { services: [] }).services };
          const f = { ok: false, steps: [{ label: "Connecting…", detail: "0.8 s" }, { label: "Discovering services…", detail: "5 services" }, { label: "Reading characteristics", detail: "Disconnected unexpectedly", failed: true }], services: [] };
          const box = (label, html) => cell(label, `<section class="sheet static dev-sheet"><div class="sheet-body">${html}</div></section>`, "width: 380px; padding: 12px 0");
          return [box("1 · before discovery", UI.gattTab({ phase: "idle" })), box("2 · confirm", UI.gattTab({ phase: "confirm" })),
            box("3 · running (reading 12/18)", UI.gattTab({ phase: "running", result: r, shown: 2, running: 2, progress: "12/18" })),
            box("4 · failed", UI.gattTab({ phase: "failed", result: f, shown: 3 })), box("5 · done, with results", UI.gattTab({ phase: "done", result: r, shown: 4 }))].join(""); })()}
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
      ${sect("Discover sheet", ".disc-sheet · three snaps: collapsed (peek), --discover-sheet-half (67%), --discover-sheet-full; view switch shows the view it switches TO")}
      <div class="g-row">
        ${cell("live, categories", `<section class="sheet static disc-sheet"><div class="handle"></div><div class="disc-head">${UI.discoverHead({ view: "categories", live: true, mode: "nearby" })}</div><div class="sheet-body">${UI.categorySection({ ...groups[1], products: groups[1].products.slice(0, 2) })}</div></section>`, "width: 412px; padding: 0")}
        ${cell("live, list", `<section class="sheet static disc-sheet"><div class="handle"></div><div class="disc-head">${UI.discoverHead({ view: "list", live: true, mode: "all" })}</div><div class="disc-chips">${UI.chipRow(CATS, "all", "", "inline")}</div><div class="sheet-body"><div class="rows">${devs.slice(0, 3).map((d) => UI.deviceRow(d)).join("")}</div></div></section>`, "width: 412px; padding: 0")}
        ${cell("history, categories (no Nearby / All)", `<section class="sheet static disc-sheet"><div class="handle"></div><div class="disc-head">${UI.discoverHead({ view: "categories", live: false })}</div></section>`, "width: 412px; padding: 0")}
        ${cell("history, list", `<section class="sheet static disc-sheet"><div class="handle"></div><div class="disc-head">${UI.discoverHead({ view: "list", live: false })}</div></section>`, "width: 412px; padding: 0")}
        ${cell("product view", `<section class="sheet static disc-sheet"><div class="handle"></div><div class="disc-head">${UI.discoverHead({ product: "govee" })}</div><div class="sheet-body">${UI.productView(D.getProduct("govee"), D.getProduct("govee").devices.slice(0, 2).map((d) => UI.deviceRow(d, { spark: false })).join(""))}</div></section>`, "width: 412px; padding: 0")}
      </div>
      ${sect("TopFade", ".top-fade · --fade-h, --fade-solid; solid gradient to --bg, no blur")}
      <div class="g-row">${cell("over a list", `<div style="position: relative; height: 150px; overflow: hidden">${devs.slice(0, 3).map((d) => UI.deviceRow(d)).join("")}<div class="top-fade" style="height: 90px"></div></div>`, "width: 412px; padding: 0")}</div>
    </div>`;
    miniMap($("#g-rec-map"), D.getRecording("now").route);
    $("#g-q").focus();
  }

  window.CluetoothUI = { UI, icon, band, minus };
  addEventListener("hashchange", () => location.reload());
  if (location.hash === "#gallery") renderGallery();
  else if (location.hash === "#onboarding") renderOnboarding();
  else boot();
})();
