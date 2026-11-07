"""Leakage-safe, global multi-sensor direct-horizon forecasting.

Only work with one pre-split partition at a time. All features are trailing,
per-sensor, and observed at/before the forecast origin. Targets are joined by
exact timestamp and remain in the supplied partition.
"""
from __future__ import annotations

from collections.abc import Sequence

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingRegressor, RandomForestRegressor
from sklearn.linear_model import Ridge
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from cold_storage_ml.baseline import SUPPORTED_HORIZONS_MINUTES, validate_partition

LAGS = (1, 5, 15, 30, 60)
WINDOWS = (5, 15, 30)
FEATURE_COLUMNS = (
    ["temperature"]
    + [f"temperature_lag_{n}" for n in LAGS]
    + [f"temperature_mean_{n}" for n in WINDOWS]
    + [f"temperature_min_{n}" for n in WINDOWS]
    + [f"temperature_max_{n}" for n in WINDOWS]
    + ["delta_temperature_5", "delta_temperature_15", "humidity", "humidity_lag_5"]
)
MODEL_NAMES = ("ridge", "random_forest", "hist_gradient_boosting")
KEY_COLUMNS = ["sensor_id", "horizon_minutes", "origin_timestamp", "target_timestamp"]


def features_for_partition(partition: pd.DataFrame) -> pd.DataFrame:
    """Generate trailing features from one split, never across a sensor or split.

    Shift and rolling operations are performed after alignment to a full 1-minute
    grid, so absent timestamps cannot turn a 5-row shift into a 5-minute lag.
    Rolling windows require all minutes present and valid. Humidity missingness
    also excludes a row under this intentionally conservative first version.
    """
    data = validate_partition(partition)
    output = []
    for sensor_id, sensor in data.groupby("sensor_id", sort=True):
        sensor = sensor.set_index("timestamp").sort_index()
        full_idx = pd.date_range(sensor.index.min(), sensor.index.max(), freq="1min")
        sensor = sensor.reindex(full_idx)
        sensor.index.name = "origin_timestamp"
        sensor["sensor_id"] = sensor_id
        temp = sensor["temperature"]
        humidity = pd.to_numeric(sensor.get("humidity"), errors="coerce") if "humidity" in sensor else pd.Series(np.nan, index=full_idx)
        sensor["humidity"] = humidity
        # Keep features only when the entire preceding 60 minutes is observed.
        # Without this guard a missing interval could fall between selected lags.
        sensor["complete_history"] = (
            temp.notna().rolling(61, min_periods=61).sum().eq(61)
            & humidity.notna().rolling(61, min_periods=61).sum().eq(61)
        )
        for lag in LAGS:
            sensor[f"temperature_lag_{lag}"] = temp.shift(lag)
        for window in WINDOWS:
            # A trailing window INCLUDING the current reading (never centered).
            trailing = temp.rolling(window=window, min_periods=window)
            sensor[f"temperature_mean_{window}"] = trailing.mean()
            sensor[f"temperature_min_{window}"] = trailing.min()
            sensor[f"temperature_max_{window}"] = trailing.max()
        sensor["delta_temperature_5"] = temp - sensor["temperature_lag_5"]
        sensor["delta_temperature_15"] = temp - sensor["temperature_lag_15"]
        sensor["humidity_lag_5"] = humidity.shift(5)
        output.append(sensor.reset_index()[["sensor_id", "origin_timestamp", "complete_history", *FEATURE_COLUMNS]])
    return pd.concat(output, ignore_index=True)


def supervised_examples(partition: pd.DataFrame, horizon_minutes: int) -> pd.DataFrame:
    """Return examples with exact t+h targets from the SAME supplied partition."""
    if type(horizon_minutes) is not int or horizon_minutes not in SUPPORTED_HORIZONS_MINUTES:
        raise ValueError(f"Horizon must be one of {SUPPORTED_HORIZONS_MINUTES}")
    data = validate_partition(partition)
    features = features_for_partition(data)
    features["horizon_minutes"] = horizon_minutes
    features["target_timestamp"] = features["origin_timestamp"] + pd.Timedelta(minutes=horizon_minutes)
    targets = data[["sensor_id", "timestamp", "temperature"]].rename(
        columns={"timestamp": "target_timestamp", "temperature": "observed_temperature"}
    )
    merged = features.merge(targets, on=["sensor_id", "target_timestamp"], how="inner", validate="one_to_one")
    merged = merged.loc[merged["complete_history"]].dropna(subset=[*FEATURE_COLUMNS, "observed_temperature"]).copy()
    merged["persistence_prediction"] = merged["temperature"]
    return merged.sort_values(["sensor_id", "origin_timestamp"]).reset_index(drop=True)


