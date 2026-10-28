#!/usr/bin/env python3
"""Copy rendered preview PNGs into a flat folder and write an index.html (light | dark side by side).

usage: ui-gallery.py <reference-image-dir> <output-dir>
"""
import html
import re
import shutil
import sys
from pathlib import Path

# (preview function, title, what to look at) in review order.
SCREENS = [
    ("ScanList", "Scan", "Stock TopAppBar (count · location), ListItem rows, NavigationBar, stock FAB"),
    ("ScanNoLocation", "Scan, no location", "Location unavailable in the subtitle"),
    ("ScanRecording", "Scan, recording", "Expanded control: blinking dot, time, pause, stop"),
    ("ScanPaused", "Scan, paused, stale fix", "Gray steady dot, dimmed time; Location stale (2 min)"),
    ("ScanScrolledDown", "Scan, scrolled", "Scroll-to-top small FAB centered just below the top app bar"),
    ("ScanScrolledDownRecording", "Scan, scrolled, recording", "Same place while recording; the record control stays bottom-right"),
    ("ScanDeviceSheet", "Device sheet, recognized device", "Modal sheet (drawn in place in previews): wraps its content, ends below the app bar; Wiki button"),
    ("ScanDeviceSheetLongName", "Device sheet, long name", "Name ellipsizes; -101 dBm inline; each line on one line"),
    ("ScanDeviceSheetShortLived", "Device sheet, short-lived, not recognized", "Heard for 4 s; no Wiki button"),
    ("ScanDeviceSheetGattRecording", "Device sheet, GATT, recording", "Pill over the app bar; the sheet stays below it"),
    ("ScanDeviceSheetNotConnectable", "Device sheet, not connectable", "Why GATT is disabled"),
    ("ScanControlSheet", "Control sheet", "Opened from the pill: time, rows, Pause/Resume, Stop"),
    ("NearbyHome", "Discover, Nearby", "Category ListItems with rows of stock Cards, in range now"),
    ("NearbyAll", "Discover, All", "The same layout with what was seen before"),
    ("NearbyRecording", "Discover, recording", "Floating pill"),
    ("NearbyCategoryList", "Discover, category", "ListItems"),
    ("ProductPage", "Product page", "Image stand-in, full name, wiki, map preview card, devices"),
    ("ProductPageNoMap", "Product page, no located sightings", "Record to put it on the map"),
    ("SettingsPage", "Settings", "Stock ListItems and switches; replaces Scan Sessions"),
    ("OnboardingPermissions", "Onboarding, permissions", "First launch, step 1 of 4"),
    ("OnboardingUserId", "Onboarding, user ID", "Replaces the startup dialog"),
    ("MissingPermission", "Permission revoked later", "Only the missing piece, with its fix"),
    ("ScanBluetoothOff", "Bluetooth off later", "A stock card under the app bar with Turn on"),
    ("HistoryList", "History", "Pinned in-progress recording, then days"),
    ("HistoryIdle", "History, idle", "Round record button"),
    ("RecordingMapRoute", "Recording map", "Stock TopAppBar; persistent BottomSheetScaffold sheet peeking"),
    ("RecordingMapListExpanded", "Recording map, list expanded", "The sheet stops below the app bar"),
    ("RecordingMapDeviceSelected", "Recording map, device", "Device detail in the sheet"),
    ("RecordingMapDeviceExpanded", "Recording map, device expanded", "GATT tab; below the app bar"),
    ("RecordingMapPannedHeatmap", "Recording map, panned, density", "Only this area chip"),
    ("ProductMap", "Product map", "MACs of one product"),
    ("RecordingMapWithPill", "Recording map, recording", "The same pill as every page"),
    ("ProductMapWithPillPaused", "Product map, paused", "Gray dot in the pill"),
    ("RecordControlStates", "Record control, every state", "Stock FAB; Surface + IconButtons; pill; small FAB; control sheet"),
]


def main() -> None:
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    images: dict[tuple[str, str], Path] = {}
    for png in sorted(src.rglob("*.png")):
        # <Function>_<light|dark>_<hash>_0.png
        m = re.match(r"([A-Za-z0-9]+)_(light|dark|narrow)_", png.name)
        if m:
            images[(m.group(1), m.group(2))] = png
    rows = []
    missing = []
    for fn, title, note in SCREENS:
        cells = []
        for mode in ("light", "dark", "narrow"):
            png = images.get((fn, mode))
            if png is None:
                missing.append(f"{fn}/{mode}")
                cells.append("<div class=miss>missing</div>")
                continue
            name = f"{fn}_{mode}.png"
            shutil.copyfile(png, out / name)
            cells.append(f'<a href="{name}"><img src="{name}" loading=lazy></a>')
        rows.append(
            f"<section><h2>{html.escape(title)} <small>{fn}</small></h2><p>{html.escape(note)}</p>"
            f"<div class=pair>{''.join(cells)}</div></section>"
        )
    (out / "index.html").write_text(
        """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Cluetooth v3 UI</title>
<style>
body{font:15px system-ui,sans-serif;margin:0;padding:24px;background:#f4f4f5;color:#1a1b1e}
h1{font-size:20px;margin:0 0 4px}.sub{color:#62656b;margin:0 0 24px}
section{margin:0 0 40px}h2{font-size:16px;margin:0}h2 small{font:12px ui-monospace,monospace;color:#9a9da3;margin-left:8px}
p{margin:2px 0 10px;color:#62656b}.pair{display:flex;gap:20px;flex-wrap:wrap}
img{width:300px;height:auto;border:1px solid #d5d7db;border-radius:10px;display:block}
.miss{width:300px;height:200px;border:1px dashed #d93a35;color:#d93a35;display:flex;align-items:center;justify-content:center}
</style>
<h1>Cluetooth v3 UI</h1><p class=sub>Fake data, static previews. Left to right: light and dark at 411 dp, light at 360 dp. Click for full size.</p>
"""
        + "\n".join(rows)
    )
    print(f"{len(SCREENS)} screens, {len(images)} images, missing: {missing or 'none'}")


if __name__ == "__main__":
    main()
