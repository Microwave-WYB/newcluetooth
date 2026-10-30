# Cluetooth UI v2: specification

This is the reference for the Compose rewrite. The HTML prototype in this directory is the design source, and this
file states every behavior and style in it as a numbered rule. When the two disagree, the prototype wins; fix this
file. Verify the Android build rule by rule against the prototype at the same size, light and dark.

- **Prototype:** `index.html`, `tokens.css`, `components.css`, `app.js`, `data.js`, `map-style.js`. The gallery is at
  `#gallery` and onboarding at `#onboarding`.
- **Demo states:** `?state=1|2|3|4|first`, `?layout=phone|tablet`, `?loc=denied|off|waiting|stale`, `?side=1`,
  `?q=<query>` (tablet).
- **Frozen at:** branch `design/ui-v2-prototype`, commit `ec1934d`.
- **Repository rules** in `AGENTS.md` ("Android UI Principles") also apply. Where they conflict, this file is the
  newer decision for v2.

Units: 1 CSS px in the prototype is 1 dp on Android. "Phone" means the compact layout and "tablet" means medium or
expanded (G-2).

---

## G. Global

- **G-1 Tokens.** Every color, size, radius, shadow and timing comes from `tokens.css`. Generate the Compose theme
  from it; do not copy values by hand. Components never hard-code values.
- **G-2 Layout mode.** One `layoutMode`, computed from window width (Material window size classes):
  - compact: below 600 dp;
  - medium: 600 to 840 dp;
  - expanded: 840 dp and above.

  Compact is the phone layout; medium and expanded are the tablet layout (section T). Crossing a breakpoint
  re-lays out the screen and keeps the current state (T-9).
- **G-3 Type.** Inter. Body 15/24, titles 17 at weight 650 with -0.01em tracking, the product heading 24/30, sub text
  13/20 and small text 12.5/18. Numbers (RSSI, times, counts, MACs) use tabular figures. No monospace in the UI,
  except the raw advertisement data column (D-13).
- **G-4 Color.** Solid colors only: no blur, no translucency effects. Light and dark sets are as in `tokens.css`. RSSI
  bands:
  - strong: −65 dBm and above, `--rssi-strong`;
  - fair: −80 to −65, `--rssi-fair`;
  - weak: below −80, `--rssi-weak`.
- **G-5 Shadows.** Only these:
  - `--shadow-float`: float buttons, the FAB and capsule, the "Search this area" chip, the search bar, map markers;
  - `--shadow-raised`: the recorder panel, the location popover, the tablet panel;
  - `--shadow-sheet`: bottom sheets;
  - `--shadow-thumb`: the segmented control's selected thumb.

  Everything inside a page or sheet has no shadow and no border, and is separated by background (`--fill2` on
  `--panel`/`--bg`).
- **G-6 Clipping.** Shadows, outlines and focus rings are never clipped by a scrolling or clipping parent. Focus rings
  are inset (2 dp `--focus-color`, offset −2 dp).
- **G-7 Motion.** Easing is `--ease` (cubic-bezier(0.2, 0, 0, 1)) everywhere. Durations:
  - `--t-fast` 180 ms: color and opacity;
  - `--t-std` 240 ms: sidebar and segmented controls;
  - `--t-page` 320 ms: sheets and pages;
  - `--t-morph` 360 ms: recorder and button morphs;
  - `--t-fade` 200 ms: the search page;
  - `--t-blink` 1 s: the recording dot, a step blink;
  - `--t-spin` 0.8 s: the GATT spinner.

  Reduced motion makes every transition and animation instant.
- **G-8 Vocabulary.**
  - Use "scan" and never "heard" ("Scanned now", "Last scanned 2 h ago").
  - A device without an advertised name shows "No name" in `--t3`. A device row never falls back to the product or
    wiki title.
  - The address type is never shown.
  - Text is English only, with no check or cross symbols.
- **G-9 No self-reordering.** A list the user can tap never reorders by itself:
  - It is sorted once, by RSSI descending, when a session view opens.
  - Live values update in place and new rows are appended. Filters (category, area, search, Apple) only hide rows.
  - Only an explicit user action builds a new order (S-6, M-9, Q-7).
  - Keep each list's order as explicit state (a list of keys), not as a sort applied on every update.
- **G-10 Restore on return.** Going back to a list after opening an item restores its scroll position, view, filters
  and sheet height exactly.
- **G-11 No duplicate information.** The same information is never shown twice on one screen. For example, the product
  sheet header shows the category, not the product name that the body already shows.
- **G-12 Display filter.** "Hide unnamed Apple devices" defaults to on. It hides every device whose company ID is
  0x004C (76) and that has no local name, regardless of rule matches, from every list, count and map layer. It
  never affects scanning, recording, storage or upload.
- **G-13a Inner corners.** An element inside a rounded container's corner region has the same inset on its top and
  sides, and its radius is the container radius minus that inset. Examples:
  - tablet panel rows: 30 − 8 = 22;
  - the recorder mini map: panel radius − 12 (phone 22 − 12 = 10, tablet 28 − 12 = 16);
  - the location popover: its radius is 28, concentric with its pill button (16 + 12).

  The recorder panel radius is concentric with its Pause/Stop buttons: 22 on phone (18 + 4) and 28 on tablet
  (20 + 8).

  The tokens are `--panel-pad` (8) and `--pop-pad` (12).
- **G-13 Slop.** `--drag-slop` is 8 dp. A pointer that moves less than that is a tap; past it, a vertical move is a drag.
- **G-14 Back order.** System Back closes the topmost layer first, in this order:
  1. the location popover;
  2. a page (the search page, Settings);
  3. the top pushed sheet;
  4. the sidebar;
  5. the recorder panel;
  6. search state.

  With none of these open, Back leaves the app.

