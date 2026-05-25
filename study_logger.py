"""
BandPin — study trial logger
-----------------------------
Records per-trial data for the within-subjects study.
Run on a laptop connected to the ESP32 via serial during study sessions,
or integrate with the watch app's Bluetooth logging path.

Measures (per trial):
  - condition        : "bandpin" | "touchscreen"
  - participant_id   : str
  - trial_number     : int
  - pin_entered      : bool (was any PIN submitted)
  - correct          : bool (was it the right PIN)
  - completion_time_ms : int (from first zone tap to final confirmation)
  - num_errors       : int (wrong PIN attempts before success)
  - timestamp        : ISO 8601

Usage:
  logger = TrialLogger(output_dir="data/")
  logger.start_trial(participant_id="P01", condition="bandpin", trial_num=1)
  logger.record_tap(zone=1, position=0.4, duration_ms=110)
  logger.end_trial(correct=True)
  logger.save()
"""

import json
import csv
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from datetime import datetime, timezone


@dataclass
class TapEvent:
    zone: int
    position: float
    duration_ms: int
    timestamp_ms: int


@dataclass
class Trial:
    participant_id: str
    condition: str            # "bandpin" | "touchscreen"
    trial_number: int
    start_time_ms: int = field(default_factory=lambda: int(time.time() * 1000))
    end_time_ms: int | None = None
    correct: bool = False
    num_errors: int = 0
    taps: list[TapEvent] = field(default_factory=list)
    timestamp: str = field(
        default_factory=lambda: datetime.now(timezone.utc).isoformat()
    )

    @property
    def completion_time_ms(self) -> int | None:
        if self.end_time_ms is None:
            return None
        return self.end_time_ms - self.start_time_ms


class TrialLogger:
    """
    Logs study trials to JSON (full detail) and CSV (analysis-ready).
    """

    def __init__(self, output_dir: str = "data"):
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self._trials: list[Trial] = []
        self._current_trial: Trial | None = None

    def start_trial(
        self,
        participant_id: str,
        condition: str,
        trial_num: int,
    ) -> None:
        """Begin a new trial. Call before the participant starts entering PIN."""
        if self._current_trial is not None:
            print("[Logger] WARNING: previous trial not ended — auto-closing")
            self.end_trial(correct=False)

        self._current_trial = Trial(
            participant_id=participant_id,
            condition=condition,
            trial_number=trial_num,
        )
        print(
            f"[Logger] trial started  participant={participant_id} "
            f"condition={condition}  trial={trial_num}"
        )

    def record_tap(self, zone: int, position: float, duration_ms: int) -> None:
        """Record a single zone tap during the active trial."""
        if self._current_trial is None:
            return
        tap = TapEvent(
            zone=zone,
            position=position,
            duration_ms=duration_ms,
            timestamp_ms=int(time.time() * 1000),
        )
        self._current_trial.taps.append(tap)

    def record_error(self) -> None:
        """Increment error count when participant submits wrong PIN."""
        if self._current_trial:
            self._current_trial.num_errors += 1

    def end_trial(self, correct: bool) -> None:
        """Finalise the active trial."""
        if self._current_trial is None:
            return
        self._current_trial.end_time_ms = int(time.time() * 1000)
        self._current_trial.correct = correct
        self._trials.append(self._current_trial)
        print(
            f"[Logger] trial ended  correct={correct}  "
            f"time={self._current_trial.completion_time_ms}ms  "
            f"errors={self._current_trial.num_errors}"
        )
        self._current_trial = None

    def save(self, session_id: str | None = None) -> tuple[Path, Path]:
        """
        Write collected trials to disk.
        Returns (json_path, csv_path).
        """
        tag = session_id or datetime.now().strftime("%Y%m%d_%H%M%S")
        json_path = self.output_dir / f"trials_{tag}.json"
        csv_path  = self.output_dir / f"trials_{tag}.csv"

        # Full JSON (includes tap-level detail)
        with open(json_path, "w") as f:
            payload = []
            for t in self._trials:
                d = asdict(t)
                d["completion_time_ms"] = t.completion_time_ms
                payload.append(d)
            json.dump(payload, f, indent=2)

        # Summary CSV (one row per trial — ready for SPSS / R / pandas)
        fieldnames = [
            "participant_id", "condition", "trial_number",
            "correct", "completion_time_ms", "num_errors",
            "num_taps", "timestamp",
        ]
        with open(csv_path, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            for t in self._trials:
                writer.writerow({
                    "participant_id":    t.participant_id,
                    "condition":         t.condition,
                    "trial_number":      t.trial_number,
                    "correct":           int(t.correct),
                    "completion_time_ms": t.completion_time_ms,
                    "num_errors":        t.num_errors,
                    "num_taps":          len(t.taps),
                    "timestamp":         t.timestamp,
                })

        print(f"[Logger] saved → {json_path}  {csv_path}")
        return json_path, csv_path

    @property
    def trial_count(self) -> int:
        return len(self._trials)


# ── Quick smoke-test ─────────────────────────────────────────────────────────

if __name__ == "__main__":
    logger = TrialLogger(output_dir="/tmp/bandpin_test")

    # Simulate one BandPin trial
    logger.start_trial("P01", "bandpin", trial_num=1)
    time.sleep(0.1)
    for zone, pos in [(0, 0.2), (2, 0.7), (1, 0.5), (0, 0.9)]:
        logger.record_tap(zone=zone, position=pos, duration_ms=95)
        time.sleep(0.05)
    logger.end_trial(correct=True)

    # Simulate one touchscreen trial with an error
    logger.start_trial("P01", "touchscreen", trial_num=2)
    time.sleep(0.2)
    logger.record_error()
    logger.end_trial(correct=True)

    json_p, csv_p = logger.save(session_id="smoke_test")
    print("\nCSV output:")
    print(csv_p.read_text())
