"""Inference on observed one-minute sensor history only.

Runtime inputs must contain complete observed history. Training-only features and
future targets never enter this module. Artifacts are local trusted files.
"""
from __future__ import annotations

import json
import math
import os
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from cold_storage_ml.forecast_models import FEATURE_COLUMNS, features_for_partition
from cold_storage_ml.threshold_warning import PREDICTOR_COLUMNS

HORIZONS = (15, 30, 60)


class Observation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    timestamp: str
    temperature: float
    humidity: float

    @field_validator("temperature", "humidity")
    @classmethod
    def finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("Measurement must be finite")
        return value

    @field_validator("humidity")
    @classmethod
    def humidity_range(cls, value: float) -> float:
        if not 0 <= value <= 100:
            raise ValueError("Humidity must be between 0 and 100")
        return value


class ForecastRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sensor_id: str = Field(min_length=1, max_length=80)
    history: list[Observation] = Field(min_length=61, max_length=10080)
    horizons_minutes: list[int] = Field(default_factory=lambda: [15, 30, 60], min_length=1, max_length=3)
    threshold: float

    @field_validator("sensor_id")
    @classmethod
    def sensor_trim(cls, value: str) -> str:
        if value.strip() != value or not value.strip():
            raise ValueError("Sensor ID cannot be blank or padded")
        return value

    @field_validator("threshold")
    @classmethod
    def threshold_finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("Threshold must be finite")
        return value

    @field_validator("horizons_minutes")
    @classmethod
    def horizons_supported(cls, value: list[int]) -> list[int]:
        if any(type(h) is not int or h not in HORIZONS for h in value) or len(value) != len(set(value)):
            raise ValueError("Horizons must be unique integers chosen from 15, 30, 60")
        return value

    @model_validator(mode="after")
    def history_valid(self) -> "ForecastRequest":
        timestamps = pd.to_datetime([x.timestamp for x in self.history], errors="coerce", utc=False)
        if timestamps.isna().any():
            raise ValueError("Invalid history timestamp")
        if timestamps.tz is not None:
            raise ValueError("Expected timezone-naive source timestamps")
        if any(t.second or t.microsecond for t in timestamps):
            raise ValueError("Timestamp must be aligned to a whole minute")
        if not timestamps.is_unique:
            raise ValueError("Duplicate history timestamps")
        if not timestamps.is_monotonic_increasing:
            raise ValueError("History must be chronologically increasing")
        # A complete 60-minute window at 1-minute cadence is mandatory.
        if any((timestamps[-61:][1:] - timestamps[-61:][:-1]) != pd.Timedelta(minutes=1)):
            raise ValueError("Most recent 61 readings must be consecutive one-minute observations")
        return self


def artifact_root() -> Path:
    return Path(os.environ.get("FORECAST_ARTIFACT_DIR", "models/artifacts/forecasting")).resolve()


class ForecastService:
    def __init__(self, root: Path):
        self.root = root
        self.configs = {}
        self.models = {}
        for h in HORIZONS:
            config_path = root / "trend" / f"damped_trend_{h}min.json"
            peak_path = root / "warnings" / f"peak_quantile_{h}min.joblib"
            if not config_path.is_file() or not peak_path.is_file():
                raise FileNotFoundError(f"Missing artifacts for horizon {h}: {config_path} or {peak_path}")
            config = json.loads(config_path.read_text(encoding="utf-8"))
            if (config.get("method") != "damped_trend" or config.get("horizon_minutes") != h
                    or config.get("lag_minutes") not in (1, 3, 5, 10, 15)
                    or not math.isfinite(float(config.get("weight")))
                    or not 0 <= float(config["weight"]) <= 4):
                raise ValueError(f"Invalid trend configuration for {h}")
            bundle = joblib.load(peak_path)  # Load only artifacts created by this project.
            if (bundle.get("method") != "future_peak_quantile"
                    or bundle.get("horizon_minutes") != h
                    or bundle.get("feature_columns") != PREDICTOR_COLUMNS):
                raise ValueError(f"Incompatible warning artifact for {h}")
            if not isinstance(config.get("sensor_ids"), list) or not all(isinstance(x, str) for x in config["sensor_ids"]):
                raise ValueError(f"Invalid sensor IDs in trend configuration for {h}")
            self.configs[h] = config
            self.models[h] = bundle["model"]

    def forecast(self, request: ForecastRequest) -> dict:
        for h in request.horizons_minutes:
            if request.sensor_id not in self.configs[h]["sensor_ids"]:
                raise ValueError(f"Unsupported sensor {request.sensor_id} for horizon {h}")
        frame = pd.DataFrame([{
            "timestamp": pd.Timestamp(obs.timestamp),
            "sensor_id": request.sensor_id,
            "temperature": obs.temperature,
            "humidity": obs.humidity,
        } for obs in request.history])
        features = features_for_partition(frame)
        last = features.iloc[-1]
        if not bool(last["complete_history"]) or not np.isfinite(last[FEATURE_COLUMNS].to_numpy(dtype=float)).all():
            raise ValueError("Insufficient valid consecutive history")
        observed = float(last["temperature"])
        origin = pd.Timestamp(last["origin_timestamp"])
        results = []
        for horizon in sorted(request.horizons_minutes):
            cfg = self.configs[horizon]
            lag = int(cfg["lag_minutes"])
            weight = float(cfg["weight"])
            past_temp = float(frame.iloc[-1 - lag]["temperature"])
            predicted = observed + weight * (observed - past_temp)
            peak = float(self.models[horizon].predict(last.to_frame().T[PREDICTOR_COLUMNS])[0])
            if not all(math.isfinite(x) for x in (predicted, peak)):
                raise ValueError("Model produced nonfinite prediction")
            results.append({
                "horizon_minutes": horizon,
                "target_timestamp": (origin + pd.Timedelta(minutes=horizon)).isoformat(),
                "predicted_temperature": predicted,
                "predicted_peak_temperature": peak,
                "predicted_threshold_crossing": bool(observed < request.threshold and peak >= request.threshold),
                "method": "damped_trend",
                "peak_method": "future_peak_quantile_0.9",
            })
        # Min/max apply only to the requested point forecasts, NOT to the full future trajectory.
        point_values = [x["predicted_temperature"] for x in results]
        return {
            "sensor_id": request.sensor_id,
            "origin_timestamp": origin.isoformat(),
            "current_temperature": observed,
            "threshold": request.threshold,
            "currently_above_threshold": observed >= request.threshold,
            "predictions": results,
            "predicted_min_temperature": min(point_values),
            "predicted_max_temperature": max(point_values),
            "min_max_scope": "requested_horizon_point_forecasts_only",
            "unit": "source_csv_unit_unconfirmed",
            "warning_note": "Upper-quantile peak is not a calibrated crossing probability or safety guarantee",
        }
