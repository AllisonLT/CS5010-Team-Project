"""Train residual forecasting candidates. Never access test.csv.

Run this experiment alongside existing Checkpoint 4 files; do not overwrite them.
"""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn

from cold_storage_ml.baseline import load_partition
from cold_storage_ml.forecast_models import MODEL_NAMES, SUPPORTED_HORIZONS_MINUTES, validate_splits
from cold_storage_ml.residual_forecasting import (
    RESIDUAL_FEATURES, create_residual_model, evaluate_residual, residual_examples,
)


def main():
    parser = argparse.ArgumentParser(description="Leakage-safe residual forecasting experiment")
    parser.add_argument("--train", type=Path, default=Path("data/processed/forecasting/train.csv"))
    parser.add_argument("--validation", type=Path, default=Path("data/processed/forecasting/validation.csv"))
    parser.add_argument("--output-dir", type=Path, default=Path("models/artifacts/forecasting/residual"))
    parser.add_argument("--results-dir", type=Path, default=Path("data/processed/forecasting/residual"))
    args = parser.parse_args()
    if args.train.name.lower() != "train.csv" or args.validation.name.lower() != "validation.csv":
        parser.error("Only train.csv and validation.csv may be used; held-out test.csv is excluded")
    if args.train.resolve() == args.validation.resolve():
        parser.error("Train and validation input paths must differ")
    train = load_partition(str(args.train))
    val = load_partition(str(args.validation))
    validate_splits(train, val)
    report = {
        "evaluation_partition": "validation",
        "model_family": "residual_forecasting_with_validation_selected_shrinkage",
        "selection_note": "Validation-tuned shrinkage is exploratory; report validation outcomes, do not claim unbiased test performance",
        "feature_columns": RESIDUAL_FEATURES,
        "horizons_minutes": list(SUPPORTED_HORIZONS_MINUTES),
        "package_versions": {"numpy": np.__version__, "pandas": pd.__version__, "scikit_learn": sklearn.__version__},
        "horizons": {},
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.results_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for horizon in SUPPORTED_HORIZONS_MINUTES:
        train_examples = residual_examples(train, horizon)
        val_examples = residual_examples(val, horizon)
        if train_examples.empty or val_examples.empty:
            raise ValueError(f"Insufficient valid examples for horizon {horizon}")
        # Same feature requirements across candidate models => exact matched examples.
        horizon_report = {"train_count": len(train_examples), "validation_count": len(val_examples), "models": {}}
        print(f"Horizon {horizon}min: train={len(train_examples)}, validation={len(val_examples)}", flush=True)
        for name in MODEL_NAMES:
            model = create_residual_model(name)
            model.fit(train_examples[["sensor_id", *RESIDUAL_FEATURES]], train_examples["target_delta"])
            predictions, scores = evaluate_residual(model, val_examples, name)
            rows.append(predictions)
            horizon_report["models"][name] = scores
            artifact = {
                "model": model, "model_name": name, "approach": "residual",
                "horizon_minutes": horizon, "sensor_ids": sorted(train.sensor_id.unique()),
                "feature_columns": RESIDUAL_FEATURES,
                "shrinkage_weight": scores["shrinkage_weight"],
                "prediction_formula": "current_temperature + shrinkage_weight * predicted_delta",
                "time_interval_minutes": 1,
                "trained_at_utc": datetime.now(timezone.utc).isoformat(),
            }
            joblib.dump(artifact, args.output_dir / f"residual_{name}_{horizon}min.joblib")
            print(f"  {name}: MAE={scores['model']['mae']:.5f}, "
                  f"RMSE={scores['model']['rmse']:.5f}, shrinkage={scores['shrinkage_weight']}", flush=True)
        report["horizons"][str(horizon)] = horizon_report
    predictions_path = args.results_dir / "validation_residual_predictions.csv"
    pd.concat(rows, ignore_index=True).sort_values(
        ["horizon_minutes", "model_name", "sensor_id", "origin_timestamp"]
    ).to_csv(predictions_path, index=False, float_format="%.9f")
    report_path = args.results_dir / "validation_residual_metrics.json"
    report_path.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
    print(f"Saved {predictions_path} and {report_path}")


if __name__ == "__main__":
    main()
