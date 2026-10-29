#!/usr/bin/env python3
"""crash-guard: fail on the constructs AGENTS.md bans from v2 production Kotlin.

Banned ("Crashes are ruled out structurally"): `!!`, `lateinit`, an unchecked `as` (`as?` is fine),
error/check/require/checkNotNull/requireNotNull calls, TODO(), runBlocking and GlobalScope.

Scanned: src/main of the v2 modules in SCANNED (paths relative to scanner/), minus gallery packages.
Comments and string literals are blanked first (string templates stay code); import and package lines
are skipped, so an import alias `as` or an unused import never counts.

A justified exception is one line in allowlist.txt next to this script:

    <path relative to scanner/>:<line>  <reason>

An entry covers every hit on its line. The check fails on a hit without an entry and on an entry whose
line has no hit (remove it, or move it with the code).

    crash_guard.py              check (exit 1 on a violation or a stale entry)
    crash_guard.py --self-test  test the lexer and the patterns
"""

import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
SCANNER = HERE.parent.parent
ALLOWLIST = HERE / "allowlist.txt"

SCANNED = [
    "sheets/src/main",
    "chrome/src/main",
    "map/src/main",
    "platform/src/main",
    "core-bridge/src/main",
    "designsystem/src/main",
    "ui-contract/src/main",
    "app/src/main/kotlin/edu/ucsd/sysnet/cluetoothscanner/v2",
]

PATTERNS = [
    ("!!", re.compile(r"!!")),
    ("lateinit", re.compile(r"\blateinit\b")),
    ("as", re.compile(r"\bas\b(?!\?)")),
    ("error/check/require", re.compile(r"(?<![\w.:`])(?:error|check|require|checkNotNull|requireNotNull)\s*\(")),
    ("TODO()", re.compile(r"(?<![\w.:`])TODO\s*\(")),
    ("runBlocking", re.compile(r"\brunBlocking\b")),
    ("GlobalScope", re.compile(r"\bGlobalScope\b")),
]
SKIPPED_LINE = re.compile(r"^\s*(?:import|package)\s")
DECLARATION = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?$")


def code_only(src: str) -> str:
    """Blank comments and string contents with spaces, keeping newlines and `${...}` template code."""
    out = []
    i, n = 0, len(src)
    # stack of contexts: "code" (with its brace depth), '"', '"""'
    stack: list[list] = [["code", 0]]
    while i < n:
        top = stack[-1]
        c = src[i]
        if top[0] == "code":
            if src.startswith("//", i):
                j = src.find("\n", i)
                j = n if j < 0 else j
                out.append(" " * (j - i))
                i = j
            elif src.startswith("/*", i):
                depth, j = 1, i + 2
                while j < n and depth:
                    if src.startswith("/*", j):
                        depth, j = depth + 1, j + 2
                    elif src.startswith("*/", j):
                        depth, j = depth - 1, j + 2
                    else:
                        j += 1
                out.append(re.sub(r"[^\n]", " ", src[i:j]))
                i = j
            elif src.startswith('"""', i):
                stack.append(['"""'])
                out.append('"""')
                i += 3
            elif c == '"':
                stack.append(['"'])
                out.append('"')
                i += 1
            elif c == "'":
                m = re.match(r"'(?:\\u[0-9a-fA-F]{4}|\\.|[^'\\])'", src[i:])
                j = i + (len(m.group(0)) if m else 1)
                out.append("'" + " " * (j - i - 2) + "'" if m else c)
                i = j
            elif c == "{":
                top[1] += 1
                out.append(c)
                i += 1
            elif c == "}":
                if top[1] == 0 and len(stack) > 1:
                    stack.pop()  # end of a ${...} template
                else:
                    top[1] -= 1
                out.append(c)
                i += 1
            else:
                out.append(c)
                i += 1
        else:
            raw = top[0] == '"""'
            if raw and src.startswith('"""', i):
                j = i + 3
                while j < n and src[j] == '"':
                    j += 1  # """ followed by more quotes ends at the last three
                out.append(" " * (j - i - 3) + '"""')
                stack.pop()
                i = j
            elif not raw and c == '"':
                stack.pop()
                out.append(c)
                i += 1
            elif not raw and c == "\\" and i + 1 < n:
                out.append("  ")
                i += 2
            elif src.startswith("${", i):
                stack.append(["code", 0])
                out.append("${")
                i += 2
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
    return "".join(out)


