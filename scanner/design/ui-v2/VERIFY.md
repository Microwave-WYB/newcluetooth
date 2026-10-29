# Cluetooth UI v2: verification (§6.8.2)

Rule-by-rule check of the Android app against SPEC.md and the prototype.

- **App:** feat/v2 at 193efbb (#76 merged), debug build with the real core (`-PcluetoothAbis=x86_64`).
- **Device:** the `cluetooth-test` AVD, read-only, with no BLE devices around and the location set with
  `emu geo fix`.
  - Phone: 412 × 915 dp (`wm size 1082x2402`, 420 dpi).
  - Tablet: 1024 × 768 dp (`wm size 2048x1536`, 320 dpi).
  - Both in light and dark (`cmd uimode night`).
- **Prototype:** the hosted copy (`https://cachyos.fin-werner.ts.net:8452/ui-v2/`) answered 502 throughout, so the
  same files were served from this directory (`python3 -m http.server`). They were rendered in headless Chromium
  at the same sizes and schemes, with `?state=`, `?layout=`, `?loc=` and `?side=`.
- **Comparison:** screenshots side by side, plus element boxes read from the prototype's DOM. The app is offset
  down by the status bar (24 dp) and up by the gesture bar, which the prototype doesn't have; positions are
  compared after that inset.

**Verdicts.**
- **pass:** checked on the AVD and matches.
- **fail:** what was seen instead, with the owning module.
- **needs device:** the rule needs advertising devices (rows, cards, products, device sheets, map points, search
  results, sessions with data). Skipped; there is no fake for it.
- **not measured:** the behaviour was observed, but no exact value (duration, slop) could be measured on the
  emulator.

## G. Global

- G-1 Tokens: pass. Colors, sizes and radii match the prototype in all four configurations (the theme is
  generated from tokens.css; `TokensTest`).
- G-2 Layout mode: pass. 590 dp is the phone layout; 610 and 850 dp are the tablet layout.
- G-3 Type: pass. Onboarding and Settings text lines sit within 1 to 3 dp of the prototype's, and sub lines use
  tabular figures as in the prototype.
- G-4 Color: pass for every surface checked, light and dark (solid, no translucency). RSSI bands: needs device.
- G-5 Shadows: pass.
  - Float: ☰, search, the FAB, the capsule and the search field.
  - Raised: the recorder panel, the location popover and the tablet panel.
  - Sheet: on the bottom sheet.
  - Nothing inside pages or sheets has a shadow.
- G-6 Clipping: pass (the tablet field's focus ring is inset and unclipped). The phone search field has no focus
  ring at all; see C-9.
- G-7 Motion: not measured. The morphs (island, panel, sidebar, page, sheet snaps) run with the ease; durations
  weren't timed.
- G-8 Vocabulary: pass for every visible string (no "heard", English, no check or cross symbols). "No name":
  needs device.
- G-9 No self-reordering: needs device.
- G-10 Restore on return: pass for the sheet height after popping the Filter sheet (Full and Collapsed came
  back). Scroll, view and filters: needs device.
- G-11 No duplicate information: needs device (product and device sheets).
- G-12 Display filter: the setting is there and on by default. The filtering itself: needs device.
- G-13a Inner corners: pass.
  - The phone panel is 388 × 220 dp with radius 22; its mini map is 364 dp wide with radius 10.
  - The tablet panel is 420 × 233 dp, with header buttons 30 dp in.
  - The popover is 260 dp wide with radius 28.
- G-13 Slop: not measured. Taps and drags were told apart correctly.
- G-14 Back order: pass for the popover, a page (Settings, the search page), the pushed Filter sheet, the
  sidebar and the recorder panel. With none open, Back leaves the app. Search state: needs device.

## C. Components

- C-1 FloatButton: pass. ☰, search and the Settings back button are 44 dp at `--edge` (below the status bar).
- C-2 IconButton: pass. The view switch, filter and X are 40 dp `--fill2`; Stop is the rec style.
- C-3 Chip and ChipRow: there is no chip row (correct, D-3a). The "Search this area" chip: needs device.
- C-4 Segmented: **fail** (`:designsystem`, lane C). After Stop, "This area" in the [This area | All] header is
  clipped ("This are…" on the phone). Each option has a fixed share of the 184 dp track, and the text is clipped
  instead of taking the space it needs. Lane D also saw this on tablet and on the Note10. The rest matches: thumb,
  track, colors, and the full-width Identification control.
- C-5 DeviceRow: needs device.
- C-6 Sparkline: needs device.
- C-7 ProductCard: needs device.
- C-8 CategorySection: needs device.
- C-9 SearchField: **fail** on phone (`:chrome`, lane C). While the field on the search page is focused, it stays
  `--fill2` with no focus ring, in light and dark; the prototype switches to `--field` with the 2 dp inset
  `--focus-color` ring. Pass on tablet (the ring shows).
- C-10 Button: pass. Primary (Next, Done, Allow, Share), tonal (Back, Delete, Upload now), small, and dimmed
  while disabled.
- C-11 Switch: pass, off and on, light and dark.
- C-12 ListItem: pass in Settings, Onboarding and the sidebar.
- C-13 Section label: pass ("Filters", "Storage", "Identification", "Categories").
- C-14 BlinkingDot: pass. It blinks on and off with a hard step, and is static grey while paused.
- C-15 Handle: pass. 36 × 4 dp on phone, and none on tablet.
- C-16 SheetFade: needs device (no scrolling content without devices).
- C-17 Icons: pass for the chrome glyphs (☰, search, back, collapse, X, list or grid, funnel, crosshair,
  crossed-out location, settings). Category icons: needs device.

## P. Phone main screen

- P-1 Map background: pass. There is no app bar and no bottom navigation.
- P-2 Top row: pass.
- P-3 Record FAB: **fail** without a sheet (`:app`, lane C). On the first run (no Discover sheet), the FAB's
  bottom is 24 dp above the screen's bottom edge, against the gesture bar; `:app` passes no navigation inset when
  there is no sheet. Pass with a sheet: 25 dp above the peek (prototype 24), 56 dp, at the right edge.
- P-4 Locate button: pass.
  - It is hidden until the user pans, then fades in bottom-left (phone) or above the recorder (tablet).
  - Tapping it eases to the location and hides it again.
  - App camera moves don't show it.
- P-5 Location warning: pass for "Location permission is off" with Allow (the system dialog opens) and "Location
  is turned off" with Turn on (the system's resolution dialog opens).
  - The popover is 260 dp wide with radius 28, a tap outside closes it, and the warning goes away once the
    location is usable.
  - "Waiting" and "stale": not checked (they need 30 s with no fix, or a fix 2 min old).
  - The same missing navigation inset as P-3 applies when there is no sheet.
- P-6 Map pan collapses: pass (a pan at Half collapsed the sheet).

## R. Recorder

- R-1 One control: pass.
- R-2 Start: pass, from the FAB and from Onboarding's Done. It morphs to the top center and switches to the new
  session.
- R-3 Island (phone): **fail** (`:chrome` / `:app`, lane C). The island's top is 12 dp from the screen's top edge,
  inside the status bar, while ☰ and search are at 24 + 12 dp. Its center is 34 dp down and theirs is 58 dp, so it
  isn't on their row. Size, content and the 44 dp height match.
- R-4 Panel: **fail** on phone, for the same reason: the panel starts in the status bar and covers its icons.
  - Its size (388 × 220 dp), its row (dot and time centered, Pause and Stop at the corner, Stop 4 dp in), the
    meta line and the fading of ☰ and search all match.
  - A tap outside or Back collapses it.
  - Pass on tablet: it grows upward, 420 dp wide.
- R-5 Mini map: **fail** (`:map`, lane E). The mini map is an empty `--fill2` box on phone and tablet, light and
  dark, with no tiles and no route.
- R-6 Pause/Resume: pass (play icon, static grey dot, and back).
- R-7 Stop: pass. It morphs back to the FAB with no snackbar, and stays on the ended session (V-3).
- R-8 Deliberate asymmetry: pass (there is no stop control at the bottom).
- R-9 Search state (phone): needs device.
- R-10 Storage full: not testable. Lane A's `StorageFull` and cleanups are not on feat/v2, so `:app` still runs
  on the placeholder port. The dialog and the Settings rows are covered by `MainStorageFullTest` and
  `StorageFullScreenTest`. The Settings rows show no sizes, as expected with the placeholder.

## S. Sheets

- S-1 One component: pass. It reaches the bottom edge and runs under the gesture bar.
- S-2 Corner buttons (phone): pass. Their centers are 34 to 35 dp from the side and 48 dp below the sheet top.
- S-3 Header row: pass.
- S-4 Stack: pass for the Filter sheet (pushed and popped). Product and device sheets: needs device.
- S-5 Snap states: **fail** at Full (`:sheets`, lane D). Full stops 68 dp from the screen top, which ignores the
  24 dp status bar, so the sheet slides under ☰ and search (their row ends at 80 dp). Collapsed (the peek) and Half
  (67%) match.
- S-6 Opening a pushed sheet: pass. The Filter sheet opens at Half from Collapsed, and at Full from Full.
- S-7 Only the top sheet is shown: pass with the Filter sheet.
- S-8 Pop: pass for X and Back. The sheet below comes back at its saved height (Full or Collapsed). A drag down
  from Collapsed, the scrim and an empty-map tap: not checked.
- S-9 Scrim: pass. It shows under the Filter sheet at Full, and not at Half.
- S-10 Header drag: not measured.
- S-10a 1:1 tracking: not measured (it can't be measured from screenshots).
- S-11 Tap on the handle or header background: pass. Collapsed goes to Half, and Half or Full go to Collapsed.
- S-12 Header controls raise the root: pass (the view switch at Collapsed switches the view and goes to Half).
- S-13 Content gestures: needs device (no scrollable content).
- S-14 Release: pass (a short flick from Collapsed moved exactly one state, to Half).
- S-15 Wheel or trackpad: not checked (no wheel on the AVD).
- S-16 Fixed height: pass (switching between categories and list kept the height).
- S-17 Map follows the top sheet: needs device.

## D. Sheet contents

- D-1 Header: pass. [Nearby | All] while recording and [This area | All] after Stop (All selected). The view
  switch label flips between "Show the device list" and "Show categories". The filter button mirrors it.
- D-1a View switch transition: pass, with the height unchanged; the crossfade duration wasn't measured.
- D-2 Categories view: needs device.
- D-3 List view: needs device ("No matching devices" for the empty list passes).
- D-3a Filter sheet: pass for the layout. Title "Filter" with X, then the three switches, Identification [Any |
  Identified | Unidentified], Categories (empty here) and Clear. Counts and live updates: needs device.
- D-4 This area: needs device.
- D-5 Empty: pass ("No products match" and "No matching devices", centered `--t2`).
- D-6 Header (product): needs device.
- D-7 Content (product): needs device.
- D-8 Map (product): needs device.
- D-9 Header (device): needs device.
- D-10 Chart: needs device.
- D-11 Tabs: needs device.
- D-12 Advertising: needs device.
- D-13 GATT: needs device.
- D-14 Map (device): needs device.

## M. Map

- M-1 Base map: **fail** (`:map`, lane E). The liberty and dark styles load (roads, buildings, water), but no
  labels and no POI icons are drawn: no street names, no place names, and no "Start" label (M-8). The prototype
  shows all of them. Compact attribution at the bottom-left: pass.
- M-2 Layers: not inspectable on the device; the overlays seen (route, ends, location) come from the style.
- M-3 Points: needs device.
- M-4 Clusters: needs device.
- M-5 Density: needs device.
- M-6 Selection: needs device.
- M-7 Taps: needs device.
- M-8 Route: **fail** (`:map` or the core's live deltas, lane E / A).
  - While recording, with the location moved about 400 m in 15 steps over 30 s, the main map drew no route at all.
  - After Stop the route shows (the right color and opacity) with the Start dot, but without the "Start" label
    (M-1).
- M-9 Location: pass (the blue dot with its white stroke, on the first run). Minor: distances along the same road
  measure about 1.24× the prototype's, so the zoom looks about 0.3 above 16.
- M-10 Framing: **fail** after Stop (`:map`, lane E). The frozen session isn't refitted, and the route runs off
  the top-right edge. The code caps the zoom at 17 (`FRAME_MAX_ZOOM`).
- M-11 Moved-away test: needs device.

## Q. Search

- Q-1 Rule: needs device (it is the core's; core tests).
- Q-2 Scope: needs device.
- Q-3 Phone search page: **fail** for the focused field (C-9). Otherwise pass: the button expands in place to the
  full width, the `--bg` page fades in, the keyboard opens, the hint reads "Search devices by name or MAC address",
  and Back reverses it.
- Q-4 No results: pass ("No results in this area" with "Search everywhere").
- Q-5 Picking a result: needs device.
- Q-6 "Search this area": needs device.
- Q-7 Exit: needs device.

## L. Sidebar and sessions

- L-1 Phone sidebar: pass. 337 dp (82%), flush, with the dimmed strip. ☰ rides the edge as the collapse icon,
  with no title. Back and a tap on the strip close it.
- L-2 Content: pass for "Current session" with "Started 3:39 PM · 0 devices" (selected, `--fill2`) and Settings
  pinned at the bottom. "All sessions" and the day groups are hidden while no session has data, which follows
  "Sessions with no data never appear".
- L-3 Choosing a session: needs device (sessions need data to be listed).

## V. Session views and launch

- V-1 Scanning only while recording: pass (nothing live outside a recording).
- V-2 Recording: **fail** for the live route (M-8). [Nearby | All] and the pinned, selected current session pass.
  Live values and appends: needs device.
- V-3 After Stop: pass. Frozen, [This area | All], and it stays on the ended session.
- V-4 History session: needs device.
- V-5 All sessions: needs device.
- V-6 Launch: pass. "Record when the app opens" (Onboarding's default) starts recording. With it off, the first
  run shows only the location, with no sheet, no search and an empty sidebar.
- V-7 Switching session view: needs device (a second session with data is needed).

## O. Pages

- O-1 Settings: pass. It opens from the sidebar with the `--bg` strip, back button and title, and rows 1 to 12 are
  in order. Under R-10, the two cleanup rows show no sizes (placeholder).
- O-2 Onboarding: pass. Four steps, "N of 4", the titles and bodies, Back from step 2, Next/Done, "Allowed" as
  plain text (as in the prototype), the user ID hint, and the switch on by default. Done records straight away.
  Note: Next on step 3 waits for a valid user ID, while the prototype doesn't validate; SPEC is silent on this.

## T. Tablet

- T-1 Floating panel: pass. 400 dp wide, `--edge` from the left, its top at `--top-zone` (below the status bar),
  radius 30, raised shadow.
- T-2 Panel states: pass for Open, Partial on a user pan, and the bottom gradient. **Fail** for the chevron
  (`:sheets`, lane D): it draws about 76 × 17 dp with a stroke about 3.8 dp wide. The prototype draws it at
  `--handle-w` × 8 (36 × 8) with a 2 dp stroke. The `Icon` call in `SheetHost` passes no size, so the 36:8 glyph
  is scaled to the default 17 dp height.
- T-3 Concentric corners: pass (header buttons 30 dp in and 30 dp down).
- T-4 Top row: pass. ☰ 44 dp, and the field 8 dp to its right, 332 dp wide, at 72 to 404 dp, permanently
  expanded.
- T-5 Search on tablet: pass for typing (a panel-shaped results page, "No results in this area"), Back (it
  closes and leaves search) and the focus ring. Picking a result: needs device.
- T-6 Sessions on tablet: pass. ☰ becomes an X ("Close sessions"), the panel shows the sessions list, and Back
  restores Discover.
- T-7 Recorder on tablet: pass. A 56 dp capsule bottom-right with 40 dp buttons. The panel grows up and to the
  left, 420 × 233 dp. (Its mini map is R-5.)
- T-8 Locate and warning on tablet: pass (12 to 14 dp above the recorder).
- T-9 Resizing: pass. Going from phone to tablet mid-recording opened the panel from Collapsed and kept the
  recording.

## B. Data boundary

- pass: the UI reads data only through `:core-api` (`CoreQueries`, `CoreStorage`) and the platform APIs; the
  seam tests in `RulesTest` hold.

## Failures by owner

- **Lane C** (`:chrome`, `:designsystem`, `:app`), fixed in follow-up PRs: R-3 and R-4 (status inset), C-9 and Q-3
  (focused field), P-3 and P-5 (navigation inset without a sheet), and C-4 (Segmented clipping).
- **Lane E** (`:map`):
  - M-1: no labels or POI icons;
  - R-5: an empty mini map;
  - M-8 and V-2: no live route (or the core's deltas);
  - M-10: no refit after Stop;
  - M-9: the zoom is slightly high (minor).
- **Lane D** (`:sheets`):
  - S-5: Full ignores the status inset;
  - T-2: the chevron is drawn at the wrong size.
- **Lane A** (core): R-10 isn't on feat/v2 yet. M-8's live route may be the core's live deltas, if `:map`
  receives no `routeAppended`.
