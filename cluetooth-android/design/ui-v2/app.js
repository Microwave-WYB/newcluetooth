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
    locateOff: '<circle cx="12" cy="12" r="7"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/><path d="M4 4l16 16"/>',
    locate: '<circle cx="12" cy="12" r="7"/><circle class="solid" cx="12" cy="12" r="2.5"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/>',
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

    /** LocationWarning: the locate button's spot while there is no usable location; tapping opens its popover. */
    locWarn: (cls = "") => `<button class="rb loc-warn ${cls}" data-action="loc-pop" aria-label="Location problem">${icon("locateOff")}</button>`,
    /** LocationPopover: the reason, and the fix when there is one. */
    locPop: (status, cls = "") => `
      <div class="loc-pop ${cls}" role="dialog" aria-label="Location">
        <p>${esc(status.message)}</p>
        ${status.action ? UI.button({ kind: "primary", label: status.action.label, action: "loc-fix", value: status.action.id, cls: "small" }) : ""}
      </div>`,

    /** SearchBox: one element. Closed, the round search button (top right). Open, it expands into the full-width
     *  wiki search field with a back arrow (its icon morphs), while the search page fades in under it. */
    searchBox: (cls = "") => `
      <div class="search-box ${cls}">
        <button class="sbx-btn" data-action="search-open" aria-label="Search devices">${icon("search", "i-a")}${icon("back", "i-b")}</button>
        <input id="search-q" type="search" placeholder="Search name or MAC" autocomplete="off" tabindex="-1" aria-label="Search devices">
      </div>`,

    /** SearchBar: search state's top row; the text reopens the search page, the X leaves search state. */
    searchBar: (query, cls = "") => `
      <div class="search-bar ${cls}">
        <button class="sb-text" data-action="search-edit">${icon("search")}<span>${esc(query)}</span></button>
        ${UI.iconButton({ name: "close", label: "Exit search", action: "search-exit" })}
      </div>`,

    /** NoResults: nothing matched; in an area, a text button searches the whole session view. */
    noResults: (inArea) => `<div class="empty">${inArea ? "No results in this area" : "No devices match"}${inArea
      ? `<br><button class="text-btn" data-action="search-everywhere">Search everywhere</button>` : ""}</div>`,

    /** SearchPage: fades in under the expanded search box; the matching devices. */
    searchPage: () => `<div class="scroll search-results"><div class="rows" id="search-rows"></div></div>`,

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

    /** DiscoverHead: the view switch (it shows the view it switches TO), then Nearby / All while recording, or
     *  This area / All for history and All sessions.
     */
    discoverHead: ({ view, live, mode, hist = "all" }) => `${UI.iconButton({ name: view === "list" ? "grid" : "list", label: view === "list" ? "Show categories" : "Show the device list", action: "disc-view" })}
         ${live ? UI.segmented([{ id: "nearby", label: "Nearby" }, { id: "all", label: "All" }], mode, "disc-mode")
           : UI.segmented([{ id: "area", label: "This area" }, { id: "all", label: "All" }], hist, "hist-mode")}
         <span></span>`,

    /** ProductView: a product sheet's content; the background map shows the product's points. */
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

    /** SheetTop: a pushed sheet's header with the same close button (X) at the top right. */
    sheetTop: (inner) => `${inner}${UI.iconButton({ name: "close", label: "Close", action: "sheet-close" })}`,

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

    /** DeviceHead: name, product wiki link, MAC with RSSI. */
    deviceHead: (d) => `
        <div class="dev-head">
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
        <div class="sheet-fade"></div>
        <div class="sheet-body" id="disc-body"></div>
      </section>
      ${UI.floatButton({ name: "locate", label: "Show my location", action: "locate", cls: "locate-btn gone" })}
      ${UI.locWarn("gone")}
      <div class="pop-catch" data-action="loc-pop-close"></div>
      <div class="loc-pop-slot" id="loc-pop"></div>
      <div class="recorder-catch" data-action="recorder-close"></div>
      ${UI.recorder({ state: "idle", paused: false, time: "12:34", meta: "", mapId: "rec-map" })}
      <div class="main-dim" data-action="side-close"></div>
      </div>
      <nav class="side" aria-label="Sessions"></nav>
      ${UI.menuButton(false)}
      ${UI.searchBox()}
      <div class="search-bar-slot" id="search-bar"></div>
      <div class="search-area gone" id="search-area">${UI.chip({ label: "Search this area", on: false, action: "search-area" })}</div>
      <div id="pages"></div>
      <div class="scrim" data-action="sheet-close"></div>`;
  }

  /** The Discover sheet (the stack's root): categories or the device list (Nearby / All only while live). */
  function renderDiscover() {
    const sheet = $(".disc-sheet", phone);
    const sess = S.session && D.getRecording(S.session);
    sheet.classList.toggle("open", !!sess);
    if (!sess) return liftRecorder();
    const mode = S.live ? S.discoverMode : "all";
    const head = $("#disc-head"), chips = $("#disc-chips"), body = $("#disc-body");
    if (S.search) {
      // search state: the matched devices only, list view, "N results"
      const n = S.search.order.length, where = S.search.bbox ? " in this area" : "";
      head.innerHTML = `<span></span><span class="disc-title">${n} ${n === 1 ? "result" : "results"}${where}</span><span></span>`;
      chips.innerHTML = "";
      body.innerHTML = n ? `<div class="rows">${resultRows()}</div>` : UI.noResults(!!S.search.bbox);
    } else {
    head.innerHTML = UI.discoverHead({ view: S.discView, live: S.live, mode, hist: S.histMode });
    if (S.discView === "list") {
      chips.innerHTML = UI.chipRow(CATS, S.devCat, "dev-cat", "inline");
      body.innerHTML = `<div class="rows">${sessionRows(mode)}</div>`;
    } else {
      chips.innerHTML = "";
      const area = areaCounts();
      const groups = D.getProducts(mode, S.session)
        .map((g) => ({ ...g, products: g.products.filter((p) => !area || area.count[p.id])
          .map((p) => (area ? { ...p, count: area.count[p.id], inRange: Math.min(p.inRange, area.live[p.id] || 0) } : p)) }))
        .filter((g) => g.products.length);
      body.innerHTML = groups.map(UI.categorySection).join("") || `<div class="empty">No products match</div>`;
    }
    }
    sheet.style.setProperty("--peek", `${head.offsetTop + head.offsetHeight}px`);
    // the first state: the peek on phone, the open panel on tablet (collapsed there would be a bare header)
    if (!sheetState(sheet)) setSheetState(sheet, isTablet() ? "full" : "collapsed");
    liftRecorder();
  }
  /** The search results as rows (search state's sheet and the search page), in the session's fixed order. */
  function resultRows(addrs = S.search && S.search.order, action = "device") {
    if (!addrs) return "";
    const byAddr = Object.fromEntries(D.getSessionDevices(S.session).map((d) => [d.addr, d]));
    return addrs.map((a) => byAddr[a]).filter((d) => d && !hiddenByFilter(d)).map((d) => UI.deviceRow(d, { spark: S.live, action })).join("")
      || `<div class="empty">No devices match</div>`;
  }
  /** Matching addresses (data.js searchDevices, within bbox if given) in the fixed order of the shown session. */
  const searchOrder = (query, bbox) => { const hit = new Set(D.searchDevices(query, S.session, bbox) || []); return S.order.filter((a) => hit.has(a)); };

  /** The record FAB (bottom right) and the locate button (bottom left) stay anchored just above the Discover sheet's
   *  collapsed peek. They live below the sheets, so a rising sheet slides over them and a collapsing one uncovers
   *  them: no hiding. */
  function liftRecorder() {
    const root = $(".disc-sheet", phone), open = root.classList.contains("open") && !isTablet();
    phone.style.setProperty("--rec-lift", `${open ? parseFloat(getComputedStyle(root).getPropertyValue("--peek")) || 0 : 0}px`);
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
    return (S.area ? S.areaOrder : S.order).map((a) => byAddr[a])
      .filter((d) => d && inArea(d.addr) && !hiddenByFilter(d) && (mode === "all" || !d.stale) && (S.devCat === "all" || d.category === S.devCat))
      .map((d) => UI.deviceRow(d, { spark: S.live })).join("") || `<div class="empty">No matching devices</div>`;
  }
  // ---- "This area" (history and All sessions): the sheets list only devices whose point is in the viewport ----
  // It re-filters once each time the map comes to rest after the user dragged or zoomed it (not during the move,
  // not after the app's own camera moves). Rows still in view keep their order, rows that left hide, new ones append.
  const inArea = (addr) => !S.area || S.area.has(addr);
  /** Per product, how many of the session's devices are in the area (null when This area is off). */
  function areaCounts() {
    if (!S.area) return null;
    const count = {}, live = {};
    for (const d of D.getSessionDevices(S.session)) {
      if (!S.area.has(d.addr)) continue;
      const k = d.product || "unidentified";
      count[k] = (count[k] || 0) + 1;
      if (!d.stale) live[k] = (live[k] || 0) + 1;
    }
    return { count, live };
  }
  function applyArea() {
    const vis = S.map.visibleAddrs();
    const kept = S.area ? S.areaOrder.filter((a) => vis.has(a)) : [];
    S.areaOrder = [...kept, ...S.order.filter((a) => vis.has(a) && !kept.includes(a))];
    S.area = vis;
    refreshSheets();
  }
  /** The locate button shows once the user's own pan or zoom has taken their location off center (more than 40 px,
   *  or any zoom since the last recenter); never after the app's camera moves, never without a location. */
  function updateLocate(zoomed, byUser = true) {
    if (zoomed) S.locZoomed = true;
    const status = D.getLocationStatus();
    // no usable location: the warning is always there instead (sheets still cover it)
    $(".loc-warn", phone).classList.toggle("gone", !status.warn);
    const show = byUser && !status.warn && status.state === "ok" && !!ME && (S.locZoomed || S.map.locOffset() > 40);
    $(".locate-btn", phone).classList.toggle("gone", !show);
  }
  function setLocPop(open) {
    $("#loc-pop").innerHTML = open ? UI.locPop(D.getLocationStatus()) : "";
    $(".pop-catch", phone).classList.toggle("on", open);
  }
  /** The map came to rest; byUser: after the user's drag or zoom. */
  function mapMoved(byUser, zoomed) {
    if (byUser) updateLocate(zoomed);
    if (S.search) {
      // the app's own camera moves (framing a picked device) move the reference; only the user's moves offer the chip
      if (!byUser) { if (!S.search.moved) S.search.ref = S.map.view(); }
      else { S.search.moved = S.map.movedFrom(S.search.ref); updateSearchArea(); }
    }
    if (byUser && S.histMode === "area" && S.session && !S.live) applyArea();
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

  // ---- Maps (MapLibre). Everything drawn over the base map is style layers from map-style.js: no DOM markers. ----
  const MS = window.CluetoothMapStyle;
  function boundsOf(coords) {
    const b = new maplibregl.LngLatBounds(coords[0], coords[0]);
    coords.forEach((c) => b.extend(c));
    return b;
  }
  const lineGeo = (routes) => ({ type: "FeatureCollection", features: routes.filter((r) => r.length > 1).map((r) => ({ type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: r } })) });
  const fc = (points) => ({ type: "FeatureCollection", features: points.map((p) => ({ type: "Feature", properties: { ...p, at: undefined }, geometry: { type: "Point", coordinates: p.at } })) });
  /** The style values, read from tokens.css. */
  const styleColors = () => ({
    route: tok("--map-route"), routeOpacity: +tok("--map-route-opacity"),
    density: tok("--map-density"), densityOpacity: +tok("--map-density-opacity"), densityRadius: +tok("--map-density-radius"),
    point: tok("--blue"), stroke: tok("--on-blue"), clusterText: tok("--on-blue"), selected: tok("--t1"), me: tok("--blue"),
    rssiStrong: tok("--rssi-strong"), rssiFair: tok("--rssi-fair"), rssiWeak: tok("--rssi-weak"),
    start: tok("--map-start"), end: tok("--map-end"), endText: tok("--map-end"), halo: tok("--fill1"), font: "Noto Sans Bold",
  });
  function addStyle(map, only) {
    const src = MS.sources();
    for (const id in src) map.addSource(id, src[id]);
    MS.layers(styleColors()).filter((l) => !only || only.includes(l.id)).forEach((l) => map.addLayer(l));
  }

  /** A small non-interactive preview of a route (the recorder panel). */
  function miniMap(container, route) {
    const coords = route.length ? route : [ME.at];
    const map = new maplibregl.Map({ container, style: MAP_STYLE(), interactive: false, attributionControl: false,
      bounds: boundsOf(coords), fitBoundsOptions: { padding: 28, maxZoom: 16 } });
    map.on("load", () => {
      addStyle(map, ["route", "end-dots", "end-labels"]);
      map.getSource("route").setData(lineGeo([route]));
      map.getSource("ends").setData(MS.endsOf(route));
    });
    return map;
  }

  /** The main map: one instance for the whole app. A session shows its route and one point per device. */
  function createMainMap(container) {
    const map = new maplibregl.Map({ container, style: MAP_STYLE(), attributionControl: false, center: ME.at, zoom: 16 });
    map.addControl(new maplibregl.AttributionControl({ compact: true }), "bottom-left");
    container._map = map; // a handle for the screenshot tests
    const M = { sel: null, points: [], at: {}, ready: false, pending: [] };
    const whenReady = (f) => (M.ready ? f() : M.pending.push(f));
    // frame what the sheets leave visible above them
    const panelLeft = () => (isTablet() ? parseFloat(tok("--edge")) + $(".disc-sheet", phone).offsetWidth : 0);
    const pad = () => ({ top: 90, bottom: sheetCover() + 40, left: panelLeft() + 40, right: 40 });

    map.on("load", () => {
      addStyle(map);
      M.ready = true;
      M.pending.splice(0).forEach((f) => f());
    });
    function paintSelection() {
      for (const [layer, kind, prop, value] of MS.selectionProps(M.sel, +tok("--dim"))) {
        if (kind === "filter") map.setFilter(layer, value);
        else map.setPaintProperty(layer, prop, value);
      }
    }
    // taps: a point opens its device, a cluster zooms in, empty map clears the selection
    map.on("click", (e) => {
      const r = 8, box = [[e.point.x - r, e.point.y - r], [e.point.x + r, e.point.y + r]];
      const f = map.queryRenderedFeatures(box, { layers: ["clusters", "points"] })[0];
      if (!f) return Stack.entries.length > 1 ? Stack.pop() : M.clear(true);
      if (f.properties.cluster) return M.zoomCluster(f.properties.cluster_id, f.geometry.coordinates);
      openDevice(f.properties.addr, false);
    });
    map.on("mousemove", (e) => {
      const r = 8, hit = map.queryRenderedFeatures([[e.point.x - r, e.point.y - r], [e.point.x + r, e.point.y + r]], { layers: ["clusters", "points"] }).length;
      map.getCanvas().style.cursor = hit ? "pointer" : "";
    });
    M.visibleAddrs = () => { const b = map.getBounds(); return new Set(M.points.filter((p) => b.contains(p.at)).map((p) => p.addr)); };
    M.bbox = () => { const b = map.getBounds(); return [b.getWest(), b.getSouth(), b.getEast(), b.getNorth()]; };
    M.view = () => ({ center: map.getCenter().toArray(), zoom: map.getZoom() });
    /** Has the view moved away from ref: a zoom change of a level or more, or more than 25% of the viewport? */
    M.movedFrom = (ref) => {
      if (Math.abs(map.getZoom() - ref.zoom) >= 1) return true;
      const p = map.project(ref.center), w = map.getCanvas().clientWidth, h = map.getCanvas().clientHeight;
      return Math.abs(p.x - w / 2) / w > 0.25 || Math.abs(p.y - h / 2) / h > 0.25;
    };
    let userMove = false, zoomAtStart = 0;
    map.on("moveend", () => { mapMoved(userMove, userMove && Math.abs(map.getZoom() - zoomAtStart) > 0.01); userMove = false; });
    // the user panning or zooming the map (drag, pinch, wheel, double-tap) collapses the sheet in front;
    // camera moves made by the app have no originalEvent
    map.on("movestart", (e) => {
      if (!e.originalEvent) return;
      userMove = true;
      zoomAtStart = map.getZoom();
      const ds = Stack.top().el; // the sheet in front
      if (ds.classList.contains("open") && sheetState(ds) !== "collapsed") setSheetState(ds, "collapsed");
    });

    function setPoints(points) {
      M.points = points;
      M.at = Object.fromEntries(points.map((p) => [p.addr, p.at]));
      const pts = points.map((p) => ({ addr: p.addr, at: p.at }));
      map.getSource("devices").setData(fc(pts));
      map.getSource("density").setData(fc(pts));
    }
    /** null: only the user's location. Otherwise the session's route(s) and points, framed. */
    M.setSession = (sess, points) => whenReady(() => {
      M.clear(true);
      map.getSource("me").setData(fc(sess ? [] : [{ at: ME.at }]));
      if (!sess) {
        setPoints([]);
        map.getSource("route").setData(lineGeo([]));
        map.getSource("ends").setData(MS.endsOf([]));
        map.jumpTo({ center: ME.at, zoom: 16 });
        return;
      }
      setPoints(points);
      map.getSource("route").setData(lineGeo(sess.routes || [sess.route]));
      map.setPaintProperty("route", "line-opacity", +tok(sess.routes ? "--map-routes-opacity" : "--map-route-opacity"));
      map.getSource("ends").setData(MS.endsOf(sess.routes ? [] : sess.route)); // All sessions: no single route
      const coords = [...(sess.routes || [sess.route]).flat(), ...points.map((p) => p.at)];
      if (coords.length) map.fitBounds(boundsOf(coords), { padding: pad(), maxZoom: 17, duration: 0 });
    });
    /** How far (px) the user's location is from the center of the map area the sheets leave visible. */
    M.locOffset = () => {
      if (!ME) return 0;
      const p = map.project(ME.at), l = panelLeft(), w = map.getCanvas().clientWidth - l, h = map.getCanvas().clientHeight - sheetCover();
      return Math.hypot(p.x - l - w / 2, p.y - h / 2);
    };
    /** The camera to the user's location, at street level (or closer if already closer); an app move. */
    M.locate = () => whenReady(() => map.easeTo({ center: ME.at, zoom: Math.max(map.getZoom(), 17), padding: { top: 0, bottom: sheetCover(), left: panelLeft(), right: 0 }, duration: 600 }));
    M.fit = (coords) => whenReady(() => coords.length && map.fitBounds(boundsOf(coords), { padding: pad(), maxZoom: 17 }));
    M.select = (addr, move) => whenReady(() => {
      M.sel = addr;
      const sightings = D.getDeviceSightings(addr);
      map.getSource("sightings").setData(fc(sightings));
      const at = M.at[addr] || (sightings[0] && sightings[0].at);
      const below = Math.max(sheetCover(), 320);
      if (move && at) map.easeTo({ center: at, zoom: Math.max(map.getZoom(), 18.2), padding: { top: 0, bottom: isTablet() ? 0 : below, left: panelLeft(), right: 0 }, duration: 600 });
      paintSelection();
    });
    /** Removes the device highlight. */
    M.clear = (quiet) => {
      if (!M.ready) return;
      if (M.sel) {
        M.sel = null;
        map.getSource("sightings").setData(fc([]));
        paintSelection();
      }
    };
    M.camera = (cam) => {
      if (!cam) return { center: map.getCenter().toArray(), zoom: map.getZoom() };
      whenReady(() => map.easeTo({ ...cam, duration: 300 }));
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
    session: null, live: false, order: [], layout: null,
    recorder: "idle", paused: false, recMap: null,
    discoverMode: "nearby", discView: "categories",
    devCat: "all", search: null, page: null, locZoomed: false, // search: { query, bbox, order, ref, moved, saved } in search state; page: the search page's scope
    histMode: "all", area: null, areaOrder: [], // area: the devices in view while "This area" is on
    settings: { ...SETTINGS }, stack: [], map: null,
    ob: 0, obAuto: true, obAllowed: [false, false, false],
  };

  const top = () => (S.stack.length ? S.stack[S.stack.length - 1].id : "main");

  // ---- Layout: one value, like Compose's WindowSizeClass. compact (< --bp-medium) is the phone layout; medium and
  // expanded use the tablet layout: a floating left panel holds the sheet stack, the map fills the rest.
  const isTablet = () => S.layout !== "compact";
  function computeLayout() {
    const w = phone.clientWidth, mode = w >= parseFloat(tok("--bp-expanded")) ? "expanded" : w >= parseFloat(tok("--bp-medium")) ? "medium" : "compact";
    if (mode === S.layout) return;
    const wasCompact = S.layout === "compact";
    S.layout = mode;
    phone.dataset.layout = mode;
    phone.classList.toggle("tablet", isTablet());
    if (!Stack.entries.length) return;
    // the panel has two states: open (full) and collapsed
    if (isTablet()) Stack.entries.forEach((e) => { if (sheetState(e.el) === "half") setSheetState(e.el, "full"); });
    // from phone to tablet: the phone's resting peek becomes the open panel
    if (isTablet() && wasCompact) Stack.entries.forEach((e) => { if (sheetState(e.el) === "collapsed") setSheetState(e.el, "full"); });
    Stack.entries.forEach((e) => (e.kind === "discover" ? renderDiscover() : renderSheet(e)));
    couple(); updateScrim(); liftRecorder();
    if (S.recorder !== "idle") setRecorder(S.recorder === "panel" ? "capsule" : S.recorder); // the recorder's place depends on the layout
  }

  /** ☰ and search show on the map only (not over Settings). */
  function updateChrome() {
    const t = top();
    $(".menu-btn", phone).classList.toggle("gone", t !== "main");
    $(".search-box", phone).classList.toggle("gone", !(t === "main" || t === "search") || !S.session);
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
    if (e.id === "search") morphSearchBox(false);
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
    Stack.reset();
    S.histMode = "all"; S.area = null; S.areaOrder = []; // per session view
    if (S.search) { S.search = null; phone.classList.remove("searching"); $("#search-bar").innerHTML = ""; } // a new view leaves search
    S.order = id ? D.getSessionDevices(id).sort((a, b) => b.rssi - a.rssi).map((d) => d.addr) : []; // sorted once
    const sess = id && D.getRecording(id);
    S.map.setSession(sess, id ? sessionPoints() : []);
    renderDiscover();
    updateChrome();
    const r = $(".recorder", phone);
    $(".rec-meta", r).textContent = S.recorder === "idle" ? "" : `${D.getCurrentRecording().devices} devices · ${locationText()}`;
  }
  const sessionPoints = () => D.getSessionDevices(S.session).filter((d) => !S.search || S.search.order.includes(d.addr)).map((d) => ({ addr: d.addr, at: d.best, rssi: d.rssi, category: d.category, product: d.product || "unidentified" }));

  /** The recorder: "idle" (FAB), "capsule" or "panel". The capsule's width and the panel's height are measured. */
  function setRecorder(state) {
    S.recorder = state;
    const r = $(".recorder", phone), row = $(".rec-row", r);
    if (state === "panel") r.style.setProperty("--panel-h", `${[...r.children].reduce((h, c) => h + c.offsetHeight + parseFloat(getComputedStyle(c).marginTop) + parseFloat(getComputedStyle(c).marginBottom), 0) + 2}px`);
    // idle is the FAB at the bottom right; recording is the island at the top center on phone, and widens in place
    // into the capsule (FAB height) at the bottom right on tablet
    const top = state !== "idle" && !isTablet(), a = r.getBoundingClientRect(), moving = r.classList.contains("top") !== top;
    r.classList.remove("idle", "capsule", "panel");
    r.classList.add(state);
    r.classList.toggle("top", top);
    // the row is max-content wide, so this is the capsule's content width (measured with the capsule's sizes)
    if (state === "capsule") r.style.setProperty("--capsule-w", `${row.offsetWidth}px`);
    if (moving) {
      const b = r.getBoundingClientRect();
      r.animate([{ transform: `translate(${a.left - b.left}px, ${a.top - b.top}px)` }, { transform: "none" }],
        { duration: parseFloat(tok("--t-morph")) * 1000, easing: tok("--ease") });
    }
    r.setAttribute("aria-label", state === "idle" ? "Record" : "Recording");
    $(".recorder-catch", phone).classList.toggle("on", state === "panel");
    phone.classList.toggle("rec-top-open", state === "panel");
    phone.classList.toggle("recording", state !== "idle");
    // tablet: the locate / warning button sits above the recorder and moves up while its panel is open
    phone.style.setProperty("--rec-h", state === "panel" ? r.style.getPropertyValue("--panel-h") || tok("--recorder-panel-h") : tok("--fab"));
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

  // ---- Sheets ----
  // One sheet component. Snap states come from data-snaps: the root (the Discover sheet) "collapsed half full",
  // every pushed sheet the same plus "dismissed" (dragging down from Collapsed pops it). A sheet is always its full
  // height; a state is a translateY offset (px from the top).
  const SNAP_STATES = ["dismissed", "collapsed", "half", "full"];
  const sheetState = (sheet) => SNAP_STATES.find((st) => sheet.classList.contains(st)) || null;
  function setSheetState(sheet, state) {
    if (state === "dismissed") return Stack.pop();
    if (isTablet() && state === "half") state = "full"; // the panel: open or collapsed
    SNAP_STATES.forEach((st) => sheet.classList.toggle(st, st === state));
    if (sheet === Stack.top().el) { couple(); updateScrim(); }
    liftRecorder();
  }
  const offsetOf = (sheet, state) => (snapOffsets(sheet).find((o) => o.st === state) || { y: 0 }).y;
  /** Every sheet below the top one sits at the top sheet's height (fully covered). liveY: during a drag. */
  function couple(liveY) {
    const top = Stack.top().el, y = liveY != null ? liveY : offsetOf(top, sheetState(top));
    phone.classList.toggle("stacked", Stack.entries.length > 1);
    Stack.entries.forEach((e) => {
      const below = e.el !== top;
      e.el.classList.toggle("following", below);
      e.el.classList.toggle("dragging", below && liveY != null);
      if (below) e.el.style.setProperty("--follow-y", `${y}px`);
      else e.el.style.removeProperty("--follow-y");
    });
  }
  /** The scrim shows only while a pushed sheet is on top at Full; tapping it pops one level. */
  function updateScrim() {
    const top = Stack.top(), on = !isTablet() && Stack.entries.length > 1 && sheetState(top.el) === "full"; // no scrim for panel levels
    const scrim = $(".scrim", phone);
    scrim.style.zIndex = (+top.el.style.zIndex || 81) - 1;
    scrim.classList.toggle("on", on);
  }

  /**
   * SheetStack. entries[0] is the root (the Discover sheet, never closes). push(kind, props) opens a sheet on top
   * (a product, or a device over whatever is showing; any depth); pop() closes the top one, and the sheet below gets
   * back its height, scroll, view, filters and the map as it was. Compose mirrors this as a list of sheet entries.
   */
  const Stack = {
    entries: [],
    top() { return this.entries[this.entries.length - 1]; },
    entryOf(el) { return this.entries.find((e) => e.el === el); },
    init(root) { this.entries = [{ el: root, kind: "discover", props: {} }]; },
    push(kind, props) {
      const below = this.top();
      below.saved = { state: sheetState(below.el), scroll: $(".sheet-body", below.el).scrollTop, camera: S.map.camera() };
      const el = document.createElement("section");
      el.className = `sheet stack-sheet ${kind}-sheet`;
      el.dataset.snaps = "dismissed collapsed half full";
      el.setAttribute("aria-label", kind === "device" ? "Device" : "Product");
      el.style.zIndex = 81 + this.entries.length * 2;
      el.innerHTML = `<div class="handle"></div><div class="sheet-top"></div><div class="sheet-fade"></div><div class="sheet-body"></div>`;
      phone.append(el);
      const entry = { el, kind, props };
      this.entries.push(entry);
      renderSheet(entry);
      el.getBoundingClientRect(); // starts below the screen and slides up
      el.classList.add("open");
      // opens at Half, or at the height of the sheet below when that is higher
      setSheetState(el, below.saved.state === "full" ? "full" : "half");
      applyMap(entry, true);
    },
    /** A device opened while a device is on top replaces it, on the same level and at the same height. */
    replaceTop(kind, props) {
      const top = this.top();
      if (top.kind === "device") stopGatt();
      top.kind = kind;
      top.props = props;
      renderSheet(top);
      $(".sheet-body", top.el).scrollTop = 0;
      applyMap(top, true);
    },
    pop() {
      if (this.entries.length < 2) return;
      const gone = this.entries.pop();
      if (gone.kind === "device") stopGatt();
      gone.el.classList.remove("open", "following", ...SNAP_STATES);
      setTimeout(() => gone.el.remove(), 400);
      const top = this.top();
      setSheetState(top.el, top.saved.state);
      $(".sheet-body", top.el).scrollTop = top.saved.scroll;
      applyMap(top, false);
      S.map.camera(top.saved.camera);
    },
    /** A new session view: back to the root alone. */
    reset() {
      while (this.entries.length > 1) { const e = this.entries.pop(); if (e.kind === "device") stopGatt(); e.el.remove(); }
      if (this.entries.length) { couple(); updateScrim(); }
    },
  };
  /** The map shows what the top sheet is about: the session, a product's points, or a device's sightings. */
  function applyMap(entry, opening) {
    if (entry.kind === "discover") { S.map.setPoints(sessionPoints()); return; }
    if (entry.kind === "product") {
      const pts = productPoints(D.getProduct(entry.props.id));
      S.map.setPoints(pts);
      if (opening) S.map.fit(pts.map((x) => x.at));
      return;
    }
    S.map.select(entry.props.addr, opening && entry.props.move);
  }
  /** A pushed sheet's header and content. */
  function renderSheet(entry) {
    const el = entry.el, top = $(".sheet-top", el), body = $(".sheet-body", el);
    if (entry.kind === "device") {
      const d = D.getDevice(entry.props.addr);
      top.innerHTML = UI.sheetTop(UI.deviceHead(d));
      body.innerHTML = UI.deviceBody(d, { tab: entry.props.tab, gatt: null, chartW: phone.clientWidth - 32 });
      $("#dev-tab", body).innerHTML = entry.props.tab === "adv" ? UI.advertising(d) : gattTabNow(d.addr);
    } else {
      const p = D.getProduct(entry.props.id);
      top.innerHTML = UI.sheetTop(`<span class="sheet-kicker">${esc((CATS.find((c) => c.id === p.category) || { label: "Unidentified" }).label)}</span>`);
      const rows = productDevices(p).filter((d) => inArea(d.addr)).map((d) => UI.deviceRow(d, { spark: S.live })).join("");
      body.innerHTML = UI.productView(p, rows || `<div class="empty">No matching devices</div>`);
    }
    el.style.setProperty("--peek", `${top.offsetTop + top.offsetHeight}px`);
  }
  /** After the search, the area or a filter changes: the root and every product sheet, scroll kept. */
  function refreshSheets() {
    Stack.entries.forEach((e) => {
      const body = $(".sheet-body", e.el), sc = body.scrollTop;
      if (e.kind === "discover") renderDiscover();
      else if (e.kind === "product") renderSheet(e);
      else return;
      body.scrollTop = sc;
    });
  }
  /** A product's points on the map: its devices in the shown session (or all of them), each at its strongest point. */
  const productPoints = (p) => productDevices(p).map((d) => { const b = d.best ? { at: d.best, rssi: d.rssi } : bestPoint(d.addr); return b && { addr: d.addr, at: b.at, rssi: b.rssi }; }).filter(Boolean);
  function openDevice(addr, move) {
    const props = { addr, tab: "adv", move };
    if (Stack.top().kind === "device") Stack.replaceTop("device", props);
    else Stack.push("device", props);
  }
  const devEntry = (addr) => Stack.entries.find((e) => e.kind === "device" && e.props.addr === addr);

  function snapOffsets(sheet) {
    const h = sheet.offsetHeight, peek = parseFloat(getComputedStyle(sheet).getPropertyValue("--peek")) || 92;
    const hv = tok("--discover-sheet-half"); // e.g. "67cqh": a share of the phone's height
    const half = hv.endsWith("cqh") ? (phone.clientHeight * parseFloat(hv)) / 100 : parseFloat(hv) || h;
    const at = { dismissed: h + 8, collapsed: h - peek, half: Math.max(0, h - half), full: 0, expanded: 0 };
    return sheet.dataset.snaps.split(" ").map((st) => ({ st, y: at[st] })).sort((a, b) => b.y - a.y); // lowest first
  }
  /** How much of the screen bottom the sheets cover (the top sheet). */
  function sheetCover() {
    if (isTablet() || !$(".disc-sheet", phone).classList.contains("open")) return 0; // the tablet panel is at the left
    const top = Stack.top().el;
    return top.offsetHeight - offsetOf(top, sheetState(top));
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
  const SLOP = parseFloat(tok("--drag-slop")) || 8; // a pointer that moves less is a tap
  let suppressClick = false, headDrag = false;
  // while a header drag moves the sheet, its touch is ours: no browser scroll or fling (which would eat the next tap)
  phone.addEventListener("touchmove", (e) => { if (headDrag && e.cancelable) e.preventDefault(); }, { passive: false });
  phone.addEventListener("click", (e) => { if (suppressClick) { suppressClick = false; e.stopPropagation(); e.preventDefault(); } }, true);
  phone.addEventListener("pointerdown", (e) => {
    const sheet = e.target.closest(".sheet.open:not(.static)");
    if (!sheet || e.button > 0) return;
    const onHead = !!e.target.closest(".handle, .sheet-top, .disc-head");
    // touches on the Discover sheet's content are handled by the touch listeners below (native scrolling)
    const body = e.pointerType !== "touch" && e.target.closest(".sheet-body, .disc-chips");
    if (!onHead && !body) return;
    // The whole header drags the sheet, controls included: under --drag-slop it is a tap and the control works;
    // past it, vertically, it is a drag and the control does not fire. A mostly horizontal move is left alone.
    const scroller = $(".sheet-body", sheet);
    const offs = snapOffsets(sheet), top = offs[offs.length - 1].y, bottom = offs[0].y;
    const from = sheetState(sheet);
    let y = (offs.find((o) => o.st === from) || offs[0]).y, lastY = e.clientY, moved = false, sheetMoved = false;
    const y0 = e.clientY, x0 = e.clientX, samples = [[performance.now(), e.clientY]];
    const move = (ev) => {
      if (isTablet()) return; // the panel does not drag; a tap on its header toggles it
      if (!moved) {
        const dx = Math.abs(ev.clientX - x0), dy0 = Math.abs(ev.clientY - y0);
        if (Math.max(dx, dy0) < SLOP) return;
        if (dx > dy0) return up(null, true); // horizontal: not ours
        moved = true;
        headDrag = true;
        sheet.setPointerCapture(ev.pointerId); // the control under the pointer no longer gets the pointer: no click
        sheet.classList.add("dragging");
        document.activeElement && document.activeElement.blur();
      }
      let dy = ev.clientY - lastY;
      lastY = ev.clientY;
      if (body && !onHead) {
        if (dy < 0 && y <= top) { scroller.scrollTop -= dy; dy = 0; }            // at Full: scroll the content
        else if (dy > 0 && scroller.scrollTop > 0) {                             // content first back to its top
          const used = Math.min(dy, scroller.scrollTop);
          scroller.scrollTop -= used; dy -= used;
        }
      }
      if (dy) { y = Math.max(top, Math.min(bottom, y + dy)); sheetMoved = true; sheet.style.transform = `translateY(${y}px)`; if (sheet === Stack.top().el) couple(y); }
      samples.push([performance.now(), ev.clientY]);
      if (samples.length > 5) samples.shift();
    };
    const up = (ev, aborted) => {
      sheet.removeEventListener("pointermove", move);
      sheet.removeEventListener("pointerup", up);
      sheet.removeEventListener("pointercancel", up);
      sheet.classList.remove("dragging");
      sheet.style.transform = "";
      headDrag = false;
      if (aborted || (ev && ev.type === "pointercancel" && !moved)) return; // the browser took it (a horizontal pan)
      if (!moved) {
        // a tap on the handle: collapsed rises one step, anything else goes back down
        if (onHead && !e.target.closest("a, button")) setSheetState(sheet, sheetState(sheet) === "collapsed" ? nextUp(sheet) : "collapsed");
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
    const sheet = e.target.closest(".sheet.open:not(.static)");
    if (isTablet() || !sheet || e.touches.length > 1 || !e.target.closest(".sheet-body, .disc-chips")) return;
    const scroller = $(".sheet-body", sheet), offs = snapOffsets(sheet), from = sheetState(sheet);
    const top = offs[offs.length - 1].y, bottom = offs[0].y;
    const t = e.touches[0], y0 = t.clientY, samples = [[performance.now(), y0]];
    let mode = null, y = (offs.find((o) => o.st === from) || offs[0]).y, lastY = y0;
    const move = (ev) => {
      const cy = ev.touches[0].clientY;
      if (!mode) {
        if (Math.abs(cy - y0) < SLOP) return;
        const atTop = from === offs[offs.length - 1].st; // Full, or the device sheet's open state
        mode = !atTop || (scroller.scrollTop <= 0 && cy > y0) ? "sheet" : "native";
        if (mode === "sheet") sheet.classList.add("dragging");
      }
      if (mode === "native") return;
      ev.preventDefault();
      let dy = cy - lastY;
      lastY = cy;
      if (dy < 0 && y <= top) { scroller.scrollTop -= dy; dy = 0; } // reached Full within this gesture: scroll
      if (dy) { y = Math.max(top, Math.min(bottom, y + dy)); sheet.style.transform = `translateY(${y}px)`; if (sheet === Stack.top().el) couple(y); }
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
    const sheet = e.target.closest(".sheet.open:not(.static)");
    if (isTablet() || !sheet || !e.target.closest(".sheet-body, .disc-chips, .disc-head, .sheet-top, .handle")) return;
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
      const e = devEntry(addr), tab = e && $("#dev-tab", e.el);
      if (e && e.props.tab === "gatt" && tab) tab.innerHTML = UI.gattTab(run.view);
      if (f.phase !== "running") S.gattRun = f.phase === "failed" ? run : null; // a finished run is read from the data next time
    };
    frames.forEach((f, k) => run.timers.push(setTimeout(() => show(f), k === 0 ? 0 : 450 + k * 450)));
  }
  function stopGatt() { if (S.gattRun) S.gattRun.timers.forEach(clearTimeout); S.gattRun = null; }

  // ---- Search (Google Maps style) ----
  // The search button opens a full-screen page: back arrow, a wiki search field, the matching devices (data.js
  // matchDevice / searchDevices, the session's fixed order). Tapping a result enters search state on the map: a
  // full-width search bar replaces the top row, the Discover sheet lists only the matches ("N results"), the map
  // shows only their points, and the result's device sheet opens at Half. The bar's X (or Back with only the root
  // sheet left) leaves search state and restores the sheet's view, height and scroll.
  /** The search box expands from the button to the full-width field (back arrow + input) while the page fades in;
   *  closing reverses it. From search state the bar is already full width: the box opens and closes in place. */
  function morphSearchBox(open) {
    const box = $(".search-box", phone), input = $("#search-q"), inPlace = !!S.search;
    if (inPlace) { box.classList.add("no-anim"); box.getBoundingClientRect(); }
    if (open) box.classList.add("lift");
    box.classList.toggle("open", open);
    $(".sbx-btn", box).setAttribute("aria-label", open ? "Close search" : "Search devices");
    input.tabIndex = open ? 0 : -1;
    if (!open) input.blur();
    if (inPlace) { box.getBoundingClientRect(); box.classList.remove("no-anim"); }
    if (!open) setTimeout(() => { if (!box.classList.contains("open")) box.classList.remove("lift"); }, inPlace ? 0 : parseFloat(tok("--t-fade")) * 1000);
  }
  function openSearchPage() {
    const q = S.search ? S.search.query : "", input = $("#search-q");
    S.page = { bbox: S.map.bbox() }; // the area the search runs in: the map as it is now
    input.value = q;
    morphSearchBox(true);
    push("search", UI.searchPage(), { cls: "search-page", mount: () => {
      renderResults(q);
      input.focus();
      input.setSelectionRange(q.length, q.length);
    } });
  }
  function renderResults(q) {
    const rows = $("#search-rows", phone);
    if (!rows) return;
    if (!q.trim()) { rows.innerHTML = `<div class="empty">Search devices by name or MAC address</div>`; return; }
    const order = searchOrder(q, S.page.bbox);
    rows.innerHTML = order.length ? resultRows(order, "search-pick") : UI.noResults(!!S.page.bbox);
  }
  function enterSearch(query, bbox) {
    const root = $(".disc-sheet", phone);
    Stack.reset();
    if (!S.search) S.search = { saved: { view: S.discView, state: sheetState(root), scroll: $("#disc-body").scrollTop } };
    S.search.query = query.trim();
    S.search.bbox = bbox;
    S.search.ref = S.map.view();   // the searched area; moving away from it offers "Search this area"
    S.search.order = searchOrder(query, bbox);
    S.search.moved = false;
    phone.classList.add("searching");
    $("#search-bar").innerHTML = UI.searchBar(S.search.query);
    renderDiscover();
    $("#disc-body").scrollTop = 0;
    S.map.setPoints(sessionPoints());
    updateSearchArea();
  }
  /** "Search this area": the same query on the current viewport. Rows still matching keep their order, new matches
   *  are appended, rows no longer matching hide. */
  function searchThisArea() {
    const bbox = S.map.bbox(), hit = new Set(searchOrder(S.search.query, bbox));
    const kept = S.search.order.filter((a) => hit.has(a));
    S.search.order = [...kept, ...S.order.filter((a) => hit.has(a) && !kept.includes(a))];
    S.search.bbox = bbox;
    S.search.ref = S.map.view();
    S.search.moved = false;
    refreshSearch();
  }
  /** "Search everywhere": the same query across the whole session view. */
  function searchEverywhere() {
    if (S.stack.length && top() === "search") { S.page.bbox = null; renderResults($("#search-q").value); return; }
    S.search.bbox = null;
    S.search.order = searchOrder(S.search.query, null);
    S.search.moved = false;
    refreshSearch();
  }
  function refreshSearch() {
    renderDiscover();
    S.map.setPoints(sessionPoints());
    updateSearchArea();
  }
  /** The "Search this area" chip: in search state, after the user moved the map away from the searched area. */
  function updateSearchArea() {
    const el = $("#search-area");
    if (el) el.classList.toggle("gone", !(S.search && S.search.moved));
  }
  function exitSearch() {
    if (!S.search) return;
    const saved = S.search.saved;
    S.search = null;
    Stack.reset();
    phone.classList.remove("searching");
    $("#search-bar").innerHTML = "";
    updateSearchArea();
    S.discView = saved.view;
    renderDiscover();
    setSheetState($(".disc-sheet", phone), saved.state || "collapsed");
    $("#disc-body").scrollTop = saved.scroll;
    S.map.setPoints(sessionPoints());
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
      if (k === "hideUnnamedApple") refreshSheets();
    },
    upload: () => {},
    wiki: (el) => open(`https://wirelesswiki.org/p/${el.dataset.value}`, "_blank", "noopener"),
    "open-session-map": () => { setRecorder("capsule"); popAll(); S.map.fit(D.getCurrentRecording().route); },
    product: (el) => Stack.push("product", { id: el.dataset.value }),
    "sheet-close": () => Stack.pop(),
    device: (el) => openDevice(el.dataset.value, true),
    "dev-tab": (el) => {
      const sheet = el.closest(".sheet"), t = Stack.entryOf(sheet).props;
      t.tab = el.dataset.value;
      const d = D.getDevice(t.addr);
      $("#dev-seg", sheet).innerHTML = UI.segmented([{ id: "adv", label: "Advertising" }, { id: "gatt", label: "GATT" }], t.tab, "dev-tab");
      $("#dev-tab", sheet).innerHTML = t.tab === "adv" ? UI.advertising(d) : gattTabNow(t.addr);
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
      const tab = el.closest("#dev-tab");
      tab.innerHTML = UI.gattTab({ phase: "idle", addr: el.dataset.value });
      const go = $(".gatt-go", tab);
      requestAnimationFrame(() => ACTIONS["gatt-go"](go)); // straight to the prompt
    },
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
    "hist-mode": (el) => {
      S.histMode = el.dataset.value;
      S.area = null;
      if (S.histMode === "area") applyArea();
      else refreshSheets();
    },
    "disc-view": () => { S.discView = S.discView === "list" ? "categories" : "list"; renderDiscover(); $("#disc-body").scrollTop = 0; },
    "search-open": () => (top() === "search" ? pop() : openSearchPage()), // open: it is the panel's Back
    "search-edit": () => openSearchPage(),
    "search-exit": () => exitSearch(),
    "search-area": () => searchThisArea(),
    locate: () => { S.map.locate(); S.locZoomed = false; $(".locate-btn", phone).classList.add("gone"); },
    "loc-pop": () => setLocPop(!$("#loc-pop").innerHTML),
    "loc-pop-close": () => setLocPop(false),
    "loc-fix": (el) => { D.fixLocation(el.dataset.value); setLocPop(false); updateLocate(false, false); }, // a stub: the state clears
    "search-everywhere": () => searchEverywhere(),
    "search-pick": (el) => {
      const addr = el.dataset.value, q = $("#search-q").value, wasSearching = !!S.search;
      pop(); // the page fades out and the field shrinks back into the button
      const go = () => { enterSearch(q, S.page.bbox); openDevice(addr, true); };
      if (wasSearching) go(); else setTimeout(go, parseFloat(tok("--t-fade")) * 1000); // then search state
    },
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
    // a sheet's content fades out under its header once it is scrolled
    if (e.target.classList && e.target.classList.contains("sheet-body")) {
      const fade = e.target.previousElementSibling;
      if (fade && fade.classList.contains("sheet-fade")) fade.classList.toggle("on", e.target.scrollTop > 0);
    }
  }, true);


  // Escape stands in for system back: the topmost layer closes first.
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape" || location.hash) return;
    if ($("#loc-pop").innerHTML) setLocPop(false);
    else if (S.stack.length) pop(); // a page (the search page, Settings) closes first
    else if (Stack.entries.length > 1) Stack.pop();
    else if (phone.classList.contains("side-open")) setDrawer(false);
    else if (S.recorder === "panel") setRecorder("capsule");
    else if (S.search) exitSearch();
  });
  phone.addEventListener("input", (e) => {
    if (e.target.id === "search-q") renderResults(e.target.value);
  });

  /** Launch. Recording when "Record when the app opens" is on; otherwise All sessions, or only the location for a
   *  brand-new user. Demo states: ?state=1 (All sessions), 2 (recording), 3 (after Stop), 4 (a history session),
   *  first (first run, no recordings ever); 5 is the old name of 1. */
  /** The frame: ?layout=tablet (1024 × 768) or ?layout=phone (412 × 915) for review; otherwise a big desktop
   *  screen shows the phone frame, and anything else (phones, tablets, landscape) fills the window. */
  function setFrame() {
    const want = new URLSearchParams(location.search).get("layout");
    const frame = want === "tablet" ? "frame-tablet" : want === "phone" || (innerWidth >= 1100 && innerHeight >= 960) ? "frame-phone" : "";
    phone.classList.remove("frame-tablet", "frame-phone");
    if (frame) phone.classList.add(frame);
    $("#stage-link").hidden = !frame; // the gallery link only beside a review frame
  }
  function boot() {
    setFrame();
    renderMain();
    Stack.init($(".disc-sheet", phone));
    computeLayout();
    addEventListener("resize", () => { setFrame(); computeLayout(); });
    updateChrome();
    S.map = createMainMap($("#main-map"));
    const params = new URLSearchParams(location.search), q = params.get("state");
    if (params.get("loc")) D.setLocationDemo(params.get("loc")); // denied, off, waiting, stale
    if (q === "first" || new URLSearchParams(location.search).get("first")) D.useFirstRunDemo();
    const state = q === "first" ? 1 : +q || (S.settings.autoRecord ? 2 : 1);
    if (state === 2 || state === 3) startRecording();
    if (state === 3) stopRecording();
    if (state === 4) showSession("r1", false);
    if (state === 1 || state === 5) showDefault();
    updateLocate(false, false); // at launch only the warning can show; the locate button waits for a user move
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
        <a href="./?layout=tablet&state=1">Tablet · 1</a>
        <a href="./?layout=tablet&state=2">Tablet · 2 recording</a>
        <a href="./?layout=tablet&state=3">Tablet · 3</a>
        <a href="./?layout=tablet&state=4">Tablet · 4</a>
        <a href="./?layout=phone&state=1">Phone frame</a>
        <a href="./?state=1&loc=denied">Location: permission off</a>
        <a href="./?state=1&loc=off">Location: turned off</a>
        <a href="./?state=1&loc=waiting">Location: waiting</a>
        <a href="./?state=1&loc=stale">Location: stale</a>
        <a href="./#onboarding">Onboarding</a>
      </div>

      ${sect("Color", "tokens.css")}
      <div class="g-row">${["--bg", "--fill1", "--fill2", "--panel", "--t1", "--t2", "--t3", "--div", "--blue", "--chip-on-bg", "--chip-on-fg", "--seg-on-bg", "--rssi-strong", "--rssi-fair", "--rssi-weak", "--rec"].map(swatch).join("")}</div>
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
      ${sect("Search", ".search-box: the button expands into the wiki field with a back arrow (--t-fade) while the page fades in; search state: .search-bar (--fill1, --shadow-float, X)")}
      <div class="g-row">${cell("closed", UI.searchBox("static"), "width: 80px")}${cell("open", UI.searchBox("static open"), "width: 388px")}${cell("search bar (search state)", UI.searchBar("govee", "static"), "width: 388px")}
        ${cell("Search this area", `<div class="search-area static">${UI.chip({ label: "Search this area", on: false, action: "" })}</div>`)}
        ${cell("no results in this area", UI.noResults(true), "width: 300px")}</div>
      ${sect("MenuButton", ".menu-btn · ☰ → collapse sidebar, rides the sidebar edge; --side-w, --t-std")}
      <div class="g-row">${cell("closed", UI.menuButton(false, "static"))}${cell("open", UI.menuButton(true, "static"))}</div>
      ${sect("IconButton", ".ib · --icon-btn, --icon-btn-glyph; neutral --fill2/--t1; rec --rec-red/--on-rec")}
      <div class="g-row">
        ${cell("Pause", UI.iconButton({ name: "pause", label: "Pause", action: "" }))}
        ${cell("Resume", UI.iconButton({ name: "play", label: "Resume", action: "" }))}
        ${cell("Stop", UI.iconButton({ name: "stop", label: "Stop", action: "", kind: "rec" }))}
      </div>
      ${sect("Location warning", ".loc-warn (.rb, --warn) in the locate button's spot; .loc-pop (--panel, --r-pop, --shadow-raised); thresholds in data.js LOCATION_WARN")}
      <div class="g-row">${[["denied", "permission off"], ["off", "location turned off"], ["stale", "stale fix (waiting reads \"Waiting for location…\")"]]
        .map(([st, label]) => cell(label, `<div class="g-locwarn">${UI.locPop(D.locationStatusFor(st), "static")}${UI.locWarn("static")}</div>`, "width: 260px")).join("")}</div>
      ${sect("Search rule (data.js matchDevice / searchDevices)", "deterministic checks; the Rust core mirrors this rule")}
      <div class="g-row">${(() => {
        const cases = [
          ["qt", "QT-Sensor", "AA:BB:CC:00:11:22", true], ["qt", "SmartQt", "AA:BB:CC:00:11:22", true], ["qt", "aqtb", "AA:BB:CC:00:11:22", true],
          ["qt", "Other", "AA:BB:CC:00:11:22", false], ["4b4b", "No name", "72:CD:4C:4B:4B:6F", true], ["4B:4B", "No name", "72:CD:4C:4B:4B:6F", true],
          ["4b-4b", "No name", "72:CD:4C:4B:4B:6F", true], ["7d3f", "No name", "40:E7:7D:3F:7A:CD", true], ["ab", "Sensor", "AB:CD:EF:01:02:03", false],
          ["ab", "Abbey Road", "11:22:33:44:55:66", true], ["  ", "QT-Sensor", "AA:BB:CC:00:11:22", false]];
        const rowsHtml = cases.map(([q, n, a, want]) => { const got = D.matchDevice(q, n, a); return `<tr><td>${esc(JSON.stringify(q))}</td><td>${esc(n)}</td><td>${a}</td><td>${want}</td><td>${got === want ? "PASS" : "FAIL"}</td></tr>`; }).join("");
        const pass = cases.filter(([q, n, a, want]) => D.matchDevice(q, n, a) === want).length;
        return cell(`${pass}/${cases.length} pass`, `<table class="raw g-tests"><tr><th>Query</th><th>Name</th><th>MAC</th><th>Match</th><th>Result</th></tr>${rowsHtml}</table>`, "width: 640px");
      })()}</div>
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
      ${sect("Device sheet (a pushed sheet)", ".stack-sheet · collapsed / half / full like the root; the same X; --scrim only at Full; neutral; --scrim behind; RssiChart ticks −65/−80 only")}
      <div class="g-row">
        ${[["identified, Advertising", sheetDev, "adv", D.getDeviceGatt(sheetDev.addr)], ["GATT read", sheetDev, "gatt", D.getDeviceGatt(sheetDev.addr)], ["unidentified, no name, GATT not read", noName, "gatt", null]]
          .map(([label, d, tab, gatt]) => cell(label, `<section class="sheet static stack-sheet"><div class="handle"></div><div class="sheet-top">${UI.sheetTop(UI.deviceHead(d))}</div><div class="sheet-body">${UI.deviceBody(d, { tab, gatt, chartW: 380 })}</div></section>`, "width: 412px; padding: 0")).join("")}
      </div>
      ${sect("GATT tab flow", ".gatt-flow · note always shown; Discover GATT → Cancel | I am authorized; steps .gstep (spinner, done, failed --rssi-weak); Try again")}
      <div class="g-row">
        ${(() => { const r = { ok: true, steps: [{ label: "Connecting…", detail: "0.8 s" }, { label: "Discovering services…", detail: "5 services" }, { label: "Reading characteristics", detail: "18/18", progress: "12/18" }, { label: "Disconnected", detail: "" }], services: (D.getDeviceGatt(sheetDev.addr) || { services: [] }).services };
          const f = { ok: false, steps: [{ label: "Connecting…", detail: "0.8 s" }, { label: "Discovering services…", detail: "5 services" }, { label: "Reading characteristics", detail: "Disconnected unexpectedly", failed: true }], services: [] };
          const box = (label, html) => cell(label, `<section class="sheet static stack-sheet"><div class="sheet-body">${html}</div></section>`, "width: 380px; padding: 12px 0");
          return [box("1 · before discovery", UI.gattTab({ phase: "idle" })), box("2 · confirm", UI.gattTab({ phase: "confirm" })),
            box("3 · running (reading 12/18)", UI.gattTab({ phase: "running", result: r, shown: 2, running: 2, progress: "12/18" })),
            box("4 · failed", UI.gattTab({ phase: "failed", result: f, shown: 3 })), box("5 · done, with results", UI.gattTab({ phase: "done", result: r, shown: 4 }))].join(""); })()}
      </div>
      ${sect("Map style (map-style.js: MapLibre layers, no DOM markers)", "points and clusters --blue + --on-blue stroke; sightings by RSSI band; Start/End symbol labels with --fill1 halo; selection dims to --dim")}
      <div class="g-row">${cell("a past session (clusters, points, route, Start/End)", `<div id="g-style-map" style="width: 380px; height: 300px; border-radius: var(--r-item); overflow: hidden"></div>`, "padding: 0")}
        ${cell("layers as JSON (for MapLibre Android)", `<details><summary>map-style layers</summary><pre class="g-json">${esc(JSON.stringify(MS.layers(styleColors()), null, 2))}</pre></details>`, "width: 420px")}</div>
      ${sect("Discover sheet", ".disc-sheet · three snaps: collapsed (peek), --discover-sheet-half (67%), --discover-sheet-full; view switch shows the view it switches TO")}
      <div class="g-row">
        ${cell("live, categories", `<section class="sheet static disc-sheet"><div class="handle"></div><div class="disc-head">${UI.discoverHead({ view: "categories", live: true, mode: "nearby" })}</div><div class="sheet-body">${UI.categorySection({ ...groups[1], products: groups[1].products.slice(0, 2) })}</div></section>`, "width: 412px; padding: 0")}
        ${cell("live, list", `<section class="sheet static disc-sheet"><div class="handle"></div><div class="disc-head">${UI.discoverHead({ view: "list", live: true, mode: "all" })}</div><div class="disc-chips">${UI.chipRow(CATS, "all", "", "inline")}</div><div class="sheet-body"><div class="rows">${devs.slice(0, 3).map((d) => UI.deviceRow(d)).join("")}</div></div></section>`, "width: 412px; padding: 0")}
        ${cell("history, categories: This area / All", `<section class="sheet static disc-sheet"><div class="handle"></div><div class="disc-head">${UI.discoverHead({ view: "categories", live: false })}</div></section>`, "width: 412px; padding: 0")}
        ${cell("history, list", `<section class="sheet static disc-sheet"><div class="handle"></div><div class="disc-head">${UI.discoverHead({ view: "list", live: false })}</div></section>`, "width: 412px; padding: 0")}
        ${cell("product sheet", `<section class="sheet static stack-sheet"><div class="handle"></div><div class="sheet-top">${UI.sheetTop(`<span class="sheet-kicker">Lighting</span>`)}</div><div class="sheet-body">${UI.productView(D.getProduct("govee"), D.getProduct("govee").devices.slice(0, 2).map((d) => UI.deviceRow(d, { spark: false })).join(""))}</div></section>`, "width: 412px; padding: 0")}
      </div>
      ${sect("TopFade", ".top-fade · --fade-h, --fade-solid; solid gradient to --bg, no blur")}
      <div class="g-row">${cell("over a list", `<div style="position: relative; height: 150px; overflow: hidden">${devs.slice(0, 3).map((d) => UI.deviceRow(d)).join("")}<div class="top-fade" style="height: 90px"></div></div>`, "width: 412px; padding: 0")}</div>
    </div>`;
    miniMap($("#g-rec-map"), D.getRecording("now").route);
    { // the map style on a past session
      const r = D.getRecording("r5"), m = new maplibregl.Map({ container: $("#g-style-map"), style: MAP_STYLE(), attributionControl: false, bounds: boundsOf(r.route), fitBoundsOptions: { padding: 30 } });
      m.on("load", () => { addStyle(m); m.getSource("route").setData(lineGeo([r.route])); m.getSource("ends").setData(MS.endsOf(r.route));
        const pts = r.points.map((p) => ({ addr: p.addr, at: p.at })); m.getSource("devices").setData(fc(pts)); m.getSource("density").setData(fc(pts)); });
    }
    $("#g-q").focus();
  }

  window.CluetoothUI = { UI, icon, band, minus };
  addEventListener("hashchange", () => location.reload());
  if (location.hash === "#gallery") renderGallery();
  else if (location.hash === "#onboarding") renderOnboarding();
  else boot();
})();