def hits(src: str) -> list[tuple[int, str, str]]:
    """(line, kind, source line) for every banned construct in Kotlin [src]."""
    found = []
    lines = src.split("\n")
    for no, code in enumerate(code_only(src).split("\n"), 1):
        if SKIPPED_LINE.match(code):
            continue
        for kind, pattern in PATTERNS:
            for m in pattern.finditer(code):
                if kind in ("error/check/require", "TODO()") and DECLARATION.search(code[: m.start()]):
                    continue  # a declaration named like the stdlib function, not a call
                found.append((no, kind, lines[no - 1].strip()))
    return found


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
    for path in sources():
        rel = path.relative_to(SCANNER).as_posix()
        for no, kind, text in hits(path.read_text()):
            key = f"{rel}:{no}"
            if key in allowed:
                seen.add(key)
            else:
                bad.append(f"{key}: {kind}: {text}")
    stale = sorted(set(allowed) - seen)
    for b in bad:
        print(f"crash-guard: banned construct: {b}", file=sys.stderr)
    for s in stale:
        print(f"crash-guard: allowlist entry has no hit (remove it, or move it with the code): {s}", file=sys.stderr)
    if bad or stale:
        print("crash-guard: see AGENTS.md 'Crashes are ruled out structurally'; an exception needs an entry "
              f"with its reason in {ALLOWLIST.relative_to(SCANNER)}", file=sys.stderr)
        return 1
    print(f"crash-guard: ok ({len(sources())} files, {len(allowed)} allowlisted)")
    return 0


def self_test() -> int:
    banned = {
        "val a = b!!": "!!",
        "lateinit var x: Foo": "lateinit",
        "val a = b as Foo": "as",
        "val a = (b as List<*>).size": "as",
        "error(\"boom\")": "error/check/require",
        "check(x > 0)": "error/check/require",
        "require (x)": "error/check/require",
        "val v = checkNotNull(x)": "error/check/require",
        "requireNotNull(x) { \"m\" }": "error/check/require",
        "fun f(): Int = TODO()": "TODO()",
        "runBlocking { f() }": "runBlocking",
        "GlobalScope.launch { }": "GlobalScope",
        "val s = \"${a!!.b}\"": "!!",
        "val s = \"x ${if (a) { b as C } else c} y\"": "as",
    }
    allowed = [
        "val a = b as? Foo",
        "val a = b ?: return",
        "val a = !b",
        "x.check(y)",
        "state.error(y)",
        "this::error",
        "fun check(x: Int) = x",
        "private fun Foo.require(x: Int) = x",
        "val error = e",
        "Failed(error = e)",
        "onError(e)",
        "if (a != b) c",
        "// a!! as Foo, lateinit, error(x), runBlocking",
        "/* outer /* nested */ still a comment: b!! */",
        "val s = \"a!! as Foo error(x) TODO() runBlocking GlobalScope\"",
        "val s = \"\"\"raw \"quoted\" b!! as Foo\"\"\"",
        "val s = \"escaped \\\" b!! \"",
        "val c = '\"'; val d = '!'",
        "import kotlinx.coroutines.runBlocking",
        "import a.b.C as D",
        "package a.b",
        "val ok = alias.assignment",
        "val hasNext = true",
        "val error: Throwable? = null",
    ]
    failures = []
    for src, kind in banned.items():
        got = [k for _, k, _ in hits(src)]
        if got != [kind]:
            failures.append(f"expected [{kind}] in {src!r}, got {got}")
    for src in allowed:
        got = hits(src)
        if got:
            failures.append(f"expected no hit in {src!r}, got {got}")
    multi = "val a = \"\"\"\nb!!\n\"\"\"\nval c = d!!\n/*\n e as F\n*/\nlateinit var g: H"
    got = [(no, k) for no, k, _ in hits(multi)]
    if got != [(4, "!!"), (8, "lateinit")]:
        failures.append(f"multi-line strings and comments keep line numbers: got {got}")
    for f in failures:
        print(f"crash-guard self-test: {f}", file=sys.stderr)
    if failures:
        return 1
    print(f"crash-guard self-test: ok ({len(banned) + len(allowed) + 1} cases)")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        sys.exit(__doc__)
    sys.exit(check())