## C. Components

Each component is built once. Its size, color and state rules come from these tokens.

- **C-1 FloatButton.**
  - 44 dp circle (`--rb`), `--fill1` with a `--t1` icon (20 dp glyph, stroke 1.8, round caps and joins) and
    `--shadow-float`.
  - The "on" state is `--fill2`.
  - Phone positions: top-left and top-right at `--edge` (12 dp).
- **C-2 IconButton.**
  - 40 dp circle (`--icon-btn`) with a 16 dp glyph.
  - Neutral: `--fill2` with `--t1`. Rec: `--rec-red` with a white glyph.
  - The touch target is at least 44 dp (transparent hit padding).
- **C-3 Chip and ChipRow.**
  - Chip: 34 dp tall pill with 14 dp horizontal padding and 14 sp text. Unselected: `--chip-bg` (`--fill2`) with
    `--chip-fg` (`--t2`). Selected: `--chip-on-bg` (`--t1`) with `--chip-on-fg` (`--bg`) at weight 500.
  - ChipRow: horizontally scrollable with no scrollbar, a 6 dp gap and `--edge` side padding. The Discover sheet
    has no chip row; filtering lives in the Filter sheet (D-3a).
- **C-4 Segmented.**
  - A `--fill2` pill track with 2 dp padding. The selected thumb is `--seg` with `--t1`, weight 500 and
    `--shadow-thumb`. Unselected labels are `--t2`. Text is 14 sp.
  - In a sheet header it is 40 dp tall (inner buttons 36 dp) and 184 dp wide.
  - Advertising/GATT in the device sheet spans the full width.
  - Switching: the thumb slides to the new option (`--t-std`). The content below moves 28 dp (`--swap-x`) in the
    direction of the change while fading (shared-axis X, `--t-std`); the sheet height doesn't change.
- **C-5 DeviceRow.**
  - A grid of three columns at least 64 dp tall, with 8 dp × `--gutter` (16 dp) padding and a 12 dp item radius.
  - Left: the name, one line ellipsized, or "No name" in `--t3`. Below it the MAC in 13/20 `--t2` tabular.
  - Middle: the sparkline, 56 × 22 dp. Last column: the RSSI number, 34 dp wide, right-aligned, weight 500, with
    "−" as the minus sign.
  - The sparkline and the number take the band color (G-4).
  - Stale rows use opacity `--dim` (0.42). Pressed and selected rows are `--fill2`.
  - Rows from history and All sessions have no sparkline, and their RSSI is the strongest seen.
- **C-6 Sparkline.** The last minute as 30 samples. y covers −30 to −100 (clamped), with a 1.5 dp stroke in the band
  color. Gaps (missing samples) break the line.
- **C-7 ProductCard.**
  - 148 dp wide, 12 dp padding with an 8 dp gap and `--fill2`, with no shadow. The radius `--card-r` is concentric
    with the sheet: container radius − the card's inset from the sheet edge (phone 34 − 16 = 18; tablet
    30 − 24 = 6).
  - A 36 dp `--bg` circle holds the category icon; `--t3` for Unidentified.
  - The name is weight 600, 20 dp line height, clamped to 2 lines (minimum height 40).
  - The status line is 13/20 `--t2`, one line, chosen as follows:
    - history: "N device(s)";
    - live and in range: "In range · N device(s)";
    - live and not in range: "Last scanned {last}".
- **C-8 CategorySection.** A header (17/650 label plus a "›" in `--t3`), then a horizontally scrolling row of cards
  with a 10 dp gap.
- **C-9 SearchField (wiki `.sf`).**
  - 42 dp pill on `--fill2` with 16 dp padding, a search icon and a 10 dp gap. Text is 14 sp, the placeholder `--t3`.
  - Focused: the `--field` background and the inset focus ring (G-6).
- **C-10 Button.**
  - 40 dp pill with 18 dp padding and weight 500. Primary: `--blue` with white text. Tonal: `--fill2` with `--t1`.
  - Small: 32 dp tall with 14 dp padding and 14 sp text.
- **C-11 Switch.**
  - 46 × 28 dp pill with a 22 dp knob. Off: `--fill2` with a 1 dp inset `--div` ring and a `--fill1` knob.
  - On: `--blue`, with the knob moving right over `--t-std`.
- **C-12 ListItem.** At least 56 dp tall, 8 × 16 dp padding and a 12 dp radius. Title, optional sub line (13/20 `--t2`)
  and an optional trailing value or chevron. Pressed and selected rows are `--fill2`.
- **C-13 Section label.** 12.5/18 `--t3`, padding 16 16 4.
- **C-14 BlinkingDot.** 8 dp `--rec` circle, blinking at 1 s with a step (hard on/off). Paused, it is a static `--t3`
  dot.
- **C-15 Handle.** A 36 × 4 dp `--div` pill, centered in a dedicated 14 dp handle zone (`--handle-zone`) at the top of
  every phone sheet. The header row starts below that zone. It is hidden on tablet, which has no handle zone.
- **C-16 SheetFade.** While a sheet's content is scrolled away from the top, a 24 dp gradient from the sheet
  background to transparent shows just under the header. It fades out over `--t-fast` at scroll 0 and takes no
  touches.
- **C-17 Icons.** One stroke icon set (24 grid, 1.8 stroke). The glyphs are in `app.js` `ICONS`. Category icons:
  - headphones: Audio;
  - bulb: Lighting;
  - thermometer: Sensors;
  - tag: Trackers;
  - watch: Wearables;
  - car: Vehicles;
  - medical: Medical;
  - circled question mark: Unidentified.

