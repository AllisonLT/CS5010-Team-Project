"""In-memory per-process alert deduplication; deliberately NOT persistent.

A fresh application run or multiple Uvicorn workers have independent state.
Production-like persistence belongs to Spring Boot/MongoDB, not this module.
"""
from __future__ import annotations

from dataclasses import dataclass
from threading import Lock


@dataclass
class AlertState:
    severity: str = "clear"
    clear_count: int = 0
    last_timestamp: str | None = None


class AlertRegistry:
    def __init__(self, clear_after: int = 3):
        self.states: dict[tuple[str, float], AlertState] = {}
        self.clear_after = clear_after
        self.lock = Lock()

    def process(self, forecast: dict) -> dict:
        key = (forecast["sensor_id"], float(forecast["threshold"]))
        now = forecast["origin_timestamp"]
        crosses = [p["horizon_minutes"] for p in forecast["predictions"] if p["predicted_threshold_crossing"]]
        if forecast["currently_above_threshold"]:
            desired = "current_violation"
        elif crosses:
            desired = "imminent" if min(crosses) <= 15 else ("warning" if min(crosses) <= 30 else "advisory")
        else:
            desired = "clear"
        ranks = {"clear": 0, "advisory": 1, "warning": 2, "imminent": 3, "current_violation": 4}
        with self.lock:
            state = self.states.setdefault(key, AlertState())
            if state.last_timestamp is not None and now <= state.last_timestamp:
                raise ValueError("Alerts require strictly increasing origin timestamps per sensor and threshold")
            previous = state.severity
            state.last_timestamp = now
            event = "none"
            if desired == "clear":
                if previous != "clear":
                    state.clear_count += 1
                    if state.clear_count >= self.clear_after:
                        state.severity = "clear"
                        state.clear_count = 0
                        event = "cleared"
                else:
                    state.clear_count = 0
            else:
                state.clear_count = 0
                if previous == "clear":
                    state.severity = desired
                    event = "raised"
                elif ranks[desired] > ranks[previous]:
                    state.severity = desired
                    event = "escalated"
                elif ranks[desired] < ranks[previous]:
                    # A resolved current violation must never remain incorrectly labeled active.
                    state.severity = desired
                    event = "updated"
            return {"severity": state.severity, "event": event,
                    "clear_readings_remaining": (self.clear_after - state.clear_count) if state.severity != "clear" and desired == "clear" else 0,
                    "deduplicated": event == "none"}
