"""Forecast future temperature *maximum* and warn about threshold crossings.

This is a research candidate: a high-quantile future-peak regression gives an
adjustable numeric threshold at inference time. No knowledge of a future event is
used to build origin features. A crossing is defined as current temperature <
threshold and any future 1-minute observation at/above threshold by t+h.

All future minutes must be observed to score an example. For real-time inference,
features can be generated from history only; future labels are training-only.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from cold_storage_ml.baseline import SUPPORTED_HORIZONS_MINUTES, validate_partition
from cold_storage_ml.forecast_models import FEATURE_COLUMNS, features_for_partition

DEFAULT_QUANTILE = 0.90
PREDICTOR_COLUMNS = ["sensor_id", *FEATURE_COLUMNS]


def peak_examples(partition: pd.DataFrame, horizon_minutes: int) -> pd.DataFrame:
    """Use only complete 60-min history and complete future t+1..t+h targets.

    Never crosses a partition: the caller passes train or validation separately.
    A full minute grid and min_periods=h exclude both missing rows and NaN bins.
    """
    if type(horizon_minutes) is not int or horizon_minutes not in SUPPORTED_HORIZONS_MINUTES:
        raise ValueError(f"Unsupported horizon: {horizon_minutes}")
    data = validate_partition(partition)
    feat = features_for_partition(data)
    labeled = []
    for sensor_id, g in data.groupby("sensor_id", sort=True):
        g = g.set_index("timestamp").sort_index()
        idx = pd.date_range(g.index.min(), g.index.max(), freq="1min")
        temp = g.reindex(idx)["temperature"]
        # After shift(-1), reverse rolling covers strictly future t+1..t+h.
        future = temp.shift(-1).iloc[::-1]
        max_future = future.rolling(horizon_minutes, min_periods=horizon_minutes).max().iloc[::-1]
        part = pd.DataFrame({
            "sensor_id": sensor_id,
            "origin_timestamp": idx,
            "future_peak_temperature": max_future.to_numpy(),
        })
        labeled.append(part)
    labels = pd.concat(labeled, ignore_index=True)
    joined = feat.merge(labels, on=["sensor_id", "origin_timestamp"], validate="one_to_one")
    joined = joined.loc[joined.complete_history].dropna(
        subset=[*FEATURE_COLUMNS, "future_peak_temperature"]
    ).copy()
    joined["horizon_minutes"] = horizon_minutes
    joined["window_end_timestamp"] = joined.origin_timestamp + pd.Timedelta(minutes=horizon_minutes)
    return joined.sort_values(["sensor_id", "origin_timestamp"]).reset_index(drop=True)


def create_peak_model(quantile: float = DEFAULT_QUANTILE) -> Pipeline:
    if not 0.5 < quantile < 1.0:
        raise ValueError("Quantile must be strictly between 0.5 and 1")
    preprocess = ColumnTransformer([
        ("sensor", OneHotEncoder(handle_unknown="ignore"), ["sensor_id"]),
        ("numeric", StandardScaler(), FEATURE_COLUMNS),
    ], sparse_threshold=0)
    regressor = HistGradientBoostingRegressor(
        loss="quantile", quantile=quantile, max_iter=120, learning_rate=0.05,
        max_leaf_nodes=15, min_samples_leaf=30, l2_regularization=10.0,
        random_state=42,
    )
    return Pipeline([("preprocess", preprocess), ("regressor", regressor)])


def warning_predictions(model: Pipeline, examples: pd.DataFrame, threshold: float) -> pd.DataFrame:
    """Predict an upper estimate of the future maximum; flags are risk warnings.

    Past readings already >= threshold are marked as active violations, not as
    newly predicted crossings. Warning metrics concern ONLY currently-below cases.
    """
    if not np.isfinite(threshold):
        raise ValueError("Threshold must be a finite numeric value")
    if examples.empty:
        raise ValueError("No complete-history and complete-future examples")
    estimate = np.asarray(model.predict(examples[PREDICTOR_COLUMNS]), dtype=float)
    if not np.isfinite(estimate).all():
        raise ValueError("Predicted peaks must be finite")
    result = examples[["sensor_id", "horizon_minutes", "origin_timestamp", "window_end_timestamp",
                       "temperature", "future_peak_temperature"]].copy()
    result = result.rename(columns={"temperature": "current_temperature"})
    result["predicted_peak_temperature"] = estimate
    result["threshold"] = float(threshold)
    result["currently_above_threshold"] = result.current_temperature >= threshold
    result["actual_future_crossing"] = (
        (result.current_temperature < threshold) & (result.future_peak_temperature >= threshold)
    )
    result["predicted_future_crossing"] = (
        (result.current_temperature < threshold) & (result.predicted_peak_temperature >= threshold)
    )
    result["persistence_future_crossing"] = False  # Current below stays below under persistence.
    return result.reset_index(drop=True)


def crossing_metrics(predictions: pd.DataFrame) -> dict:
    """Binary-event precision, recall, F1, event frequency and false alarms.

    Currently-above-threshold rows are excluded: their status is already known,
    not a future-onset prediction. Null rather than fabricated metrics if no
    positives/predicted positives exist.
    """
    at_risk = predictions.loc[~predictions.currently_above_threshold]
    actual = at_risk.actual_future_crossing.astype(bool).to_numpy()
    predicted = at_risk.predicted_future_crossing.astype(bool).to_numpy()
    tp = int((actual & predicted).sum())
    fp = int((~actual & predicted).sum())
    fn = int((actual & ~predicted).sum())
    tn = int((~actual & ~predicted).sum())
    precision = tp / (tp + fp) if tp + fp else None
    recall = tp / (tp + fn) if tp + fn else None
    f1 = 2 * precision * recall / (precision + recall) if precision is not None and recall is not None and precision + recall else None
    return {"at_risk_origins": int(len(at_risk)), "already_above_threshold": int(predictions.currently_above_threshold.sum()),
            "actual_future_crossings": int(actual.sum()), "predicted_warnings": int(predicted.sum()),
            "tp": tp, "fp": fp, "fn": fn, "tn": tn,
            "precision": precision, "recall": recall, "f1": f1,
            "false_alarm_rate": fp / (fp + tn) if fp + tn else None,
            "persistence_recall": 0.0 if int(actual.sum()) else None}
