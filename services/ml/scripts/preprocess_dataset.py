"""Run from repository root, e.g. python services/ml/scripts/preprocess_dataset.py --inputs data/raw/SENSOR02_preprocessed.csv"""

import argparse
import json
from pathlib import Path

from cold_storage_ml.preprocessing import (
    chronological_split, data_quality_summary, prepare_sensors,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare multiple sensors for forecasting")
    parser.add_argument("--inputs", nargs="+", required=True, type=Path, help="Sensor CSV files")
    parser.add_argument("--output-dir", type=Path, default=Path("data/processed/forecasting"))
    parser.add_argument("--interval", default="1min")
    parser.add_argument("--min-coverage", type=float, default=0.75)
    args = parser.parse_args()

    frame = prepare_sensors(args.inputs, interval=args.interval, min_coverage=args.min_coverage)
    parts, boundaries = chronological_split(frame)
    summary = data_quality_summary(frame, parts, boundaries, args.interval, args.min_coverage)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    frame.to_csv(args.output_dir / "all_sensors.csv", index=False, float_format="%.6f")
    for name, part in parts.items():
        part.to_csv(args.output_dir / f"{name}.csv", index=False, float_format="%.6f")
    (args.output_dir / "preprocessing_report.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    print(f"\nSaved outputs to {args.output_dir.resolve()}")


if __name__ == "__main__":
    main()