def create_model(model_name: str, random_state: int = 42) -> Pipeline:
    """Build a common feature pipeline, fitted solely on training examples."""
    if model_name not in MODEL_NAMES:
        raise ValueError(f"Unknown model {model_name!r}; choose from {MODEL_NAMES}")
    # StandardScaler is useful for Ridge and harmless for tree learners.
    preprocessor = ColumnTransformer(
        transformers=[
            ("sensor", OneHotEncoder(handle_unknown="ignore"), ["sensor_id"]),
            ("numeric", StandardScaler(), FEATURE_COLUMNS),
        ],
        remainder="drop",
        sparse_threshold=0,  # Dense output supports all three estimators.
    )
    if model_name == "ridge":
        estimator = Ridge(alpha=10.0)
    elif model_name == "random_forest":
        estimator = RandomForestRegressor(
            n_estimators=100, min_samples_leaf=5, max_features=0.8,
            random_state=random_state, n_jobs=-1,
        )
    else:
        estimator = HistGradientBoostingRegressor(
            max_iter=120, learning_rate=0.07, max_leaf_nodes=15,
            min_samples_leaf=20, random_state=random_state,
        )
    return Pipeline([("preprocess", preprocessor), ("regressor", estimator)])


def metrics(observed: np.ndarray, predicted: np.ndarray) -> dict:
    observed = np.asarray(observed, dtype=float)
    predicted = np.asarray(predicted, dtype=float)
    if observed.shape != predicted.shape:
        raise ValueError("Observed and predicted arrays must have matching shape")
    if observed.size == 0:
        return {"count": 0, "mae": None, "rmse": None}
    if not (np.isfinite(observed).all() and np.isfinite(predicted).all()):
        raise ValueError("Metric inputs must be finite")
    residual = observed - predicted
    return {"count": int(observed.size), "mae": float(np.abs(residual).mean()),
            "rmse": float(np.sqrt(np.mean(np.square(residual))))}


def evaluate_one_model(model: Pipeline, examples: pd.DataFrame, model_name: str) -> tuple[pd.DataFrame, dict]:
    """Score model and matched persistence against identical valid examples."""
    pred = model.predict(examples[["sensor_id", *FEATURE_COLUMNS]])
    result = examples[KEY_COLUMNS + ["observed_temperature", "persistence_prediction"]].copy()
    result["model_name"] = model_name
    result["predicted_temperature"] = pred
    actual = result["observed_temperature"].to_numpy()
    result["absolute_error"] = np.abs(actual - pred)
    result["squared_error"] = np.square(actual - pred)
    report = {"model": metrics(actual, pred),
              "matched_persistence": metrics(actual, result["persistence_prediction"].to_numpy()),
              "by_sensor": {}}
    for sensor_id, group in result.groupby("sensor_id", sort=True):
        y = group["observed_temperature"].to_numpy()
        report["by_sensor"][sensor_id] = {
            "model": metrics(y, group["predicted_temperature"].to_numpy()),
            "matched_persistence": metrics(y, group["persistence_prediction"].to_numpy()),
        }
    return result, report


def validate_splits(train: pd.DataFrame, validation: pd.DataFrame) -> None:
    """Require distinct chronological partitions before any training."""
    train_data = validate_partition(train)
    val_data = validate_partition(validation)
    if set(train_data.sensor_id) != set(val_data.sensor_id):
        raise ValueError("Training and validation sensor sets differ")
    if train_data.timestamp.max() >= val_data.timestamp.min():
        raise ValueError("Train and validation timestamps overlap or are out of order")
