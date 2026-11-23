import math
from datetime import datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from cold_storage_ml.app import app
from cold_storage_ml.shelf_life import ShelfLifeRequest, estimate_shelf_life


def payload(temperature=4.0, minutes=60, **changes):
    start = datetime(2026, 1, 1)
    body = {
        "category": "FRESH_POULTRY_AEROBIC",
        "initial_log_count": 2.0,
        "physiological_state_alpha0": 1.0,
        "interval_minutes": 1,
        "history": [
            {"timestamp": (start + timedelta(minutes=i)).isoformat(), "temperature_c": temperature}
            for i in range(minutes)
        ],
    }
    body.update(changes)
    return body


def estimate(**kwargs):
    return estimate_shelf_life(ShelfLifeRequest(**payload(**kwargs)))


@pytest.mark.parametrize("temperature,initial,days", [
    (0, 2, 25.2), (4, 2, 9.3), (8, 2, 4.8), (4, 3, 7.5), (4, 4, 5.6),
])
def test_published_constant_temperature_references(temperature, initial, days):
    result = estimate(temperature=temperature, initial_log_count=initial)
    total_days = (result["remaining_shelf_life_hours"] + 1) / 24
    assert total_days == pytest.approx(days, rel=0.05)


def test_one_day_contract_example_and_growth_units():
    result = estimate(minutes=1440)
    rate = (0.022 * (4 + 6.18)) ** 2
    total_hours = 5 * math.log(10) / rate
    assert result["remaining_shelf_life_hours"] == pytest.approx(total_hours - 24)
    assert result["shelf_life_used_fraction"] == pytest.approx(24 / total_hours)
    assert result["estimated_log_count"] == pytest.approx(2 + rate * 24 / math.log(10))
    assert result["spoilage_risk"] == "low"
    assert not result["past_spoilage_level"]


def test_warmer_history_and_higher_contamination_reduce_shelf_life():
    normal = estimate()
    assert estimate(temperature=8)["remaining_shelf_life_hours"] < normal["remaining_shelf_life_hours"]
    assert estimate(initial_log_count=3)["remaining_shelf_life_hours"] < normal["remaining_shelf_life_hours"]
    assert estimate(future_temperature_c=8)["remaining_shelf_life_hours"] < normal["remaining_shelf_life_hours"]


def test_lag_state_carries_forward_without_restarting_at_prediction():
    alpha = 0.1
    rate = (0.022 * (4 + 6.18)) ** 2
    first = estimate(physiological_state_alpha0=alpha)
    later = estimate(minutes=120, physiological_state_alpha0=alpha)
    assert first["remaining_shelf_life_hours"] - later["remaining_shelf_life_hours"] == pytest.approx(1)
    total_growth = (1 + first["remaining_shelf_life_hours"]) * rate
    adjusted = math.log1p(alpha * math.expm1(total_growth))
    assert adjusted / math.log(10) == pytest.approx(5)
    assert first["remaining_shelf_life_hours"] > estimate()["remaining_shelf_life_hours"]


def test_dynamic_history_growth_lies_between_constant_extremes():
    body = payload(minutes=1440, future_temperature_c=5)
    for i, reading in enumerate(body["history"]):
        reading["temperature_c"] = 5 if i < 720 else 10 if i < 1200 else 15
    dynamic = estimate_shelf_life(ShelfLifeRequest(**body))
    cold = estimate(temperature=5, minutes=1440, future_temperature_c=5)
    warm = estimate(temperature=15, minutes=1440, future_temperature_c=5)
    assert warm["remaining_shelf_life_hours"] < dynamic["remaining_shelf_life_hours"] < cold["remaining_shelf_life_hours"]


@pytest.mark.parametrize("minutes,risk", [(7000, "medium"), (12000, "high"), (15000, "high")])
def test_risk_levels_and_spoiled_outputs(minutes, risk):
    result = estimate(minutes=minutes)
    assert result["spoilage_risk"] == risk
    if minutes == 15000:
        assert result["remaining_shelf_life_hours"] == 0
        assert result["shelf_life_used_fraction"] == 1
        assert result["past_spoilage_level"]


