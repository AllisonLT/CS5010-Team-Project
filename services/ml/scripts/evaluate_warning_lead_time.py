"""Analyze warning lead times without retraining or opening the held-out test set."""
import argparse
import json
from pathlib import Path

import pandas as pd
from cold_storage_ml.warning_lead_time import evaluate_lead_time


def main():
    parser = argparse.ArgumentParser(description="Incident-level threshold warning lead-time evaluation")
    parser.add_argument("--validation", type=Path, default=Path("data/processed/forecasting/validation.csv"))
    parser.add_argument("--predictions", type=Path, default=Path("data/processed/forecasting/warnings/threshold_10_predictions.csv"))
    parser.add_argument("--threshold", type=float, default=10.0)
    parser.add_argument("--output-dir", type=Path, default=Path("data/processed/forecasting/warnings/lead_time"))
    args = parser.parse_args()
    if args.validation.name.lower() != "validation.csv":
        parser.error("Use validation.csv only, never the held-out test.csv")
    incidents, warnings, metrics = evaluate_lead_time(
        pd.read_csv(args.validation), pd.read_csv(args.predictions), args.threshold)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    incidents.to_csv(args.output_dir / "warning_incidents.csv", index=False)
    warnings.to_csv(args.output_dir / "warning_window_matches.csv", index=False)
    (args.output_dir / "warning_incident_metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    for h, info in metrics["horizons"].items():
        a = info["overall"]
        print(f"{h}min: incidents detected {a['detected_incidents']}/{a['scorable_incidents']}, "
              f"recall={a['incident_recall']}, median lead={a['median_lead_minutes']}min, "
              f"unmatched warning episodes={a['unmatched_warning_episodes']}")
    print(f"Wrote report to {args.output_dir}")


if __name__ == "__main__":
    main()
