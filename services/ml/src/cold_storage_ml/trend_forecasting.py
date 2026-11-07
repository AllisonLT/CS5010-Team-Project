"""Damped short-term trend correction, evaluated on identical baseline pairs.

This is a simple data-driven forecast candidate, not a physical simulator:
    forecast(t+h) = temperature(t) + weight * (temperature(t) - temperature(t-lag))
Both values are observed at/before origin time. Hyperparameters are selected
on validation only; the held-out test set must remain untouched.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from cold_storage_ml.forecast_models import KEY_COLUMNS, metrics
from cold_storage_ml.residual_forecasting import residual_examples

LAGS_MINUTES = (1, 3, 5, 10, 15)
WEIGHTS = (0.0, 0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0)


def select_trend(examples: pd.DataFrame) -> tuple[dict, list]:
    """Minimize pooled validation MAE; prefer smaller corrections on ties."""
    if examples.empty:
        raise ValueError("No validation examples")
    actual = examples["observed_temperature"].to_numpy(dtype=float)
    current = examples["temperature"].to_numpy(dtype=float)
    candidates = []
    for lag in LAGS_MINUTES:
        x = examples[f"delta_temperature_{lag}"].to_numpy(dtype=float)
        for weight in WEIGHTS:
            pred = current + weight * x
            score = metrics(actual, pred)
            candidates.append({"lag_minutes": lag, "weight": weight, **score})
    selected = min(candidates, key=lambda row: (row["mae"], row["weight"], row["lag_minutes"]))
    return selected, candidates


def evaluate_trend(examples: pd.DataFrame, selected: dict) -> tuple[pd.DataFrame, dict]:
    lag = int(selected["lag_minutes"])
    weight = float(selected["weight"])
    if lag not in LAGS_MINUTES or weight not in WEIGHTS:
        raise ValueError("Unsupported trend configuration")
    if examples.empty:
        raise ValueError("No evaluation examples")
    actual = examples["observed_temperature"].to_numpy(dtype=float)
    current = examples["temperature"].to_numpy(dtype=float)
    predicted = current + weight * examples[f"delta_temperature_{lag}"].to_numpy(dtype=float)
    result = examples[KEY_COLUMNS + ["observed_temperature", "persistence_prediction"]].copy()
    result["model_name"] = "damped_trend"
    result["predicted_temperature"] = predicted
    result["absolute_error"] = np.abs(actual - predicted)
    result["squared_error"] = np.square(actual - predicted)
    report = {"model": metrics(actual, predicted), "matched_persistence": metrics(actual, current), "by_sensor": {}}
    for sensor, frame in result.groupby("sensor_id", sort=True):
        y = frame.observed_temperature.to_numpy()
        report["by_sensor"][sensor] = {"model": metrics(y, frame.predicted_temperature.to_numpy()),
                                        "matched_persistence": metrics(y, frame.persistence_prediction.to_numpy())}
    return result, report