def test_interpolates_ten_missing_minutes_and_reports_factors():
    body = payload()
    for i in range(10, 20):
        body["history"][i]["temperature_c"] = None
    body["history"][20]["temperature_c"] = 15
    result = estimate_shelf_life(ShelfLifeRequest(**body))
    assert result["interpolated_minutes"] == 10
    assert result["factors"]["minutes_above_4c"] == 11
    assert result["factors"]["peak_temperature_c"] == 15
    expected_mean = (49 * 4 + sum(range(5, 15)) + 15) / 60
    assert result["factors"]["mean_temperature_c"] == pytest.approx(expected_mean)
    assert 0 < result["factors"]["growth_share_above_4c"] < 1


def test_zero_growth_and_extrapolation_are_explicit():
    result = estimate(temperature=-10, future_temperature_c=4)
    assert result["estimated_log_count"] == 2
    assert result["factors"]["growth_share_above_4c"] == 0
    assert "HISTORY_TEMPERATURE_OUTSIDE_VALIDATED_RANGE" in result["flags"]
    assert estimate(initial_log_count=1)["flags"] == ["INITIAL_LOG_COUNT_OUTSIDE_TESTED_RANGE"]
    with pytest.raises(ValueError, match="Default future"):
        estimate(temperature=-10)


@pytest.mark.parametrize("changes", [
    {"category": "BEEF"}, {"initial_log_count": 0.9}, {"initial_log_count": 4.1},
    {"physiological_state_alpha0": 0.09}, {"physiological_state_alpha0": 1.1},
    {"future_temperature_c": -1}, {"future_temperature_c": 31},
    {"future_temperature_c": float("inf")}, {"initial_log_count": float("nan")},
    {"interval_minutes": 2}, {"interval_minutes": True}, {"minutes": 59},
    {"minutes": 20161},
])
def test_invalid_inputs_are_rejected(changes):
    with pytest.raises(ValueError):
        ShelfLifeRequest(**payload(**changes))


@pytest.mark.parametrize("mutation", ["gap", "duplicate", "reversed", "timezone", "seconds", "invalid",
                                     "first_null", "last_null", "long_null", "nan"])
def test_invalid_history_is_rejected(mutation):
    body = payload()
    rows = body["history"]
    if mutation == "gap":
        rows[20]["timestamp"] = "2026-01-01T00:21:00"
    elif mutation == "duplicate":
        rows[20]["timestamp"] = rows[19]["timestamp"]
    elif mutation == "reversed":
        rows.reverse()
    elif mutation == "timezone":
        rows[0]["timestamp"] += "Z"
    elif mutation == "seconds":
        rows[0]["timestamp"] = "2026-01-01T00:00:01"
    elif mutation == "invalid":
        rows[0]["timestamp"] = "not-a-timestamp"
    elif mutation == "first_null":
        rows[0]["temperature_c"] = None
    elif mutation == "last_null":
        rows[-1]["temperature_c"] = None
    elif mutation == "long_null":
        for row in rows[10:21]:
            row["temperature_c"] = None
    else:
        rows[0]["temperature_c"] = float("nan")
    with pytest.raises(ValueError):
        ShelfLifeRequest(**body)


def test_endpoint_works_without_forecast_artifacts_and_is_audited(tmp_path, monkeypatch, audit_collection):
    monkeypatch.setenv("FORECAST_ARTIFACT_DIR", str(tmp_path))
    with TestClient(app) as client:
        response = client.post("/api/v1/predictions/shelf-life", json=payload())
    assert response.status_code == 200
    body = response.json()
    assert body["unit"] == "celsius"
    assert body["model"]["citation_doi"] == "10.1016/j.psj.2022.101985"
    assert body["future_temperature_c"] == 4
    assert body["limitations"]
    events = [record["eventType"] for call in audit_collection.insert_many.call_args_list
              for record in call.args[0]]
    assert "ML_INFERENCE_SUCCEEDED" in events


def test_endpoint_validation_failures_are_audited(audit_collection):
    with TestClient(app) as client:
        response = client.post("/api/v1/predictions/shelf-life", json=payload(category="BEEF"))
    assert response.status_code == 422
    events = [record["eventType"] for call in audit_collection.insert_many.call_args_list
              for record in call.args[0]]
    assert "ML_INFERENCE_FAILED" in events


def test_extreme_temperature_returns_actionable_error():
    with TestClient(app) as client:
        response = client.post("/api/v1/predictions/shelf-life",
                               json=payload(temperature=1e308, future_temperature_c=4))
    assert response.status_code == 422
    assert "finite modeled growth" in response.json()["detail"]
