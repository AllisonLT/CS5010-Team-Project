"""Train future-peak risk models using TRAIN, evaluate on untouched VALIDATION.

Run at repository root. No test split is read. The numeric warning threshold is
runtime configurable; it is NOT learned from future data or tuned on validation.
"""
import argparse
import json
from pathlib import Path

import joblib
import pandas as pd

from cold_storage_ml.baseline import load_partition, SUPPORTED_HORIZONS_MINUTES
from cold_storage_ml.forecast_models import validate_splits
from cold_storage_ml.threshold_warning import (
    DEFAULT_QUANTILE, PREDICTOR_COLUMNS, create_peak_model, peak_examples,
    warning_predictions, crossing_metrics,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Experimental future temperature threshold warning evaluation")
    parser.add_argument("--train", type=Path, default=Path("data/processed/forecasting/train.csv"))
    parser.add_argument("--validation", type=Path, default=Path("data/processed/forecasting/validation.csv"))
    parser.add_argument("--threshold", type=float, required=True,
                        help="Temperature threshold in source CSV units (confirm unit before operational use)")
    parser.add_argument("--result-dir", type=Path, default=Path("data/processed/forecasting/warnings"))
    parser.add_argument("--artifact-dir", type=Path, default=Path("models/artifacts/forecasting/warnings"))
    args = parser.parse_args()
    if args.train.name.lower() != "train.csv" or args.validation.name.lower() != "validation.csv":
        parser.error("Use train.csv and validation.csv only; never evaluate test.csv here")
    train = load_partition(str(args.train))
    validation = load_partition(str(args.validation))
    validate_splits(train, validation)
    args.result_dir.mkdir(parents=True, exist_ok=True)
    args.artifact_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "definition": "Crossing: currently below threshold, any future complete 1-minute temperature >= threshold in (t, t+h]",
        "evaluation_partition": "validation", "quantile": DEFAULT_QUANTILE,
        "threshold": args.threshold, "unit": "same as CSV, not yet confirmed",
        "interpretation": "Upper-quantile future maximum estimate, not calibrated crossing probability or guaranteed warning",
        "horizons": {},
    }
    outputs = []
    for horizon in SUPPORTED_HORIZONS_MINUTES:
        training = peak_examples(train, horizon)
        evaluation = peak_examples(validation, horizon)
        if training.empty or evaluation.empty:
            raise ValueError(f"Insufficient complete history and future coverage for {horizon} minutes")
        model = create_peak_model()
        model.fit(training[PREDICTOR_COLUMNS], training.future_peak_temperature)
        pred = warning_predictions(model, evaluation, args.threshold)
        outputs.append(pred)
        metrics = {"overall": crossing_metrics(pred), "per_sensor": {}}
        for sensor, part in pred.groupby("sensor_id", sort=True):
            metrics["per_sensor"][sensor] = crossing_metrics(part)
        metrics["training_examples"] = int(len(training))
        metrics["validation_examples"] = int(len(evaluation))
        report["horizons"][str(horizon)] = metrics
        joblib.dump({"model": model, "horizon_minutes": horizon,
                     "quantile": DEFAULT_QUANTILE, "feature_columns": PREDICTOR_COLUMNS,
                     "method": "future_peak_quantile"}, args.artifact_dir / f"peak_quantile_{horizon}min.joblib")
        m = metrics["overall"]
        print(f"{horizon}min: actual crossings={m['actual_future_crossings']}, "
              f"warnings={m['predicted_warnings']}, precision={m['precision']}, "
              f"recall={m['recall']}", flush=True)
    tag = f"threshold_{args.threshold:g}".replace("-", "neg").replace(".", "p")
    pd.concat(outputs, ignore_index=True).to_csv(args.result_dir / f"{tag}_predictions.csv", index=False)
    (args.result_dir / f"{tag}_metrics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Wrote warnings to {args.result_dir}")


if __name__ == "__main__":
    main()
