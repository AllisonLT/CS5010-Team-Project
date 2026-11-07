import json

import joblib
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from cold_storage_ml.alert_policy import AlertRegistry
from cold_storage_ml.app import app, get_service
from cold_storage_ml.inference import ForecastRequest, ForecastService


class ConstantPeak:
    def __init__(self, peak):
        self.peak = peak

    def predict(self, frame):
        return [self.peak] * len(frame)


@pytest.fixture
def artifact_dir(tmp_path):
    trend = tmp_path / "trend"
    warnings = tmp_path / "warnings"
    trend.mkdir()
    warnings.mkdir()
    from cold_storage_ml.threshold_warning import PREDICTOR_COLUMNS
    for h in (15, 30, 60):
        (trend / f"damped_trend_{h}min.json").write_text(json.dumps({
            "method": "damped_trend", "horizon_minutes": h, "lag_minutes": 5,
            "weight": 1.0, "sensor_ids": ["SENSOR02"],
        }))
        joblib.dump({"method": "future_peak_quantile", "horizon_minutes": h,
                     "feature_columns": PREDICTOR_COLUMNS, "model": ConstantPeak(11.0)},
                    warnings / f"peak_quantile_{h}min.joblib")
    return tmp_path


def history(n=61, start="2024-09-09 10:00", current=8.0):
    return [{"timestamp": t.isoformat(), "temperature": current + i * 0.01,
             "humidity": 70.0} for i, t in enumerate(pd.date_range(start, periods=n, freq="min"))]


def payload(**changes):
    value = {"sensor_id": "SENSOR02", "history": history(), "threshold": 10.0,
             "horizons_minutes": [15, 30, 60]}
    value.update(changes)
    return value


def test_predict_three_horizons_and_minmax(artifact_dir):
    svc = ForecastService(artifact_dir)
    result = svc.forecast(ForecastRequest(**payload()))
    assert len(result["predictions"]) == 3
    assert [p["target_timestamp"] for p in result["predictions"]] == [
        "2024-09-09T11:15:00", "2024-09-09T11:30:00", "2024-09-09T12:00:00"]
    assert all(p["predicted_threshold_crossing"] for p in result["predictions"])
    assert result["predicted_min_temperature"] == pytest.approx(8.65)
    assert result["min_max_scope"] == "requested_horizon_point_forecasts_only"


def test_reject_missing_history_gap():
    rows = history(n=62)
    del rows[-7]
    with pytest.raises(ValueError, match="consecutive"):
        ForecastRequest(**payload(history=rows))


def test_reject_duplicate_timestamps():
    rows = history()
    rows[-1]["timestamp"] = rows[-2]["timestamp"]
    with pytest.raises(ValueError, match="Duplicate"):
        ForecastRequest(**payload(history=rows))


def test_reject_unsupported_horizon():
    with pytest.raises(ValueError):
        ForecastRequest(**payload(horizons_minutes=[10]))


def test_reject_humidity_and_timezone():
    rows = history()
    rows[-1]["humidity"] = 101
    with pytest.raises(ValueError):
        ForecastRequest(**payload(history=rows))
    rows = history()
    rows[-1]["timestamp"] += "Z"
    with pytest.raises(ValueError):
        ForecastRequest(**payload(history=rows))


def test_missing_artifacts_returns_503(tmp_path, monkeypatch):
    monkeypatch.setenv("FORECAST_ARTIFACT_DIR", str(tmp_path))
    get_service.cache_clear()
    with TestClient(app) as client:
        assert client.get("/api/v1/health").status_code == 200
        assert client.get("/api/v1/ready").status_code == 503
        assert client.post("/api/v1/predictions/temperature", json=payload()).status_code == 503
    get_service.cache_clear()


def test_http_prediction_and_readiness(artifact_dir, monkeypatch):
    monkeypatch.setenv("FORECAST_ARTIFACT_DIR", str(artifact_dir))
    get_service.cache_clear()
    with TestClient(app) as client:
        assert client.get("/api/v1/ready").status_code == 200
        result = client.post("/api/v1/predictions/temperature", json=payload())
        assert result.status_code == 200, result.text
        assert result.json()["sensor_id"] == "SENSOR02"
    get_service.cache_clear()


def test_current_violation_is_separate(artifact_dir):
    svc = ForecastService(artifact_dir)
    result = svc.forecast(ForecastRequest(**payload(threshold=8.0)))
    assert result["currently_above_threshold"] is True
    assert not any(p["predicted_threshold_crossing"] for p in result["predictions"])


def test_alert_dedup_escalation_and_clearing():
    registry = AlertRegistry(clear_after=3)
    r = {"sensor_id": "SENSOR02", "threshold": 10.0, "origin_timestamp": "2024-09-09T10:00:00",
         "currently_above_threshold": False,
         "predictions": [{"horizon_minutes": 15, "predicted_threshold_crossing": True}]}
    assert registry.process(r)["event"] == "raised"
    r["origin_timestamp"] = "2024-09-09T10:01:00"
    assert registry.process(r)["event"] == "none"
    r["currently_above_threshold"] = True
    assert registry.process({**r, "origin_timestamp": "2024-09-09T10:02:00"})["event"] == "escalated"
    r["currently_above_threshold"] = False
    r["predictions"][0]["predicted_threshold_crossing"] = False
    for i, expected in enumerate(["none", "none", "cleared"], start=3):
        assert registry.process({**r, "origin_timestamp": f"2024-09-09T10:0{i}:00"})["event"] == expected


def test_immediate_alert_without_artifacts(tmp_path, monkeypatch):
    monkeypatch.setenv("FORECAST_ARTIFACT_DIR", str(tmp_path))
    get_service.cache_clear()
    with TestClient(app) as client:
        result = client.post("/api/v1/alerts/current", json={"sensor_id": "SENSOR02", "temperature": 10, "threshold": 10})
        assert result.status_code == 200
        assert result.json()["severity"] == "current_violation"
    get_service.cache_clear()


def test_alert_downgrade_on_cooling():
    registry = AlertRegistry()
    r = {"sensor_id": "SENSOR02", "threshold": 10.0, "origin_timestamp": "2024-09-09T10:00:00",
         "currently_above_threshold": True, "predictions": [{"horizon_minutes": 30, "predicted_threshold_crossing": True}]}
    assert registry.process(r)["severity"] == "current_violation"
    r["currently_above_threshold"] = False
    r["origin_timestamp"] = "2024-09-09T10:01:00"
    output = registry.process(r)
    assert output["severity"] == "warning"
    assert output["event"] == "updated"


def test_unknown_sensor_rejected(artifact_dir):
    svc = ForecastService(artifact_dir)
    with pytest.raises(ValueError, match="Unsupported sensor"):
        svc.forecast(ForecastRequest(**payload(sensor_id="SENSOR99")))


def test_alert_rejects_nonmonotonic_request():
    registry = AlertRegistry()
    r = {"sensor_id": "SENSOR02", "threshold": 10.0, "origin_timestamp": "2024-09-09T10:00:00",
         "currently_above_threshold": False, "predictions": []}
    registry.process(r)
    with pytest.raises(ValueError, match="strictly increasing"):
        registry.process(r)
