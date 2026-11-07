"""Checkpoint 4 experiment: leakage-safe residual temperature forecasting.

For each horizon, predict the *change* relative to the most recent observed
measurement, then optionally shrink that change toward persistence. The shrinkage
is selected using validation MAE; final held-out test data is never read here.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingRegressor, RandomForestRegressor
from sklearn.linear_model import Ridge
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from cold_storage_ml.forecast_models import (
    FEATURE_COLUMNS, KEY_COLUMNS, MODEL_NAMES, SUPPORTED_HORIZONS_MINUTES,
    features_for_partition, metrics, supervised_examples, validate_splits,
)

# Extra features only depend on readings at or before the forecast origin.
EXTRA_FEATURES = [
    "delta_temperature_1", "delta_temperature_2", "delta_temperature_3",
    "delta_temperature_10", "temperature_std_5", "temperature_std_15",
    "temperature_slope_5", "temperature_slope_15",
]
RESIDUAL_FEATURES = FEATURE_COLUMNS + EXTRA_FEATURES
SHRINKAGE_VALUES = (0.0, 0.1, 0.25, 0.5, 0.75, 1.0)


def residual_examples(partition: pd.DataFrame, horizon_minutes: int) -> pd.DataFrame:
    """Build aligned examples with full past history and exact future targets.

    Uses the original strict 60-minute guard, plus extra temperature-derived
    features. Never joins targets across sensors or split boundaries.
    """
    base = supervised_examples(partition, horizon_minutes)
    if base.empty:
        return base.assign(**{c: pd.Series(dtype=float) for c in EXTRA_FEATURES})
    past = features_for_partition(partition)[["sensor_id", "origin_timestamp", *FEATURE_COLUMNS]]
    # Compute extra features on a complete per-sensor minute grid, not row offsets.
    additional = []
    for sensor, chunk in past.groupby("sensor_id", sort=True):
        chunk = chunk.sort_values("origin_timestamp").copy()
        # features_for_partition reindexes each sensor to the full 1-minute grid.
        temp = chunk["temperature"]
        for n in (1, 2, 3, 10):
            chunk[f"delta_temperature_{n}"] = temp - temp.shift(n)
        for n in (5, 15):
            chunk[f"temperature_std_{n}"] = temp.rolling(n, min_periods=n).std(ddof=0)
            # Endpoint slope, degrees/minute, over a trailing n-minute interval.
            chunk[f"temperature_slope_{n}"] = (temp - temp.shift(n - 1)) / (n - 1)
        additional.append(chunk[["sensor_id", "origin_timestamp", *EXTRA_FEATURES]])
    extra = pd.concat(additional, ignore_index=True)
    result = base.merge(extra, on=["sensor_id", "origin_timestamp"], validate="one_to_one", how="left")
    result = result.dropna(subset=EXTRA_FEATURES).copy()
    result["target_delta"] = result["observed_temperature"] - result["temperature"]
    return result.sort_values(["sensor_id", "origin_timestamp"]).reset_index(drop=True)


def create_residual_model(model_name: str, random_state: int = 42) -> Pipeline:
    if model_name not in MODEL_NAMES:
        raise ValueError(f"Unsupported model: {model_name}")
    prep = ColumnTransformer([
        ("sensor", OneHotEncoder(handle_unknown="ignore"), ["sensor_id"]),
        ("numerical", StandardScaler(), RESIDUAL_FEATURES),
    ], sparse_threshold=0)
    if model_name == "ridge":
        estimator = Ridge(alpha=100.0)
    elif model_name == "random_forest":
        estimator = RandomForestRegressor(
            n_estimators=100, min_samples_leaf=10, max_features=0.8,
            random_state=random_state, n_jobs=-1,
        )
    else:
        estimator = HistGradientBoostingRegressor(
            max_iter=120, learning_rate=0.05, max_leaf_nodes=15,
            min_samples_leaf=30, l2_regularization=10.0,
            random_state=random_state,
        )
    return Pipeline([("preprocess", prep), ("regressor", estimator)])


def select_shrinkage(actual: np.ndarray, current: np.ndarray, predicted_delta: np.ndarray) -> tuple[float, dict]:
    """Select correction strength by validation MAE (ties prefer simpler 0)."""
    actual, current, predicted_delta = (np.asarray(x, dtype=float) for x in (actual, current, predicted_delta))
    if not (actual.shape == current.shape == predicted_delta.shape) or actual.size == 0:
        raise ValueError("Need nonempty, same-shaped arrays")
    if not all(np.isfinite(x).all() for x in (actual, current, predicted_delta)):
        raise ValueError("Nonfinite values in shrinkage selection")
    scores = {str(weight): metrics(actual, current + weight * predicted_delta) for weight in SHRINKAGE_VALUES}
    best = min(SHRINKAGE_VALUES, key=lambda w: (scores[str(w)]["mae"], w))
    return best, scores


def evaluate_residual(model: Pipeline, examples: pd.DataFrame, name: str) -> tuple[pd.DataFrame, dict]:
    delta = np.asarray(model.predict(examples[["sensor_id", *RESIDUAL_FEATURES]]), dtype=float)
    actual = examples["observed_temperature"].to_numpy(dtype=float)
    current = examples["temperature"].to_numpy(dtype=float)
    weight, sweep = select_shrinkage(actual, current, delta)
    predicted = current + weight * delta
    result = examples[KEY_COLUMNS + ["observed_temperature", "persistence_prediction"]].copy()
    result["model_name"] = name
    result["predicted_delta"] = delta
    result["shrinkage_weight"] = weight
    result["predicted_temperature"] = predicted
    result["absolute_error"] = np.abs(actual - predicted)
    result["squared_error"] = (actual - predicted) ** 2
    report = {
        "model": metrics(actual, predicted),
        "unshrunk_model": metrics(actual, current + delta),
        "matched_persistence": metrics(actual, current),
        "shrinkage_weight": weight,
        "validation_shrinkage_sweep": sweep,
        "by_sensor": {},
    }
    for sensor_id, frame in result.groupby("sensor_id", sort=True):
        y = frame["observed_temperature"].to_numpy()
        report["by_sensor"][sensor_id] = {
            "model": metrics(y, frame["predicted_temperature"].to_numpy()),
            "matched_persistence": metrics(y, frame["persistence_prediction"].to_numpy()),
        }
    return result, report
