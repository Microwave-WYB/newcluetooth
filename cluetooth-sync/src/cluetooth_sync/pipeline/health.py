import json
import os
import tempfile
import threading
from dataclasses import asdict, dataclass, field, fields
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .orchestrate import CycleStats


def _now() -> str:
    return datetime.now(UTC).isoformat()


@dataclass
class SyncStatus:
    """Process-wide sync health, published as a heartbeat JSON file."""

    started_at: str = field(default_factory=_now)
    phase: str = "starting"
    cycles: int = 0
    cycle_started_at: str | None = None
    last_cycle_finished_at: str | None = None
    cycle: CycleStats = field(default_factory=CycleStats)
    total_succeeded: int = 0
    total_failed: int = 0
    total_rows: int = 0
    consecutive_cycle_errors: int = 0
    last_cycle_error: str | None = None
    last_projection_at: str | None = None
    projection_scans_through_id: int | None = None
    consecutive_projection_errors: int = 0
    last_projection_error: str | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def begin_cycle(self) -> None:
        with self._lock:
            self.phase = "discovering"
            self.cycle_started_at = _now()
            self.cycle = CycleStats()

    def cycle_progress(self, stats: CycleStats) -> None:
        with self._lock:
            self.phase = "syncing"
            self.cycle = CycleStats(**asdict(stats))

    def end_cycle(self, stats: CycleStats) -> None:
        with self._lock:
            self.phase = "idle"
            self.cycles += 1
            self.last_cycle_finished_at = _now()
            self.cycle = CycleStats(**asdict(stats))
            self.total_succeeded += stats.succeeded
            self.total_failed += stats.failed
            self.total_rows += stats.rows
            self.consecutive_cycle_errors = 0

    def cycle_failed(self, exc: BaseException) -> None:
        with self._lock:
            self.phase = "cycle-error-backoff"
            # Blobs finished before the error are committed; count them anyway.
            self.total_succeeded += self.cycle.succeeded
            self.total_failed += self.cycle.failed
            self.total_rows += self.cycle.rows
            self.consecutive_cycle_errors += 1
            self.last_cycle_error = f"{_now()} {type(exc).__name__}: {exc}"[:2000]

    def projection_succeeded(self, scans_through_id: int) -> None:
        with self._lock:
            self.last_projection_at = _now()
            self.projection_scans_through_id = scans_through_id
            self.consecutive_projection_errors = 0

    def projection_failed(self, exc: BaseException) -> None:
        with self._lock:
            self.consecutive_projection_errors += 1
            self.last_projection_error = f"{_now()} {type(exc).__name__}: {exc}"[:2000]

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            data = {
                item.name: getattr(self, item.name)
                for item in fields(self)
                if not item.name.startswith("_")
            }
            data["cycle"] = asdict(self.cycle)
        data["updated_at"] = _now()
        data["pid"] = os.getpid()
        return data


def write_heartbeat(status: SyncStatus, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(status.snapshot(), indent=2, sort_keys=True).encode()
    with tempfile.NamedTemporaryFile(
        mode="wb", dir=path.parent, prefix=".heartbeat-", delete=False
    ) as temporary:
        temporary.write(payload)
    os.replace(temporary.name, path)


def check_heartbeat(
    path: Path,
    *,
    max_age_seconds: float,
    max_consecutive_errors: int,
) -> tuple[bool, str]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        updated_at = datetime.fromisoformat(data["updated_at"])
    except (OSError, ValueError, KeyError) as exc:
        return False, f"unreadable heartbeat {path}: {exc}"

    age = (datetime.now(UTC) - updated_at).total_seconds()
    cycle = data.get("cycle") or {}
    summary = (
        f"phase={data.get('phase')} age={age:.0f}s cycles={data.get('cycles')} "
        f"cycle={cycle.get('completed')}/{cycle.get('discovered')} "
        f"cycle_failed={cycle.get('failed')} total_ok={data.get('total_succeeded')} "
        f"total_failed={data.get('total_failed')} rows={data.get('total_rows')} "
        f"cycle_errors={data.get('consecutive_cycle_errors')} "
        f"projection_errors={data.get('consecutive_projection_errors')}"
    )
    if age > max_age_seconds:
        return False, f"stale heartbeat: {summary}"
    if data.get("consecutive_cycle_errors", 0) >= max_consecutive_errors:
        return False, f"repeated cycle errors: {summary}"
    if data.get("consecutive_projection_errors", 0) >= max_consecutive_errors:
        return False, f"repeated projection errors: {summary}"
    return True, summary
