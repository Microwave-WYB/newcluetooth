#!/usr/bin/env python3
"""ui-state-guard: fail on mutable state that holds more than an ephemeral UI primitive in v2 UI code.

AGENTS.md: async results enter UI state only as events through a reducer; no local mutable state updated from
effects. A `mutableStateOf` (or `mutableStateListOf` / `mutableStateMapOf`) is allowed without an entry only when it
holds an ephemeral UI primitive: a Boolean, a String (a text field), or a Float / Dp / Offset (gestures), as its type
argument (`mutableStateOf<Dp?>(null)`) or its literal initial value (`false`, `""`, `0f`, `0.dp`, `Offset.Zero`).
`mutableIntStateOf` / `mutableLongStateOf` / `mutableDoubleStateOf` and every `MutableStateFlow` / `MutableSharedFlow`
constructed in v2 UI code count as state too (a flow written from effects is the same lost-update hazard as Compose
state). Anything else is non-trivial state: put it in a state machine (an immutable value, a pure reducer, one dispatch), or,
for a runner's single state holder or an execution-layer handle, add an entry with its reason to
ui-state-allowlist.txt next to this script. A runner holds one state: a file with more than one `MutableStateFlow`
fails even when each is allowlisted, so data read beside the runner's state (a keyed "data feed") cannot come back;
it enters that state as a result event through the reducer, tagged with the request it answers.

    <path relative to scanner/>:<line>  <reason>

Scanned: the v2 UI modules in SCANNED (src/main, minus gallery packages); legacy v3 code is not scanned. Comments and
strings are blanked first and import lines skipped (crash_guard.code_only, SKIPPED_LINE). Stale entries fail, as in crash-guard.

    ui_state_guard.py              check
    ui_state_guard.py --self-test  test the patterns
"""

import pathlib
import re
import sys

from crash_guard import SCANNER, SKIPPED_LINE, code_only

HERE = pathlib.Path(__file__).resolve().parent
ALLOWLIST = HERE / "ui-state-allowlist.txt"

SCANNED = [
    "sheets/src/main",
    "map/src/main",
    "chrome/src/main",
    "ui-contract/src/main",
    "app/src/main/kotlin/edu/ucsd/sysnet/cluetoothscanner/v2",
]

STATE = re.compile(r"\bmutableState(?:Of|ListOf|MapOf)\b")
# a state holder flow (not a SharedFlow channel): a runner has exactly one
HOLDER = re.compile(r"\bMutableStateFlow\s*(?:<.*?>)?\s*\(")
# always state: numeric Compose state that isn't a gesture Float, and flows constructed as holders or channels
ALWAYS = re.compile(r"\b(?:mutable(?:Int|Long|Double)StateOf|Mutable(?:State|Shared)Flow\s*(?:<.*?>)?\s*\()")
EPHEMERAL_TYPES = {"Boolean", "String", "Float", "Dp", "Offset"}
TYPE_ARG = re.compile(r"\bmutableStateOf\s*<\s*([\w.]+)\??\s*>")
# the first argument after `mutableStateOf(`: a literal of an ephemeral type (strings are blanked to quotes)
LITERAL = re.compile(r"\bmutableStateOf\s*\(\s*(?:true|false|\"\s*\"|-?\d+(?:\.\d+)?f|-?\d+(?:\.\d+)?\.dp|Offset\.Zero)\s*[,)]")


def hits(src: str) -> list[tuple[int, str]]:
    """(line, source line) for every non-ephemeral mutable state in Kotlin [src]."""
    found = []
    lines = src.split("\n")
    for no, code in enumerate(code_only(src).split("\n"), 1):
        if SKIPPED_LINE.match(code):
            continue
        if ALWAYS.search(code):
            found.append((no, lines[no - 1].strip()))
            continue
        for m in STATE.finditer(code):
            rest = code[m.start():]
            t = TYPE_ARG.match(rest)
            if t and t.group(1).split(".")[-1] in EPHEMERAL_TYPES:
                continue
            if not t and LITERAL.match(rest):
                continue
            found.append((no, lines[no - 1].strip()))
    return found


def holders(src: str) -> list[int]:
    """The lines of every `MutableStateFlow` constructed in Kotlin [src]."""
    return [no for no, code in enumerate(code_only(src).split("\n"), 1) if not SKIPPED_LINE.match(code) and HOLDER.search(code)]


def sources() -> list[pathlib.Path]:
    files = []
    for root in SCANNED:
        for p in sorted((SCANNER / root).rglob("*.kt")):
            if "gallery" not in p.relative_to(SCANNER / root).parts:
                files.append(p)
    return files


