"""Leakage-aware, multi-sensor temperature preparation (no model fitting)."""

from __future__ import annotations

import math
import re
from pathlib import Path

import numpy as np
import pandas as pd

COLUMNS = ["timestamp", "sensor_id", "temperature", "humidity", "temperature_count", "humidity_count"]
SPLITS = ("train", "validation", "test")


def sensor_id_from_path(path: str | Path) -> str:
    stem = Path(path).stem
    match = re.search(r"sensor[_\- ]?(\d+)", stem, flags=re.IGNORECASE)
    return f"SENSOR{int(match.group(1)):02d}" if match else stem


def load_sensor_csv(path: str | Path, sensor_id: str | None = None) -> pd.DataFrame:
    """Load one sensor; reject invalid/ambiguous timestamps and duplicates.

    Non-numeric/missing or physically implausible readings become NaN, not invented
    values. Timestamps are timezone-naive because the source doesn't specify a zone.
    """
    path = Path(path)
    frame = pd.read_csv(path, sep=None, engine="python", encoding="utf-8-sig")
    frame.columns = frame.columns.str.strip().str.lower()
    if frame.empty:
        raise ValueError(f"Empty CSV: {path}")
    if {"date", "time"}.issubset(frame.columns):
        raw_ts = frame["date"].astype("string").str.strip() + " " + frame["time"].astype("string").str.strip()
        ts = pd.to_datetime(raw_ts, format="%d.%m.%Y %H:%M:%S", errors="coerce")
    elif "timestamp" in frame.columns:
        # Require ISO timestamps to avoid ambiguous day/month interpretation.
        ts = pd.to_datetime(frame["timestamp"], format="ISO8601", errors="coerce")
    else:
        raise ValueError(f"Expected date/time or timestamp columns: {path}")
    if ts.isna().any():
        raise ValueError(f"Invalid timestamps in {path}: {int(ts.isna().sum())}")
    if getattr(ts.dt, "tz", None) is not None:
        raise ValueError("Timezone-aware and naive CSVs must not be mixed; normalize explicitly first")
    if ts.duplicated().any():
        raise ValueError(f"Duplicate timestamps in {path}: {int(ts.duplicated().sum())}")
    if "temperature" not in frame:
        raise ValueError(f"Missing temperature column: {path}")

    data = pd.DataFrame({"timestamp": ts, "sensor_id": sensor_id or sensor_id_from_path(path)})
    data["temperature"] = pd.to_numeric(frame["temperature"], errors="coerce")
    data["temperature"] = data["temperature"].where(data["temperature"].between(-40, 80))
    if "humidity" in frame:
        data["humidity"] = pd.to_numeric(frame["humidity"], errors="coerce")
        data["humidity"] = data["humidity"].where(data["humidity"].between(0, 100))
    else:
        data["humidity"] = np.nan
    return data.sort_values("timestamp").reset_index(drop=True)


def resample_sensor(frame: pd.DataFrame, interval: str = "1min", min_coverage: float = 0.75,
                    source_interval_seconds: int = 5) -> pd.DataFrame:
    """End-label bins: values at 09:36 summarize (09:35, 09:36].

    Require a fraction of expected 5-second readings in each bin; never fill gaps.
    """
    if not 0 < min_coverage <= 1:
        raise ValueError("min_coverage must be in (0, 1]")
    if source_interval_seconds <= 0:
        raise ValueError("source_interval_seconds must be positive")
    if frame.empty or frame["sensor_id"].nunique() != 1:
        raise ValueError("resample_sensor expects non-empty data from exactly one sensor")
    interval_seconds = pd.Timedelta(interval).total_seconds()
    if interval_seconds < source_interval_seconds or interval_seconds % source_interval_seconds != 0:
        raise ValueError("interval must be an integer multiple of source_interval_seconds")
    required = math.ceil(interval_seconds / source_interval_seconds * min_coverage)
    agg = (frame.set_index("timestamp")[["temperature", "humidity"]]
           .resample(interval, label="right", closed="right")
           .agg(["mean", "count"]))
    agg.columns = ["temperature", "temperature_count", "humidity", "humidity_count"]
    agg.loc[agg["temperature_count"] < required, "temperature"] = np.nan
    agg.loc[agg["humidity_count"] < required, "humidity"] = np.nan
    agg = agg.reset_index()
    agg.insert(1, "sensor_id", frame["sensor_id"].iloc[0])
    return agg[COLUMNS]


