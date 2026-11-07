"""Evaluate persistence on validation.csv only (hold out test.csv)."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from cold_storage_ml.baseline import (
    SUPPORTED_HORIZONS_MINUTES,
    evaluate_predictions,
    load_partition,
    persistence_predictions,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate multi-sensor persistence forecasting")
    parser.add_argument(
        "--validation", type=Path,
        default=Path("data/processed/forecasting/validation.csv"),
        help="Checkpoint 2 validation partition (do not supply test.csv)",
    )
    parser.add_argument(
        "--output-dir", type=Path,
        default=Path("data/processed/forecasting/baseline"),
    )
    parser.add_argument(
        "--horizons", type=int, nargs="+", default=list(SUPPORTED_HORIZONS_MINUTES),
    )
    args = parser.parse_args()
    if args.validation.name.lower() != "validation.csv":
        parser.error("For Checkpoint 3, --validation must point to validation.csv (not train/test)")

    frame = load_partition(str(args.validation))
    predictions = persistence_predictions(frame, args.horizons)
    summary = evaluate_predictions(predictions, frame["sensor_id"].unique(), args.horizons)
    report = {
        "method": "persistence (last observed temperature)",
        "evaluation_partition": "validation",
        "input_file": str(args.validation),
        "horizons_minutes": list(args.horizons),
        "temperature_unit": "same as source CSV (unit unconfirmed)",
        "missing_pair_policy": "skip pair if origin/target temperature missing or target timestamp absent",
        "split_policy": "origin and target both inside validation.csv; no train/test rows used",
        "forecast_count": len(predictions),
        **summary,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    predictions.to_csv(args.output_dir / "validation_predictions.csv", index=False, float_format="%.6f")
    (args.output_dir / "validation_metrics.json").write_text(
        json.dumps(report, indent=2, allow_nan=False), encoding="utf-8"
    )
    print(json.dumps(report, indent=2, allow_nan=False))
    print(f"Saved baseline results to {args.output_dir.resolve()}")


if __name__ == "__main__":
    main()