## P. Phone main screen

- **P-1 Map background.** The map fills the screen behind everything. There is no top app bar and no bottom
  navigation.
- **P-2 Top row.** ☰ is at the top-left (C-1) and search at the top-right (C-1). There is no other permanent chrome.
- **P-3 Record FAB.**
  - 56 dp `--rec` circle with an 18 dp white dot and `--shadow-float`, at the bottom-right.
  - Its bottom is `--edge` + 12 dp above the Discover sheet's collapsed peek, or above the screen bottom when there
    is no sheet.
  - It is in a layer above the map and below every sheet: a rising sheet covers it, and it is never hidden or faded.
- **P-4 Locate button.**
  - 44 dp FloatButton with the crosshair icon at the bottom-left, its center level with the FAB's center, in the same
    layer as the FAB.
  - Hidden by default. It fades in only after the user's own pan or zoom has taken their location more than 40 dp
    from the center of the visible map area (the area the sheets leave free), or after any user zoom.
  - Camera moves made by the app never show it.
  - Tapping it eases to the location at zoom max(current, 17) over 600 ms, then the button hides again.
  - It never shows without a location.
- **P-5 Location warning.**
  - While the location is unusable, the locate button's spot shows a 44 dp FloatButton with a crossed-out location
    icon in `--warn`. It is always visible, overriding P-4, and sheets still cover it.
  - Tapping it opens a popover anchored above it, at most 260 dp wide, with `--panel`, a 28 dp radius, 12 dp padding and
    `--shadow-raised`. It contains:
    - "Location permission is off", with a small primary "Allow" button;
    - "Location is turned off", with a small primary "Turn on" button;
    - "Waiting for location…", text only: shown once there has been no first fix for more than 30 s;
    - "Location is N min old", text only: shown once the last fix is older than 2 min.
  - A tap outside closes the popover. When the location becomes usable, the warning disappears.
- **P-6 Map pan collapses.** When the user starts panning or zooming the map (drag, pinch, wheel, double-tap), the top
  sheet goes to Collapsed if it isn't already. App camera moves don't do this.

## R. Recorder

- **R-1 One control.** The FAB, the capsule and the panel are one element that morphs. Recording can start only from
  the FAB.
- **R-2 Start.**
  - Tapping the FAB starts recording. On phone the element moves (morphing, `--t-morph`) to the top center between
    ☰ and search and becomes the island capsule.
  - The UI switches to the new current session (V-2).
- **R-3 Island (phone).**
  - 44 dp tall (`--island-h`), its vertical center on the ☰/search row. Its width is measured from its content
    (about 165 dp).
  - `--panel` background with `--shadow-float`.
  - The row: 14 dp left inset, the BlinkingDot, the elapsed time (weight 500, tabular), Pause/Resume (neutral
    IconButton, 36 dp) and Stop (rec IconButton, 36 dp). Both buttons have 44 dp touch targets.
  - It stays at the top center whatever the sheets do.
- **R-4 Panel.** Tapping the dot or time expands the panel.
  - The row keeps its height and vertical position, so the panel grows downward on phone and upward on tablet.
  - Inside the row, the dot and time move to the panel's horizontal center, while Pause and Stop slide right
    together until Stop is concentric with the panel's corner: top-right on phone (4 dp from top and right), and
    bottom-right on tablet (8 dp). The Pause–Stop gap is 6 dp on phone and 8 dp on tablet. All of it animates with
    the panel's width (`--t-morph`), and collapsing returns exactly to the capsule layout.
  - On phone the panel widens symmetrically to the full width minus 2 × `--edge`, with a 22 dp radius and
    `--shadow-raised`. Its extra content grows downward below the row:
    - "N devices · lat, lon ±N m" (13/20 `--t2`, one line).
  - Its height is measured from its content.
  - While it is open, ☰ and search fade out because the panel covers them.
  - A tap outside, or Back, collapses it to the capsule.
- **R-5 (removed) Mini map.** The panel has no mini map. Only one map renderer exists at a time; a second one (a
  snapshotter) was the suspected cause of native MapLibre crashes on device.
- **R-6 Pause/Resume.**
  - Pause swaps the icon to play ("Resume"), makes the dot static grey and pauses recording.
  - Resume reverses it.
- **R-7 Stop.**
  - Stop morphs the control back to the FAB at the bottom-right. There is no snackbar.
  - The UI stays on the session that just ended, now frozen (V-3).
- **R-8 Deliberate asymmetry.** Starting sits at the thumb (bottom-right) and stopping needs a reach to the top (phone).
  This is intended; do not add a stop control near the bottom.
- **R-9 Search state (phone).** The island sits centered directly below the search bar (top = `--edge` + 44 + 8 dp)
  and returns when search state ends.
- **R-10 Storage full.**
  - **Stop writing.** The core checks the data volume's free space on every commit tick. It stops writing when free
    space falls below 200 MB, or when a write fails for lack of space, and reports a typed `StorageFull`. Nothing is
    buffered without bound: scans that can't be stored are dropped and counted.
  - **Pause.** The recording pauses (R-6: grey dot, Resume). The session stays open, and nothing already written is
    lost.
  - **Dialog.**
    - Title "Storage is full". Body "Recording is paused because the phone is almost out of space. Free up some space
      to continue." It shows the current free space.
    - Two switches, both off by default. Each shows the space it frees:
      - "Delete uploaded raw files": raw files that have already uploaded. Files not yet uploaded are never touched.
      - "Clear local database": the stored history of every session except the current one. Raw files not yet
        uploaded are kept, so they still upload.
    - "Free up space" (primary) is enabled when at least one switch is on. "Not now" (text) closes the dialog.
    - After "Free up space", the dialog shows how much was freed.
    - If free space is back above the floor, "Resume" continues the recording. Otherwise the recording stays paused
      and the dialog stays open.
  - **Settings.** The same two actions are also in Settings › Storage (O-1 item 11).