def read_allowlist() -> dict[str, str]:
    entries = {}
    for no, line in enumerate(ALLOWLIST.read_text().splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = re.match(r"^(\S+:\d+)\s+(\S.*)$", line)
        if not m:
            sys.exit(f"{ALLOWLIST.name}:{no}: expected '<path>:<line>  <reason>', got: {line}")
        entries[m.group(1)] = m.group(2)
    return entries


def check() -> int:
    allowed = read_allowlist()
    seen, bad = set(), []
    feeds = []
    for path in sources():
        rel = path.relative_to(SCANNER).as_posix()
        src = path.read_text()
        held = holders(src)
        if len(held) > 1:
            feeds.append(f"{rel}: lines {', '.join(map(str, held))}")
        for no, text in hits(src):
            key = f"{rel}:{no}"
            if key in allowed:
                seen.add(key)
            else:
                bad.append(f"{key}: {text}")
    stale = sorted(set(allowed) - seen)
    for b in bad:
        print(f"ui-state-guard: non-ephemeral mutable state: {b}", file=sys.stderr)
    for s in stale:
        print(f"ui-state-guard: allowlist entry has no hit (remove it, or move it with the code): {s}", file=sys.stderr)
    for f in feeds:
        print(f"ui-state-guard: more than one MutableStateFlow (a runner holds one state; read data enters it as "
              f"result events through the reducer, never as a data feed beside it): {f}", file=sys.stderr)
    if bad or stale or feeds:
        print("ui-state-guard: async results enter UI state only as events through a reducer (AGENTS.md); an "
              f"exception needs an entry with its reason in {ALLOWLIST.relative_to(SCANNER)}", file=sys.stderr)
        return 1
    print(f"ui-state-guard: ok ({len(sources())} files, {len(allowed)} allowlisted)")
    return 0


def self_test() -> int:
    flagged = [
        "var content by remember { mutableStateOf(DeviceContent()) }",
        "var rows by remember { mutableStateOf<FilterRows>(FilterRows.Pending) }",
        "var groups by mutableStateOf<Loaded<List<CategoryGroup>>>(Loaded.Pending)",
        "var labels by mutableStateOf<Map<String, String>>(emptyMap())",
        "var x: Foo? by mutableStateOf(null)",
        "val items = mutableStateListOf<Row>()",
        "val m = mutableStateMapOf<Long, Row>()",
        "var n by mutableStateOf(count)",
        "private val content = MutableStateFlow(DeviceContent())",
        "private val _ui = MutableStateFlow<MainUiState>(MainUiState())",
        "val events = MutableSharedFlow<Event>(extraBufferCapacity = 4)",
        "val m = MutableStateFlow<Map<Long, List<Row>>>(emptyMap())",
        "var n by remember { mutableIntStateOf(0) }",
        "var t by remember { mutableLongStateOf(now()) }",
        "var d by mutableDoubleStateOf(0.0)",
    ]
    ephemeral = [
        "var shown by remember { mutableStateOf(false) }",
        "var open by mutableStateOf(true)",
        "var q by remember { mutableStateOf(\"\") }",
        "var q by remember { mutableStateOf(\"hint\") }",
        "var live by mutableStateOf<Float?>(null)",
        "var h by mutableStateOf<Dp>(0.dp)",
        "var h by mutableStateOf(12.dp)",
        "var p by mutableStateOf(Offset.Zero)",
        "var f by mutableStateOf(0f)",
        "var f by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)",
        "var b by mutableStateOf<Boolean>(false)",
        "// var content by mutableStateOf(DeviceContent())",
        "val s = \"mutableStateOf(Foo())\"",
        "var p by mutableFloatStateOf(0f)",
        "import androidx.compose.runtime.mutableStateOf",
        "import kotlinx.coroutines.flow.MutableStateFlow",
        "fun f(x: StateFlow<Int>): StateFlow<Int> = x",
        "val s: MutableStateFlow<Int> get() = holder",
        "// MutableStateFlow(DeviceContent())",
    ]
    failures = []
    for src in flagged:
        if len(hits(src)) != 1:
            failures.append(f"expected a hit in {src!r}, got {hits(src)}")
    for src in ephemeral:
        if hits(src):
            failures.append(f"expected no hit in {src!r}, got {hits(src)}")
    # the lost update the owner's blank sheet came from, as a flow: the holder is the hit, on its own line
    lost = "private val content = MutableStateFlow(DeviceContent())\n" \
        "suspend fun open() {\n    content.value = content.value.withDetail(core.device(key))\n}"
    if [no for no, _ in hits(lost)] != [1]:
        failures.append(f"lost update via MutableStateFlow: expected a hit on line 1, got {hits(lost)}")
    # a runner with a data feed beside its state: two holders, whatever the allowlist says
    fed = "private val _ui = MutableStateFlow(MainUiState())\n" \
        "private val _view = MutableStateFlow<ViewData?>(null)\n" \
        "private val _requests = MutableSharedFlow<ActivityRequest>(extraBufferCapacity = 4)\n" \
        "// private val _old = MutableStateFlow(0)"
    if holders(fed) != [1, 2]:
        failures.append(f"data feed beside the runner's state: expected holders on lines 1 and 2, got {holders(fed)}")
    if holders("private val _ui = MutableStateFlow(MainUiState())\nval requests = MutableSharedFlow<Int>()") != [1]:
        failures.append("a runner's one state and a SharedFlow channel: expected one holder")
    for f in failures:
        print(f"ui-state-guard self-test: {f}", file=sys.stderr)
    if failures:
        return 1
    print(f"ui-state-guard self-test: ok ({len(flagged) + len(ephemeral) + 3} cases)")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        sys.exit(__doc__)
    sys.exit(check())
