#!/usr/bin/env python3
"""single-renderer: fail unless the app has at most one MapLibre renderer at a time (SPEC R-5 removed, a201027).

A second native renderer next to the main map crashed MapLibre 12.3.1 (SIGSEGV in
MapSnapshotter::Impl::onDidFinishLoadingStyle). So, in every module's src/main (Kotlin, Java, layout XML):

- no MapSnapshotter at all (code or import);
- a MapView is constructed only in MAP_VIEW_SITES (the :map main map, and the v3 legacy UI that never runs);
- CluetoothMap (the :map composable that owns that MapView) is called from at most one place.

Comments and string literals are blanked first (crash-guard's lexer), so a comment naming MapSnapshotter is fine.

    single_renderer.py              check (exit 1 on a violation)
    single_renderer.py --self-test  test the patterns
"""

import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
SCANNER = HERE.parent.parent
sys.dont_write_bytecode = True  # importing crash-guard must not leave a __pycache__ in the tree
sys.path.insert(0, str(HERE.parent / "crash-guard"))
from crash_guard import code_only  # noqa: E402

# path relative to scanner/ -> why a MapView may be built there (at most one construction per file)
MAP_VIEW_SITES = {
    "map/src/main/kotlin/edu/ucsd/sysnet/cluetoothscanner/map/CluetoothMap.kt": "the one main map",
    "app/src/main/java/edu/ucsd/sysnet/cluetoothscanner/ui/v3/screen/MapLibreMap.kt":
        "v3 legacy UI: V3MainActivity is not in the manifest, so it never runs next to v2",
}

SNAPSHOTTER = re.compile(r"\bMapSnapshotter\b")
MAP_VIEW = re.compile(r"\bMapView\s*\(")
MAP_VIEW_XML = re.compile(r"<\s*org\.maplibre\.android\.maps\.MapView\b")
CLUETOOTH_MAP_CALL = re.compile(r"(?<!fun )\bCluetoothMap\s*\(")


def sources() -> list[pathlib.Path]:
    files = []
    for main in sorted(SCANNER.glob("*/src/main")):
        for p in sorted(main.rglob("*")):
            if "build" in p.relative_to(SCANNER).parts:
                continue
            if p.suffix in (".kt", ".java") or (p.suffix == ".xml" and "layout" in p.parent.name):
                files.append(p)
    return files


def hits(src: str, xml: bool) -> list[tuple[int, str]]:
    """(line, kind) for every snapshotter, MapView construction and CluetoothMap call in [src]."""
    out = []
    if xml:
        for no, line in enumerate(src.splitlines(), 1):
            if MAP_VIEW_XML.search(line):
                out.append((no, "MapView"))
        return out
    for no, line in enumerate(code_only(src).splitlines(), 1):
        if SNAPSHOTTER.search(line):
            out.append((no, "MapSnapshotter"))
        if not line.lstrip().startswith("import "):
            out += [(no, "MapView") for _ in MAP_VIEW.finditer(line)]
        out += [(no, "CluetoothMap") for _ in CLUETOOTH_MAP_CALL.finditer(line)]
    return out


def violations(found: dict[str, list[tuple[int, str]]]) -> list[str]:
    bad = []
    calls = []
    for rel, hs in found.items():
        views = [no for no, kind in hs if kind == "MapView"]
        for no, kind in hs:
            if kind == "MapSnapshotter":
                bad.append(f"{rel}:{no}: MapSnapshotter is a second native renderer; the app has none (R-5 removed)")
            if kind == "CluetoothMap":
                calls.append(f"{rel}:{no}")
        if views and rel not in MAP_VIEW_SITES:
            bad += [f"{rel}:{no}: a MapView outside {sorted(MAP_VIEW_SITES)}" for no in views]
        elif len(views) > 1:
            bad += [f"{rel}:{no}: a second MapView in this file" for no in views[1:]]
    if len(calls) > 1:
        bad += [f"{c}: CluetoothMap is called from more than one place: {calls}" for c in calls]
    return bad


def check() -> int:
    files = sources()
    found = {}
    for p in files:
        rel = p.relative_to(SCANNER).as_posix()
        hs = hits(p.read_text(errors="replace"), xml=p.suffix == ".xml")
        if hs:
            found[rel] = hs
    bad = violations(found)
    for b in bad:
        print(f"single-renderer: {b}", file=sys.stderr)
    if bad:
        print("single-renderer: only one MapLibre renderer may exist at a time; see map/README.md", file=sys.stderr)
        return 1
    print(f"single-renderer: ok ({len(files)} files)")
    return 0


def self_test() -> int:
    site = next(iter(MAP_VIEW_SITES))
    cases = [
        # (files, number of violations)
        ({"a.kt": "val s = MapSnapshotter(context, options)"}, 1),
        ({"a.kt": "import org.maplibre.android.snapshotter.MapSnapshotter"}, 1),
        ({"a.kt": "// no MapSnapshotter here\nval s = \"MapSnapshotter(\""}, 0),
        ({site: "MapView(context, options)"}, 0),
        ({site: "MapView(context)\nMapView(context)"}, 1),
        ({"other.kt": "val v = MapView(context)"}, 1),
        ({"other.kt": "import org.maplibre.android.maps.MapView\nfun f(v: MapView) = v.onStart()"}, 0),
        ({"res/layout/x.xml": "<org.maplibre.android.maps.MapView android:id=\"@+id/m\" />"}, 1),
        ({"m.kt": "fun CluetoothMap(state: S) {}", "a.kt": "CluetoothMap(s, e)"}, 0),
        ({"a.kt": "CluetoothMap(s, e)", "b.kt": "CluetoothMap(s, e)"}, 2),
        ({"a.kt": "if (t) CluetoothMap(s, e) else CluetoothMap(s, e)"}, 2),
    ]
    failed = 0
    for files, expected in cases:
        found = {rel: h for rel, src in files.items() if (h := hits(src, xml=rel.endswith(".xml")))}
        got = len(violations(found))
        if got != expected:
            failed += 1
            print(f"single-renderer self-test: {files}: {got} violations, expected {expected}", file=sys.stderr)
    if failed:
        return 1
    print(f"single-renderer self-test: ok ({len(cases)} cases)")
    return 0


if __name__ == "__main__":
    sys.exit(self_test() if sys.argv[1:] == ["--self-test"] else check())
