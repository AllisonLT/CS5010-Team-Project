"""Train three global model families for 15/30/60-minute direct forecasting.

Reads train.csv and validation.csv ONLY. Never reads the held-out test.csv.
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn

from cold_storage_ml.baseline import load_partition
from cold_storage_ml.forecast_models import (
    FEATURE_COLUMNS, MODEL_NAMES, SUPPORTED_HORIZONS_MINUTES,
    create_model, evaluate_one_model, supervised_examples, validate_splits,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Train multi-sensor direct-horizon forecasting models")
    parser.add_argument("--train", type=Path, default=Path("data/processed/forecasting/train.csv"))
    parser.add_argument("--validation", type=Path, default=Path("data/processed/forecasting/validation.csv"))
    parser.add_argument("--output-dir", type=Path, default=Path("models/artifacts/forecasting"))
    parser.add_argument("--results-dir", type=Path, default=Path("data/processed/forecasting/ml"))
    args = parser.parse_args()
    if args.train.name.lower() != "train.csv" or args.validation.name.lower() != "validation.csv":
        parser.error("Only train.csv and validation.csv are permitted (test.csv is held out)")
    if args.train.resolve() == args.validation.resolve():
        parser.error("Train and validation files must differ")

    train, val = load_partition(str(args.train)), load_partition(str(args.validation))
    validate_splits(train, val)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.results_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "evaluation_partition": "validation", "train_file": str(args.train),
        "validation_file": str(args.validation), "model_families": list(MODEL_NAMES),
        "horizons_minutes": list(SUPPORTED_HORIZONS_MINUTES),
        "temperature_unit": "source CSV unit (not yet confirmed)",
        "selection_rule": "lowest pooled validation MAE, then RMSE",
        "leakage_policy": "input features and targets entirely inside each partition; per-sensor lagging; strict complete features",
        "feature_columns": FEATURE_COLUMNS,
        "package_versions": {"pandas": pd.__version__, "numpy": np.__version__, "scikit_learn": sklearn.__version__, "joblib": joblib.__version__},
        "horizons": {},
    }
    all_results = []
    for horizon in SUPPORTED_HORIZONS_MINUTES:
        tr = supervised_examples(train, horizon)
        va = supervised_examples(val, horizon)
        if tr.empty or va.empty:
            raise ValueError(f"Not enough complete training/validation examples for {horizon} minutes")
        xtrain = tr[["sensor_id", *FEATURE_COLUMNS]]
        ytrain = tr["observed_temperature"]
        results = {}
        print(f"Horizon {horizon}: {len(tr)} train, {len(va)} validation examples", flush=True)
        for name in MODEL_NAMES:
            model = create_model(name)
            model.fit(xtrain, ytrain)
            predictions, scores = evaluate_one_model(model, va, name)
            scores["train_count"] = len(tr)
            scores["validation_count"] = len(va)
            results[name] = scores
            all_results.append(predictions)
            artifact_path = args.output_dir / f"{name}_{horizon}min.joblib"
            artifact = {
                "model": model,
                "model_name": name,
                "horizon_minutes": horizon,
                "sensor_ids": sorted(train.sensor_id.unique().tolist()),
                "feature_columns": FEATURE_COLUMNS,
                "time_interval_minutes": 1,
                "feature_policy": "complete trailing history within the selected data partition",
                "trained_at_utc": datetime.now(timezone.utc).isoformat(),
            }
            joblib.dump(artifact, artifact_path)
            print(f"  {name}: MAE={scores['model']['mae']:.5f}, RMSE={scores['model']['rmse']:.5f}", flush=True)
        best = min(MODEL_NAMES, key=lambda name: (
            results[name]["model"]["mae"], results[name]["model"]["rmse"]
        ))
        best_mae = results[best]["model"]["mae"]
        persistence_mae = results[best]["matched_persistence"]["mae"]
        recommended_method = best if best_mae < persistence_mae else "persistence"
        report["horizons"][str(horizon)] = {
            "models": results, "best_ml_model": best,
            "recommended_method_by_validation_mae": recommended_method,
        }
        print(f"  Best ML: {best}; recommended by MAE: {recommended_method}", flush=True)
    predictions_csv = args.results_dir / "validation_ml_predictions.csv"
    pd.concat(all_results, ignore_index=True).sort_values(
        ["horizon_minutes", "model_name", "sensor_id", "origin_timestamp"]
    ).to_csv(predictions_csv, index=False, float_format="%.9f")
    metrics_json = args.results_dir / "validation_ml_metrics.json"
    metrics_json.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")
    print(f"Saved validation metrics: {metrics_json.resolve()}")
    print(f"Saved model artifacts: {args.output_dir.resolve()}")


if __name__ == "__main__":
    main()
