"""Local-only ML inference HTTP interface for Smart Cold Storage Monitor."""
from __future__ import annotations

from functools import lru_cache

from fastapi import APIRouter, FastAPI, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator
import math

from cold_storage_ml.inference import ForecastRequest, ForecastService, artifact_root
from cold_storage_ml.alert_policy import AlertRegistry
from cold_storage_ml.audit import AuditedRoute, audit_lifespan

app = FastAPI(title="Smart Cold Storage Monitor ML", version="0.1.0", lifespan=audit_lifespan)
audited = APIRouter(route_class=AuditedRoute)
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


@audited.post("/api/v1/alerts/current")
def current_alert(payload: CurrentTemperatureRequest, request: Request) -> dict:
    """Stateless immediate threshold check: works even if models are unavailable."""
    active = payload.temperature >= payload.threshold
    request.state.audit_details = {"sensorId": payload.sensor_id[:128], "thresholdC": payload.threshold}
    if active:
        request.state.audit_warning = {"level": "current_violation"}
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


def _forecast_audit_details(payload: ForecastRequest) -> dict:
    return {"sensorId": payload.sensor_id[:128], "thresholdC": payload.threshold,
            "horizonsMinutes": payload.horizons_minutes}


@audited.post("/api/v1/predictions/temperature")
def predict_temperature(payload: ForecastRequest, request: Request) -> dict:
    """Stateless prediction; safe for repeated historical simulations."""
    request.state.audit_details = _forecast_audit_details(payload)
    forecast = _predict(payload)
    crossings = [p["horizon_minutes"] for p in forecast["predictions"] if p["predicted_threshold_crossing"]]
    if forecast["currently_above_threshold"] or crossings:
        request.state.audit_warning = {"currentlyAboveThreshold": forecast["currently_above_threshold"],
                                       "crossingHorizonsMinutes": crossings}
    return forecast


@audited.post("/api/v1/alerts/evaluate")
def evaluate_alert(payload: ForecastRequest, request: Request) -> dict:
    """Stateful local demonstration; backend should persist real alerts."""
    request.state.audit_details = _forecast_audit_details(payload)
    if set(payload.horizons_minutes) != {15, 30, 60}:
        raise HTTPException(status_code=422, detail="Alert evaluation requires all three horizons")
    forecast = _predict(payload)
    try:
        status = alerts.process(forecast)
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    if status["event"] in {"raised", "escalated"}:
        request.state.audit_warning = {"level": status["severity"], "event": status["event"]}
    return {"forecast": forecast, "alert": status}


app.include_router(audited)
