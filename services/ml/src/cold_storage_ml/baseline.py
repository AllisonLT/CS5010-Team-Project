"""Persistence forecasting and leakage-safe evaluation for minute-level sensor data.

No model fitting occurs here. The caller supplies one chronological partition
(e.g. validation.csv); prediction origins and targets stay inside it.
"""

from __future__ import annotations

from collections.abc import Sequence

import numpy as np
import pandas as pd

SUPPORTED_HORIZONS_MINUTES = (15, 30, 60)
REQUIRED_COLUMNS = {"timestamp", "sensor_id", "temperature"}
PREDICTION_COLUMNS = [
    "sensor_id", "horizon_minutes", "origin_timestamp", "target_timestamp",
    "observed_temperature", "predicted_temperature", "absolute_error", "squared_error",
]


def load_partition(path: str) -> pd.DataFrame:
    """Read a Checkpoint 2 processed CSV and enforce the baseline's assumptions."""
    frame = pd.read_csv(path)
    return validate_partition(frame)


def validate_partition(frame: pd.DataFrame) -> pd.DataFrame:
    """Validate timestamps, minute cadence and uniqueness; do not fill missing data.

    Gaps represented by absent timestamp rows are permitted; a forecast is never
    inferred for a timestamp not explicitly present in the source partition.
    """
    if not REQUIRED_COLUMNS.issubset(frame.columns):
        raise ValueError(f"Missing required columns: {sorted(REQUIRED_COLUMNS - set(frame.columns))}")
    if frame.empty:
        raise ValueError("The evaluation partition is empty")

    data = frame.copy()
    data["timestamp"] = pd.to_datetime(data["timestamp"], errors="coerce")
    if data["timestamp"].isna().any():
        raise ValueError("Invalid or missing timestamps")
    if data["timestamp"].dt.tz is not None:
        raise ValueError("Expected timezone-naive timestamps from Checkpoint 2")
    if data["sensor_id"].isna().any() or (data["sensor_id"].astype(str).str.strip() == "").any():
        raise ValueError("Missing or empty sensor IDs")
    if data.duplicated(["sensor_id", "timestamp"]).any():
        raise ValueError("Duplicate (sensor_id, timestamp) pairs")
    data["temperature"] = pd.to_numeric(data["temperature"], errors="coerce")
    if np.isinf(data["temperature"].to_numpy(dtype=float)).any():
        raise ValueError("Infinite temperature readings are invalid")
    # The supported source is 1-minute right-labeled resampling. A malformed
    # partition is rejected instead of silently treating row offsets as minutes.
    if ((data["timestamp"].dt.second != 0) | (data["timestamp"].dt.microsecond != 0)).any():
        raise ValueError("Expected timestamps aligned to whole minutes")
    return data.sort_values(["sensor_id", "timestamp"]).reset_index(drop=True)


def persistence_predictions(
    partition: pd.DataFrame,
    horizons_minutes: Sequence[int] = SUPPORTED_HORIZONS_MINUTES,
) -> pd.DataFrame:
    """Build forecast/observation pairs within *only this* partition.

    For each valid origin time t, prediction for t+h is temperature at t.
    Exact timestamp joins prevent row-shift errors across missing timestamp rows.
    Missing origin or target temperatures are excluded from metric calculation.
    """
    data = validate_partition(partition)
    horizons = tuple(horizons_minutes)
    if not horizons or any(type(h) is not int or h <= 0 for h in horizons):
        raise ValueError("Horizons must be a nonempty sequence of positive integer minutes")
    if len(set(horizons)) != len(horizons):
        raise ValueError("Duplicate forecast horizons")
    if any(h not in SUPPORTED_HORIZONS_MINUTES for h in horizons):
        raise ValueError(f"Unsupported horizon; choose from {SUPPORTED_HORIZONS_MINUTES}")

    results = []
    for sensor_id, sensor in data.groupby("sensor_id", sort=True):
        source = sensor[["timestamp", "temperature"]].copy()
        for horizon in horizons:
            # A timestamp shifted backward by h is a forecast origin whose
            # target is the original observation timestamp.
            origins = source.rename(columns={
                "timestamp": "origin_timestamp",
                "temperature": "predicted_temperature",
            })
            origins["target_timestamp"] = origins["origin_timestamp"] + pd.Timedelta(minutes=horizon)
            targets = source.rename(columns={
                "timestamp": "target_timestamp",
                "temperature": "observed_temperature",
            })
            joined = origins.merge(targets, on="target_timestamp", how="inner", validate="one_to_one")
            joined = joined.dropna(subset=["predicted_temperature", "observed_temperature"]).copy()
            if joined.empty:
                continue
            joined.insert(0, "sensor_id", sensor_id)
            joined.insert(1, "horizon_minutes", horizon)
            delta = joined["observed_temperature"] - joined["predicted_temperature"]
            joined["absolute_error"] = delta.abs()
            joined["squared_error"] = delta.pow(2)
            results.append(joined[PREDICTION_COLUMNS])

    if not results:
        return pd.DataFrame(columns=PREDICTION_COLUMNS)
    return (pd.concat(results, ignore_index=True)
            .sort_values(["horizon_minutes", "sensor_id", "origin_timestamp"])
            .reset_index(drop=True))


def evaluate_predictions(predictions: pd.DataFrame, sensor_ids: Sequence[str],
                         horizons_minutes: Sequence[int]) -> dict:
    """Validation MAE/RMSE by sensor/horizon and pooled by horizon.

    Missing pairs have count 0 and null metrics. Pooled metrics are micro-averaged
    across all evaluated forecast/target pairs, not the mean of sensor-level MAEs.
    """
    results: dict = {"per_sensor": {}, "overall_by_horizon": {}}
    for sensor_id in sorted(set(sensor_ids)):
        per_horizon = {}
        for horizon in horizons_minutes:
            group = predictions.loc[(predictions["sensor_id"] == sensor_id) &
                                    (predictions["horizon_minutes"] == horizon)]
            per_horizon[str(horizon)] = _metrics(group)
        results["per_sensor"][str(sensor_id)] = per_horizon
    for horizon in horizons_minutes:
        group = predictions.loc[predictions["horizon_minutes"] == horizon]
        results["overall_by_horizon"][str(horizon)] = _metrics(group)
    return results


def _metrics(group: pd.DataFrame) -> dict:
    count = len(group)
    if count == 0:
        return {"count": 0, "mae": None, "rmse": None}
    return {
        "count": count,
        "mae": float(group["absolute_error"].mean()),
        "rmse": float(np.sqrt(group["squared_error"].mean())),
    }