def prepare_sensors(paths: list[str | Path], interval: str = "1min",
                    min_coverage: float = 0.75) -> pd.DataFrame:
    if not paths:
        raise ValueError("At least one input CSV is required")
    datasets = [load_sensor_csv(p) for p in paths]
    ids = [x["sensor_id"].iloc[0] for x in datasets]
    if len(ids) != len(set(ids)):
        raise ValueError(f"Duplicate sensor IDs in input files: {ids}")
    output = pd.concat([resample_sensor(df, interval, min_coverage) for df in datasets], ignore_index=True)
    return output.sort_values(["timestamp", "sensor_id"]).reset_index(drop=True)


def chronological_split(frame: pd.DataFrame, train_fraction: float = 0.70,
                        validation_fraction: float = 0.15) -> tuple[dict[str, pd.DataFrame], dict]:
    """Split on SHARED clock times across all sensors, not random rows.

    This only partitions observations. Future supervised examples must keep both
    their feature history and forecast target within the assigned partition.
    """
    if train_fraction <= 0 or validation_fraction <= 0 or train_fraction + validation_fraction >= 1:
        raise ValueError("Fractions must be positive and sum to less than 1")
    times = pd.DatetimeIndex(frame["timestamp"].drop_duplicates().sort_values())
    if len(times) < 3:
        raise ValueError("At least three distinct timestamps required")
    a = int(len(times) * train_fraction)
    b = int(len(times) * (train_fraction + validation_fraction))
    if not 0 < a < b < len(times):
        raise ValueError("Not enough timestamps for non-empty splits")
    boundaries = {"validation_start": times[a].isoformat(), "test_start": times[b].isoformat()}
    result = {
        "train": frame[frame["timestamp"] < times[a]].copy(),
        "validation": frame[(frame["timestamp"] >= times[a]) & (frame["timestamp"] < times[b])].copy(),
        "test": frame[frame["timestamp"] >= times[b]].copy(),
    }
    return result, boundaries


def data_quality_summary(frame: pd.DataFrame, parts: dict[str, pd.DataFrame],
                         boundaries: dict, interval: str, min_coverage: float) -> dict:
    per_sensor = {}
    for sensor_id, group in frame.groupby("sensor_id"):
        missing = group["temperature"].isna()
        runs = (missing != missing.shift(fill_value=False)).cumsum()
        lengths = missing.groupby(runs).sum()
        longest = int(lengths.max()) if len(lengths) else 0
        per_sensor[str(sensor_id)] = {
            "rows": len(group),
            "missing_temperature_bins": int(missing.sum()),
            "missing_humidity_bins": int(group["humidity"].isna().sum()),
            "longest_missing_temperature_bins": longest,
            "temperature_min": None if group["temperature"].count() == 0 else float(group["temperature"].min()),
            "temperature_max": None if group["temperature"].count() == 0 else float(group["temperature"].max()),
        }
    return {
        "interval": interval,
        "aggregation": "mean of valid sensor readings; right-labeled/right-closed bins",
        "minimum_valid_reading_fraction_per_bin": min_coverage,
        "missing_value_policy": "NaN preserved; no interpolation or imputation",
        "timestamp_timezone": "unspecified in source CSV; naive timestamps retained",
        "temperature_units": "not confirmed by this pipeline",
        "split_boundaries": boundaries,
        "split_rows": {name: len(part) for name, part in parts.items()},
        "sensors": per_sensor,
    }
