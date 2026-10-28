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
    ("ScanList", "扫描 · 列表", "按信号排序，行内迷你曲线；右下角是空闲的录制按钮"),
    ("ScanRecording", "扫描 · 录制中", "录制按钮展开成胶囊：脉动红点、时间、暂停、停止"),
    ("ScanPaused", "扫描 · 已暂停", "时间冻结（真机上缓慢闪烁），暂停变成继续"),
    ("ScanPullToRefresh", "扫描 · 下拉刷新", "下拉时重新排序"),
    ("ScanScrolledDown", "扫描 · 向下滚动", "回到顶部按钮叠在录制按钮正上方"),
    ("ScanScrolledDownRecording", "扫描 · 滚动 + 录制中", "胶囊上方的回到顶部按钮"),
    ("ScanDeviceHalfSheet", "设备详情 · 半屏", "录制按钮缩进右上角；设备详情的共享组件"),
    ("ScanDeviceHalfSheetNotConnectable", "设备详情 · 半屏 · 不可连接", "按钮置灰并说明原因；迷你胶囊录制中"),
    ("ScanRecordControlBar", "录制控制条", "点迷你胶囊后从底部出现"),
    ("ScanSavedSnackbar", "停止后的提示", "已保存 · 时长 · 条数，加备注 / 查看"),
    ("DeviceFullBroadcast", "设备详情 · 全屏 · 广播", "每个变体一行，展开后是 AD 结构表"),
    ("DeviceFullGattInProgress", "设备详情 · 全屏 · GATT 进行中", "步骤进度列表"),
    ("DeviceFullGattDone", "设备详情 · 全屏 · GATT 完成", "服务 → 特征 → 值；错误红色"),
    ("DeviceFullWiki", "设备详情 · 全屏 · Wiki", "仅已识别设备"),
    ("DeviceFullNotConnectable", "设备详情 · 全屏 · 不可连接", "GATT 页签里的禁用说明"),
    ("NearbyHome", "附近", "分类行；范围内在前，带信号点；曾见过的带上次日期"),
    ("NearbyRecording", "附近 · 录制中", "迷你胶囊（红点 + 时间）在右上角"),
    ("NearbyPaused", "附近 · 已暂停", "迷你胶囊，灰点"),
    ("NearbyControlBar", "附近 · 录制控制条", "点迷你胶囊后的底部控制条"),
    ("NearbyCategoryList", "附近 · 分类列表", "点分类右侧箭头进入"),
    ("RecordingsList", "录制 · 列表", "按天分组，当前录制置顶"),
    ("RecordingsListIdle", "录制 · 空闲", "没有置顶项；迷你录制点"),
    ("RecordingsSavedSnackbar", "录制 · 停止后", "停止后的提示"),
    ("RecordingMapRoute", "录制详情 · 路线", "默认只显示路线和起止点；底部是设备列表"),
    ("RecordingMapDeviceSelected", "录制详情 · 选中设备", "只显示该设备的目击点和估计位置；底部切到设备详情"),
    ("RecordingMapPannedHeatmap", "录制详情 · 平移后 + 热力图", "只看此区域 chip 与密度热力图"),
    ("ProductMap", "产品地图", "来自“附近”；跨录制的点；列表是该产品的 MAC"),
    ("RecordControlStates", "录制按钮 · 全部状态", "空闲 / 录制中 / 暂停 · 完整与迷你；回到顶部；提示；控制条"),
    ("RecordControlBarPaused", "录制控制条 · 已暂停", "继续 / 结束录制"),
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
