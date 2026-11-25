"""Reproduce published poultry references and compare a fixed 4 C baseline."""
from __future__ import annotations

import json
from datetime import datetime, timedelta

from cold_storage_ml.shelf_life import MODEL_VERSION, ShelfLifeRequest, estimate_shelf_life


def scenario(temperatures: list[float], initial: float = 2.0) -> dict:
    start = datetime(2026, 1, 1)
    return estimate_shelf_life(ShelfLifeRequest(
        category="FRESH_POULTRY_AEROBIC",
        initial_log_count=initial,
        physiological_state_alpha0=1.0,
        future_temperature_c=4.0,
        interval_minutes=1,
        history=[
            {"timestamp": (start + timedelta(minutes=i)).isoformat(), "temperature_c": t}
            for i, t in enumerate(temperatures)
        ],
    ))


def main() -> None:
    references = []
    for temperature, initial, published_days in (
        (0, 2, 25.2), (4, 2, 9.3), (8, 2, 4.8), (4, 3, 7.5), (4, 4, 5.6),
    ):
        start = datetime(2026, 1, 1)
        request = ShelfLifeRequest(
            category="FRESH_POULTRY_AEROBIC",
            initial_log_count=initial,
            physiological_state_alpha0=1.0,
            interval_minutes=1,
            history=[{"timestamp": (start + timedelta(minutes=i)).isoformat(),
                      "temperature_c": temperature} for i in range(60)],
        )
        total_days = (estimate_shelf_life(request)["remaining_shelf_life_hours"] + 1) / 24
        error = abs(total_days - published_days) / published_days
        if error > 0.05:
            raise RuntimeError(f"Published reference outside 5% tolerance at {temperature} C, N0={initial}")
        references.append({
            "temperature_c": temperature, "initial_log_count": initial,
            "published_days": published_days, "calculated_days": total_days,
            "relative_error": error,
        })
    baseline = scenario([4.0] * 1440)
    comparisons = []
    for name, temperatures in (
        ("constant_4c", [4.0] * 1440),
        ("constant_8c", [8.0] * 1440),
        ("dynamic_5_10_15c", [5.0] * 720 + [10.0] * 480 + [15.0] * 240),
    ):
        result = scenario(temperatures)
        comparisons.append({
            "scenario": name,
            "future_temperature_c": 4.0,
            "remaining_shelf_life_hours": result["remaining_shelf_life_hours"],
            "fixed_4c_baseline_remaining_hours": baseline["remaining_shelf_life_hours"],
            "difference_from_baseline_hours": (
                result["remaining_shelf_life_hours"] - baseline["remaining_shelf_life_hours"]
            ),
        })
    print(json.dumps({
        "model_version": MODEL_VERSION,
        "evaluation_type": "published_reference_reproduction_not_held_out_dataset_accuracy",
        "references": references,
        "baseline_comparisons": comparisons,
        "limitations": "Synthetic constant/dynamic histories; no beef training data used. Not a safety assessment.",
    }, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
