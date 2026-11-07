"""Local-only ML inference HTTP interface for Smart Cold Storage Monitor."""
from __future__ import annotations

from functools import lru_cache

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator
import math

from cold_storage_ml.inference import ForecastRequest, ForecastService, artifact_root
from cold_storage_ml.alert_policy import AlertRegistry

app = FastAPI(title="Smart Cold Storage Monitor ML", version="0.1.0")
alerts = AlertRegistry(clear_after=3)


class HealthResponse(BaseModel):
    status: str
    service: str


@app.get("/api/v1/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok", service="ml")


@lru_cache(maxsize=1)
def get_service() -> ForecastService:
    return ForecastService(artifact_root())


def _predict(payload: ForecastRequest) -> dict:
    try:
        return get_service().forecast(payload)
    except (FileNotFoundError, OSError) as exc:
        raise HTTPException(status_code=503, detail="Forecast artifacts unavailable") from exc
    except (ValueError, KeyError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


class CurrentTemperatureRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sensor_id: str = Field(min_length=1)
    temperature: float
    threshold: float

    @field_validator("temperature", "threshold")
    @classmethod
    def finite(cls, value: float) -> float:
        if not math.isfinite(value):
            raise ValueError("Temperature and threshold must be finite")
        return value


@app.post("/api/v1/alerts/current")
def current_alert(payload: CurrentTemperatureRequest) -> dict:
    """Stateless immediate threshold check: works even if models are unavailable."""
    active = payload.temperature >= payload.threshold
    return {"sensor_id": payload.sensor_id, "temperature": payload.temperature,
            "threshold": payload.threshold, "currently_above_threshold": active,
            "severity": "current_violation" if active else "clear"}


@app.get("/api/v1/ready")
def ready() -> dict:
    try:
        get_service()
    except (FileNotFoundError, OSError, ValueError, KeyError) as exc:
        raise HTTPException(status_code=503, detail="Forecast artifacts are not ready") from exc
    return {"status": "ready", "horizons_minutes": [15, 30, 60]}


@app.post("/api/v1/predictions/temperature")
def predict_temperature(payload: ForecastRequest) -> dict:
    """Stateless prediction; safe for repeated historical simulations."""
    return _predict(payload)


@app.post("/api/v1/alerts/evaluate")
def evaluate_alert(payload: ForecastRequest) -> dict:
    """Stateful local demonstration; backend should persist real alerts."""
    if set(payload.horizons_minutes) != {15, 30, 60}:
        raise HTTPException(status_code=422, detail="Alert evaluation requires all three horizons")
    forecast = _predict(payload)
    try:
        status = alerts.process(forecast)
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return {"forecast": forecast, "alert": status}