## S. Sheets

- **S-1 One component.** Every bottom sheet is the same component: a `--panel` surface with `--shadow-sheet` and top
  corners of radius `--sheet-radius`.
  - On phone a sheet always reaches the very bottom edge of the screen, full width, with square bottom corners, so it
    covers the bottom corners on any screen shape.
  - Its content gets bottom padding of the navigation-bar inset, and the background runs under the gesture bar. The
    peek height excludes that inset.
- **S-2 Corner buttons (phone).**
  - `--sheet-radius` = `--edge` + `--rb`/2 = 34 dp.
  - The corner buttons (the view switch at top-left, the X at top-right) are 40 dp. Their center x is 34 dp from the
    sheet's side, equal to the center x of ☰ and of search above them.
  - Vertically they sit below the handle zone: center depth = `--handle-zone` + 34 = 48 dp. So they are horizontally
    aligned with the corner arc but deliberately not vertically concentric with it (owner decision: room for the
    handle).
- **S-3 Header row.** One height, `--sheet-header-h` = 40 dp, on one center line at depth 48 dp on phone (S-2) and 30 dp
  on tablet (T-3). The view
  switch, the X, the segmented control, and every header title ("N results", the product sheet's category) use it.
- **S-4 Stack.**
  - The sheets form a stack. Level 1 is the Discover sheet, the root, which never closes.
  - A product opens a product sheet on top. A device opens a device sheet on top.
  - Depth is unlimited: Discover → product → device is level 3, and a device opened from the list or the map is
    level 2.
  - A device opened while a device sheet is on top replaces it, at the same level and height.
- **S-5 Snap states.**
  - Collapsed is the peek: the handle zone plus the header row, about 78 dp, measured as the header's bottom.
  - Half is 67% of the screen height.
  - Full stops just below the top row (the screen height minus `--top-zone` = `--edge` + 44 + `--edge`), so ☰ and
    search stay visible and tappable.
  - Pushed sheets also have Dismissed, below Collapsed. Reaching it pops the sheet.
- **S-6 Opening a pushed sheet.** It slides up and opens at Half, or at Full when the sheet below was at Full.
- **S-7 Only the top sheet is shown.** This applies on phone and tablet alike.
  - Once a pushed sheet has finished sliding in, every sheet below it is hidden (not drawn, not laid out at a
    coupled height). Its height state, scroll position, view and filters are saved.
  - While the top sheet is dragged, nothing below it moves, and the map shows behind the top sheet.
  - Hidden sheets never track the top sheet's height.
- **S-8 Pop.**
  - X, system Back, a drag down from Collapsed, a tap on empty map, or a tap on the scrim each pop exactly one level.
  - Pop by X, Back, the scrim or an empty-map tap, while the top sheet rests at some offset:
    1. Show the sheet below instantly at the top sheet's current offset, underneath it, so nothing visibly changes.
    2. Slide the top sheet out.
    3. Animate the revealed sheet from that offset to its saved height state (`--t-page`, `--ease`).
  - Pop by dragging down from Collapsed: on release, show the sheet below at its saved height state while the top
    sheet slides out of the screen.
  - The revealed sheet gets back its saved scroll, view and filters, and the map returns to its saved camera.
  - The result looks the same as the sheet below having stayed covered all along.
- **S-9 Scrim.**
  - `--scrim` (black at 32% light, 50% dark), solid, directly under the top sheet.
  - It shows only while a pushed sheet is at Full. Tapping it pops one level.
  - At Half and Collapsed there is no scrim, so the map stays usable.
- **S-10 Header drag.** The whole header drags the sheet: the handle, the header row and its controls. Within the slop
  (G-13) it is a tap and the control works. Past the slop, vertically, it is a drag: the control under the finger
  doesn't fire and its press is cancelled. A mostly horizontal move is ignored.
- **S-10a 1:1 tracking.** From the moment a drag starts until release, the sheet's top edge moves exactly as far as the
  finger, with no lag, no easing and no transition while the finger is down. The slop distance (G-13) is included,
  so there is no jump when the drag begins. When the content scrolls, as in S-13, the sum of the sheet movement and
  the content scroll equals the finger movement. Note that the web prototype does not track exactly 1:1; follow this
  rule, not the prototype, here.
  - Why the prototype lags: its `couple()` clears the dragged sheet's "dragging" flag on every move, which re-enables
    the sheet's transform transition. The sheet then chases the finger at 0.33 to 0.8 of its movement.
  - The Android rule: while a pointer is down, the dragged sheet's offset is set directly from the finger. Only the top
    sheet exists on screen (S-7), so nothing else needs updating. Snap animation runs only after release.
- **S-11 Tap on the handle or header background.** From Collapsed it goes up one state; from any other state it goes to
  Collapsed.
- **S-12 Header controls raise the root.** Tapping any control in the Discover sheet's header while it is Collapsed
  (the view switch, the segmented control) does its action AND raises the sheet to Half.
- **S-13 Content gestures (Google Maps style).** The first move past the slop decides:
  - Not at Full: the gesture moves the sheet.
  - At Full with the content at scroll 0 and the finger moving down: it lowers the sheet.
  - Otherwise: native scroll, with momentum.
  - If an upward drag of the sheet reaches Full, the rest of the same gesture scrolls the content.
- **S-14 Release.**
  - Velocity is measured over the last 5 samples.
  - A flick (above 0.4 dp/ms) moves exactly ONE state from where the drag started, in the flick's direction. It
    never skips a state.
  - Otherwise the sheet snaps to the nearest state.
- **S-15 Wheel or trackpad.**
  - Scrolling down while the sheet is below Full raises it to Full.
  - Scrolling up at content scroll 0 lowers it one state.
  - A 400 ms lock applies between steps.
- **S-16 Fixed height.** Switching tabs or views, GATT progress, or any content change never changes a sheet's
  height. The content scrolls inside.
- **S-17 Map follows the top sheet.**
  - Discover: the session's points (filtered as the sheet is).
  - Product: only that product's points, framed on open.
  - Device: that device selected (M-6).
  - Popping restores the previous map state.

## D. Sheet contents

### Discover (root)

- **D-1 Header.** A three-column grid (1fr auto 1fr):
  - The view switch on the left: an IconButton showing the icon of the view it switches TO. The list icon reads
    "Show the device list"; the grid icon reads "Show categories".
  - The centered segmented control:
    - [Nearby | All] while recording (default Nearby);
    - [This area | All] for history and All sessions (default All, reset to All on every session change).
  - The filter button in the right column (D-3a). It is the same round IconButton as the view switch, with a
    funnel icon, mirroring it at the corner, with its center under the search button's center. While any filter
    is active it takes the selected style and shows a count badge. It is shown in both views.
- **D-1a View switch transition.** Toggling categories ⇄ list crossfades the content and the chip row (`--t-fast`,
  no slide); the sheet height doesn't change.
- **D-2 Categories view (default).** CategorySections built from the session:
  - Each identified product appears once, under its own category.
  - "Unidentified devices" comes last as a single card.
  - Nearby keeps only products with a non-stale device. All keeps every product the session saw.
- **D-3 List view.**
  - DeviceRows sit directly under the header, in the session's fixed order (G-9). There is no chip row.
  - Nearby hides stale rows. The filters come from the Filter sheet (D-3a).
- **D-3a Filter sheet.** A pushed sheet titled "Filter", with the X and the usual exit logic.
  - From top to bottom:
    1. switches, AND-combined:
       - Readable: the matched wiki page has a `decoder.js`, so the advertisement is decodable without connecting.
         Decoding itself is deferred to issue #39.
       - Trackers: the `asset-tracker` category.
       - Vulnerable: the matched page links a vulnerability page.
    2. "Identification" [Any | Identified | Unidentified], default Any;
    3. "Categories": a checkbox list of every category present in the scope (data-driven from the wiki; a device
       matches any checked category);
    4. a "Clear" text button.
  - Changes apply live.
  - Category counts are faceted: each count is the devices that would show after checking that category. They
    apply every other active constraint (the switches, Identification, This area, Nearby, the Apple filter) but not the
    category facet itself, so a count always equals the resulting list length.
  - The row order is fixed when the sheet opens. Counts update in place. A row at 0 stays in place, dimmed, and
    can still be unchecked.
  - All filters reset when the session view changes. Search ignores the filters, which come back unchanged when
    search state ends.
- **D-4 This area.**
  - With This area selected, the content (list rows, category cards and their counts, product device lists) includes
    only devices whose point lies in the current map viewport.
  - It re-filters once each time the map comes to rest after the user's own pan or zoom; never during the move, and
    never after app camera moves.
  - Rows still in view keep their order, rows that leave hide, and new ones are appended.
- **D-5 Empty.** "No products match" or "No matching devices", 14 sp `--t2`, centered.

### Product sheet

- **D-6 Header.** The category label as the title (S-3) and the X (S-2).
- **D-7 Content, top to bottom.**
  1. A hero area: 200 dp tall, `--fill2`, `--edge` side margins, radius `--hero-r` (sheet radius − its inset:
     phone 34 − 12 = 22, tablet 30 − 20 = 10), holding the large category icon (88 dp,
     stroke 1.2, `--t2`). It is structured so a real product image can replace the icon later.
  2. The full product name, 24/30 at weight 650, wrapping and never truncated.
  3. The description in `--t2`.
  4. "Seen at N places · last X" in 13/20 `--t2`.
  5. For an identified product, "Open in Wiki ↗" in `--blue`. It opens `https://wirelesswiki.org/p/<id>` in the
     external browser.
  6. A "Devices" section with the product's DeviceRows. They are sorted once by RSSI, have sparklines only while live,
     and are filtered by This area. Tapping a row pushes a device sheet.
- **D-8 Map.** Only the product's devices, each at its strongest point, framed when the sheet opens.

### Device sheet

- **D-9 Header.**
  - The name (17/650, wrapping), or "No name" in `--t3`, and the X.
  - For an identified device, the product name in 13 sp `--blue` with ↗. It opens the wiki page in the external
    browser. The device's own name is not a link.
  - The MAC (13/20 `--t2` tabular), followed by "−56 dBm" in the band color.
- **D-10 Chart.** Shown only while live.
  - The last 1 minute, 96 dp tall, y from −100 to −30.
  - Dashed `--div` ticks with labels at −65 and −80 only, and "−1 min" and "now" under the axis.
  - The line is a 1.6 dp stroke in `--t2`.
  - The end marker (4.5 dp radius) sits at the last sample's x and the current reading's y, in the band color, the
    same value and color as the dBm number.
  - There are no window selector and no band labels.
- **D-11 Tabs.** A full-width segmented control [Advertising | GATT]. Switching keeps the sheet height.
- **D-12 Advertising.**
  - A "Details" section: a key/value list (14/22, keys `--t2`, values weight 500, right-aligned) with Connectable
    (Yes/No), Format (Legacy/Extended), Company (if known), Service (if any) and TX power.
  - A "Raw data" section: a table with Len, Type and Data columns, one row per AD structure. The Type is shown as
    `0xNN`. Data uses the code font in `--t2` and breaks anywhere.
  - Table styling: a 1 dp `--div` border and 12 dp radius, a `--fill2` header row, and columns of 44 / 56 dp / the
    rest.
  - Advertising PDU names (ADV_IND …), PHY and SID are not shown.
- **D-13 GATT.**
  1. The note "Only connect to devices you own or have permission to test." is always shown above the button area
     whenever a button is present.
  2. Idle: one full-width primary "Discover GATT".
  3. Tapping it turns that row, in place and with nothing else moving, into Cancel (tonal, growing from zero width) |
     "I am authorized" (primary), with an animated 8 dp gap. The prompt appears every time; it is never remembered.
     Cancel returns to idle.
  4. "I am authorized" starts the discovery. Steps appear one by one below as rows at least 36 dp tall, each with a
     12 dp mark, a label and a detail on the right:
     - Connecting… (0.8 s);
     - Discovering services… (N services);
     - Reading characteristics (m/n while running, then n/n);
     - Disconnected.

     The running step shows a spinner (2 dp `--div` ring with a `--blue` top, 0.8 s). Done steps get a
     `--rssi-strong` dot.
  5. Failure: the list stops at the failed row, which gets a `--rssi-weak` dot and its reason in `--rssi-weak`
     ("Timed out", "Disconnected unexpectedly"). Below it, a tonal "Try again" goes straight back to the step-3
     prompt.
  6. Success: the services, then their characteristics (name, UUID · properties, value), appear below the steps.
  7. A device that was already read opens showing its last result.
  8. Leaving or popping the sheet cancels a running discovery.
- **D-14 Map.** Opening a device selects it (M-6). From a list it also eases the camera to the device, at zoom ≥ 18.2,
  keeping it clear of the sheet. From a map tap, the camera stays put.

## M. Map

- **M-1 Base map.** OpenFreeMap tiles: "liberty" in light mode and "dark" in dark mode. Attribution is compact, at the
  bottom-left.
- **M-2 Layers.** All overlays are MapLibre style layers from `map-style.js`, loaded unchanged by MapLibre Android.
  There are no view or DOM markers. Colors and sizes come from tokens. Sources:
  - `route`;
  - `ends`;
  - `density`;
  - `devices`: clustered, radius 50, max zoom 18;
  - `sightings`;
  - `me`.
- **M-3 Points.**
  - One point per device, at its strongest sighting.
  - A `--blue` circle, radius 5, with a 1.5 dp `--on-blue` stroke. Unidentified devices look the same.
  - There are no category icons on the map.
- **M-4 Clusters.**
  - A `--blue` circle with a 2 dp white stroke and the white count (`point_count_abbreviated`, 12 sp).
  - Radius steps by count: 13, then 16 from 10, 20 from 50, and 24 from 200.
  - Tapping a cluster eases to its expansion zoom + 0.2.
- **M-5 Density.** A wash of `--map-density` circles, radius 16, opacity 0.035. It is always on.
- **M-6 Selection.**
  - The selected device's sightings show as radius-6 circles colored by RSSI band, with a white stroke.
  - Its own point gets an 8 dp `--t1` marker.
  - Every other point, cluster and count fades to `--dim`.
  - Clearing the selection restores everything.
- **M-7 Taps.** The hit box is 8 dp around the finger, against clusters and points.
  - A point opens its device (S-4).
  - A cluster zooms in (M-4).
  - Empty map pops the top pushed sheet if there is one, and otherwise clears the selection.
- **M-8 Route.**
  - The session route is a 4 dp round-capped `--map-route` line at opacity 0.35. All sessions draws every route at
    0.18, with no Start or End.
  - Start and End: a radius-5 dot (`--map-start` / `--map-end`) with a white stroke, plus a 12 sp label with a 2 dp
    halo, always on top. The Start label sits above its dot and the End label below.
  - Start and End are not drawn when the session barely moved: every route point lies within 50 m of the first one.
    The test uses distance from the first point, not path length, so GPS jitter while standing still doesn't count
    as movement. This keeps a stationary recording's Start/End from covering the device cluster at the same spot.
    The mini map (R-5) follows the same rule.
- **M-9 Location.** With no session, the user's location is a radius-8 `--blue` dot with a 3 dp white stroke, at zoom
  16.
- **M-10 Framing.** Showing a session fits its route and points:
  - padding: top 90, bottom = sheet cover + 40, left = panel width + 40 on tablet (40 on phone), right 40;
  - maximum zoom 17;
  - no animation on a session switch.
- **M-11 Moved-away test** (used by Q-6).
  - The view has moved away from a reference view when the zoom differs by 1 or more, or the reference center is
    more than 25% of the viewport away from the current center, on either axis.
  - App camera moves never count.

## Q. Search

- **Q-1 Rule (`data.js` `matchDevice`, mirrored exactly by the Rust core).**
  - Trim the query. An empty query is no search.
  - Name: case-insensitive substring.
  - MAC: only when the query contains a digit. Both sides drop ":" and "-" and are lowercased, then compared as a
    substring.
  - A device matches if either rule matches.
  - Test vectors are in the gallery: "qt" matches "QT-Sensor", "SmartQt" and "aqtb", but not "Other"; "4b4b",
    "4B:4B" and "4b-4b" match 72:CD:4C:4B:4B:6F; "ab" does not match the MAC AB:CD:… but does match the name
    "Abbey Road"; a blank query matches nothing.
- **Q-2 Scope.** `searchDevices(query, scope, bbox?)` searches the shown session view (the current recording, a
  history session or "all"). The bbox limits it to devices whose point is inside it. Results keep the session's
  fixed order.
- **Q-3 Phone search page.**
  - Tapping search makes the button expand in place to the full-width field (full width minus 2 × `--edge`, on
    `--fill2`), its search icon morphing into a back arrow.
  - At the same time a full-screen `--bg` page fades in (`--t-fade`, with no slide) and the keyboard opens.
  - Results are DeviceRows below the field.
  - Empty query: "Search devices by name or MAC address".
  - The scope is the map viewport at the moment the page opened.
  - Back, or the arrow, reverses the transition.
- **Q-4 No results.** "No results in this area", followed by a `--blue` text button "Search everywhere" that re-runs the
  query with no bbox. Without an area, the text is "No devices match".
- **Q-5 Picking a result.**
  - The page fades out, the field shrinks back, and the map enters search state.
  - The search bar is a full-width `--fill1` pill (44 dp, `--shadow-float`) with a search icon, the query text
    (ellipsized) and an X IconButton. It replaces ☰ and search.
  - The Discover sheet shows only the matches: list view only, with no view switch, no chips and no segmented
    control. Its header reads "N result(s) in this area", or "N result(s)" without an area.
  - The map shows only the matched points.
  - The picked device's sheet opens at Half, with the camera easing to it.
- **Q-6 "Search this area".**
  - In search state, once the user moves the map away from the searched area (M-11), a chip appears centered below
    the search bar, with the float shadow. While recording it sits below the island instead.
  - Tapping it re-runs the query on the current viewport: matches still present keep their order, new matches are
    appended and others hide. The chip then hides.
- **Q-7 Exit.**
  - The bar's X, or Back once only the root sheet is left, leaves search state.
  - The Discover view, height and scroll from before the search come back.
  - Tapping the bar's text reopens the search page with the query editable. The field opens in place; Back from the
    page returns to search state.

## L. Sidebar and sessions

- **L-1 Phone sidebar.**
  - ☰ opens a full-height panel 82% of the screen wide: `--panel`, flush to the edges, with no radius and no shadow.
  - The whole main screen slides right by the same width (`--t-std`). The visible map strip is dimmed with
    `--dim-strip`, and tapping it closes the sidebar.
  - ☰ travels to the panel's edge (4 dp in) and morphs into a "collapse sidebar" icon.
  - There is no title. The list starts below the button row (`--top-zone`).
- **L-2 Content.**
  1. "Current session" (only while recording), with "Started 7:12 PM · 26 devices".
  2. "All sessions", with "N sessions · M devices".
  3. History grouped by day, each group headed by a C-13 label: "Today · Oct 28", "Yesterday · Oct 27", then
     "Oct 25". Each session row shows its start time as the title and "23 min · 1.8 km · 214 devices" below it. The
     distance is omitted when it is 0, and durations from 60 min read "1 h 5 min".
  4. Settings, pinned at the bottom as a 40 dp row with an icon.

  Sessions with no data never appear. The selected session row uses `--fill2`.
- **L-3 Choosing a session.** It closes the sidebar and shows that session (V-4, V-5).

## V. Session views and launch

- **V-1 Scanning only while recording.** There is no live mode outside a recording.
- **V-2 Recording (the current session).**
  - Live data: sparklines, Nearby/All, the chart, values updating in place, new devices appended.
  - The current session is pinned at the top of the sidebar and selected.
- **V-3 After Stop.** The UI stays on the session that just ended, now frozen: no live values, no sparklines, no
  chart, [This area | All]. It stays there until the app restarts or a new recording starts.
- **V-4 History session.** Frozen, like V-3. The FAB is available.
- **V-5 All sessions.** The union of all local data, frozen: every device at its strongest point, every route faint
  (M-8). It behaves like a history session.
- **V-6 Launch.**
  - If "Record when the app opens" is on, recording starts (V-2).
  - Otherwise, All sessions opens if any session exists.
  - Otherwise this is a first run: the map shows only the location, with no Discover sheet, no search button and an
    empty sidebar.
- **V-7 Switching session view.** It resets the stack to the root, This area to All, and the area and search state;
  sorts the list once (G-9); and refits the map.

## O. Pages

- **O-1 Settings.**
  - Opened from the sidebar. The page slides in from the right (`--t-page`) and has a `--bg` top strip
    (`--top-zone` tall) holding a back FloatButton and "Settings" (17/650).
  - Rows, in order:
    1. User ID (value on the right);
    2. switch "Record when the app opens", with "Starts a recording each time Cluetooth opens";
    3. switch "Upload over Wi-Fi only";
    4. switch "Scan LE Coded PHY", with "Also scan long-range devices; applies within a minute";
    5. "Pending uploads", with the count and size, plus a small tonal "Upload now";
    6. a "Filters" section label;
    7. switch "Hide unnamed Apple devices" (default on), with "Only what you see; scanning and recording keep
       everything";
    8. a "Storage" section label;
    9. "Storage used", with the total and a breakdown (database / raw files / map cache);
    10. "Export data": choose the scope (one session or All sessions), then the format, then hand off to the system
        share sheet or save to Downloads. The formats are:
        - Raw (Parquet): plaintext payload v3 plus the gatt/v1 JSON;
        - Table (CSV): converted row for row from the Parquet, with trailing `session_id` and `source_file` columns;
        - Database (SQLite): a snapshot of the aggregated, research-aligned tables;
    11. "Delete local data": choose the scope, then a confirmation. Un-uploaded data is kept until it has uploaded,
        unless the user explicitly confirms losing it in a second step. It also offers the two storage-full actions
        (R-10): "Delete uploaded raw files" and "Clear local database", each showing the space it frees;
    12. "App version".
- **O-2 Onboarding.** On first launch, four full-screen steps. Each has "N of 4" (12.5 `--t3`), a 24/30 title, a body,
  and Back (tonal, from step 2) on the left with Next/Done (primary) on the right.
  1. Permissions: Nearby devices, Precise location and Notifications (optional), each with a one-line reason and a
     small tonal "Allow" that becomes "Allowed". A permanently denied permission offers "Open settings".
  2. Bluetooth: the system enable request when Bluetooth is off.
  3. User ID: a field with the hint "Lowercase letters and numbers only, max 9 characters."
  4. "Record automatically?": an explanation, then the switch "Start recording automatically when the app opens",
     ON by default, with "You can turn this off in Settings."

  Done opens the first run, recording straight away if the switch was left on. Later, a revoked permission or
  Bluetooth turned off shows only the missing piece, not the whole flow.

## T. Tablet (medium and expanded)

- **T-1 Floating panel.**
  - The sheet stack lives in one floating panel on the left: 400 dp wide (`--panel-w`), `--edge` from the left and
    bottom, its top at `--top-zone`.
  - Radius `--panel-radius` = 8 + 44/2 = 30 dp, with `--shadow-raised`.
  - Only the top level is visible. Push, pop and restore follow S-4, S-7 and S-8.
- **T-2 Panel states.**
  - Two states: Open (the full column) and Partial (the top edge stays put and the height shrinks to
    `--panel-partial-h`, 45% of the column, never less than the header plus about 3 rows).
  - The content stays scrollable in both states, and the panel clips its content to its rounded shape. The chip row
    and everything else stay inside it.
  - A user pan or zoom of the map moves Open to Partial; app camera moves don't. Tapping the header toggles Open and
    Partial. Every level behaves the same: Discover, product, device, sessions and search results. Sessions and
    search results have no header to tap.
  - In Partial, the panel's bottom shows a ~48 dp gradient from transparent to `--panel` with no blur, and on it a
    centered, handle-sized (~36 dp wide) shallow V chevron in `--t2`. The chevron has a tap target of about
    120 × 44 dp and expands the panel to Open. Both fade out in Open.
  - No level can ever be header-only. A saved or restored "collapsed" state maps to Partial. A pushed level opens
    at Open, and popping restores the state below (Open or Partial).
  - The bottom sheet's Half, drag snapping and wheel rules do not apply. There is no scrim, so the map stays usable.
  - Height changes animate with `--t-std` / `--ease`.
- **T-3 Concentric corners.** The panel has no handle zone. Its header buttons sit on the panel's corner arcs: center
  depth and inset are 30 dp. Inner rows follow G-13a: 8 dp inset with radius 22.
- **T-4 Top row.**
  - Above the panel: ☰ (44 dp) and, 8 dp to its right, a permanently expanded SearchField spanning the rest of the
    panel's width. Both are at `--edge` from the top.
  - They are separate floating pieces that never merge into the panel.
- **T-5 Search on tablet.**
  - Typing in the field shows results in a panel-shaped page over the stack.
  - Clearing the field, or Esc/Back, removes it and leaves search state.
  - Picking a result enters search state inside the panel; the field keeps the query.
  - There is no full-screen page and no separate search bar.
  - "Search this area" is centered at the top of the map area to the right of the panel.
- **T-6 Sessions on tablet.**
  - Tapping ☰ swaps the panel's content for the sessions list (L-2) and morphs ☰ into an X.
  - X or Back restores the Discover stack exactly.
  - Choosing a session switches the panel back to Discover for that session, and the X turns back into ☰.
  - No drawer, no push and no dim.
- **T-7 Recorder on tablet.**
  - It stays at the bottom-right of the map area. The FAB widens in place into a capsule of FAB height (56 dp) with
    40 dp buttons.
  - The panel grows up and to the left, right-aligned to the row, at most 420 dp wide. The row never moves (R-4).
- **T-8 Locate and warning on tablet.** They sit at the bottom-right, stacked 12 dp above the recorder, and move up
  with the recorder's panel.
- **T-9 Resizing.** Going from phone to tablet opens the panel: a Collapsed or Half sheet becomes Open. Everything
  else is kept.

## B. Data boundary

The UI gets data only through these functions. Their Compose and Rust equivalents are the UI's data interface.

| Function | Returns |
|---|---|
| `getCategoryCounts(scope, opts)` | categories present in a scope with device counts (respecting the display filter) |
| `getRecordings()` | finished sessions, newest first |
| `getCurrentRecording()` | the recording session, or null |
| `getRecording(id)` | one session: route plus one strongest point per device; `"all"` works as an id |
| `getAllSessionsView()` | All sessions summary: session and device counts, routes |
| `getSessionDevices(id)` | device rows for a session: live values when recording, the strongest otherwise; `best` point |
| `getProducts(mode, id)` | category groups of product cards for a session; mode `nearby` or `all` |
| `getProduct(id)` | a product with its devices |
| `getDevice(addr)` | a device: name, product name, wiki URL, company name, structures, TX power, history |
| `getDeviceSightings(addr)` | every point a device was scanned: `{at, rssi}` |
| `getDeviceGatt(addr)` / `discoverGatt(addr)` | the last discovery, or run one: `{ok, steps, services}` |
| `searchDevices(q, scope, bbox?)` / `matchDevice(q, name, addr)` | search (Q-1, Q-2) |
| `getLocationStatus()` | `{state, warn, message, action}` (P-5); thresholds in `LOCATION_WARN` |
| `getMyLocation()` | the user's location with accuracy |
| `startRecording()` / `stopRecording()` | session ids |
| `getSettings()` | settings (O-1) |
