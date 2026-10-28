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
    ("ScanList", "Scan", "Sorted by signal with a sparkline per row; subtitle is the device count; round red record button, bottom-right"),
    ("ScanRecording", "Scan, recording", "Expanded button: blinking dot, time, Pause, Stop"),
    ("ScanPaused", "Scan, paused", "Gray steady dot, frozen dimmed time, Resume instead of Pause"),
    ("ScanPullToRefresh", "Scan, pull to refresh", "Pulling re-sorts the list"),
    ("ScanScrolledDown", "Scan, scrolled down", "Scroll-to-top button stacked above the round button"),
    ("ScanScrolledDownRecording", "Scan, scrolled, recording", "Scroll-to-top above the expanded button"),
    ("ScanDeviceHalfSheet", "Device sheet, half height", "Shared device-detail component"),
    ("ScanDeviceHalfSheetShortLived", "Device sheet, short-lived", "First heard 20 s ago: the few buckets at the right edge, the time axis, and the Heard for caption"),
    ("ScanDeviceHalfSheetNotConnectable", "Device sheet, not connectable", "Disabled button with the reason; the sheet covers Scan, so the recording control is the floating pill"),
    ("ScanRecordControlBar", "Control bar", "Opens from the pill: time, rows, Pause/Resume, Stop"),
    ("ScanSavedSnackbar", "After Stop", "Saved, duration, rows; Add note / View"),
    ("DeviceFullBroadcast", "Device, full screen, Advertising", "One row per variant; expands to the AD structure table"),
    ("DeviceFullGattInProgress", "Device, full screen, GATT running", "Step progress list"),
    ("DeviceFullGattDone", "Device, full screen, GATT done", "Services, characteristics, values; errors in red"),
    ("DeviceFullWiki", "Device, full screen, Wiki", "Identified devices only"),
    ("DeviceFullNotConnectable", "Device, full screen, not connectable", "Why GATT is disabled"),
    ("NearbyHome", "Nearby", "Category rows; in range first with a signal dot; seen before with a last-seen date"),
    ("NearbyRecording", "Nearby, recording", "Floating top-right pill (blinking dot + time)"),
    ("NearbyPaused", "Nearby, paused", "Pill with a gray steady dot"),
    ("NearbyControlBar", "Nearby, control bar", "Bottom control bar opened from the pill"),
    ("NearbyCategoryList", "Nearby, category list", "Opened with the arrow next to a category"),
    ("RecordingsList", "History", "Grouped by day, the current recording pinned"),
    ("RecordingsListIdle", "History, idle", "Round record button, bottom-right"),
    ("RecordingsSavedSnackbar", "History, after Stop", "Saved snackbar"),
    ("RecordingMapRoute", "Recording map, route", "Same top app bar as the other pages; route and device list"),
    ("RecordingMapDeviceSelected", "Recording map, device selected", "Ring and dot only; the estimated position is in the sheet"),
    ("RecordingMapPannedHeatmap", "Recording map, panned, density", "Only this area chip and the density layer"),
    ("ProductMap", "Product map", "From Nearby; points across recordings; the list is the product's MACs"),
    ("RecordingMapWithPill", "Recording map, recording", "The same floating pill as every other page; the layer action ends before it"),
    ("RecordingMapWithPillPaused", "Recording map, paused, device selected", "Gray steady dot in the pill"),
    ("ProductMapWithPill", "Product map, recording", "Same pill"),
    ("RecordControlStates", "Record control, every state", "Round button; expanded active and paused; pill active and paused; scroll-to-top; snackbar; control bar"),
    ("RecordControlBarPaused", "Control bar, paused", "Resume / Stop"),
]


def main() -> None:
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    images: dict[tuple[str, str], Path] = {}
    for png in sorted(src.rglob("*.png")):
        # <Function>_<light|dark>_<hash>_0.png
        m = re.match(r"([A-Za-z0-9]+)_(light|dark)_", png.name)
        if m:
            images[(m.group(1), m.group(2))] = png
    rows = []
    missing = []
    for fn, title, note in SCREENS:
        cells = []
        for mode in ("light", "dark"):
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
<h1>Cluetooth v3 UI</h1><p class=sub>Fake data, static previews. Left light, right dark. Click for full size.</p>
"""
        + "\n".join(rows)
    )
    print(f"{len(SCREENS)} screens, {len(images)} images, missing: {missing or 'none'}")


if __name__ == "__main__":
    main()
