"""Checkpoint 3: no leakage, correct horizons, correct metric calculations."""

import numpy as np
import pandas as pd
import pytest

from cold_storage_ml.baseline import (
    evaluate_predictions,
    persistence_predictions,
    validate_partition,
)


def frame(values, sensor="SENSOR02", start="2024-09-01 00:00:00"):
    return pd.DataFrame({
        "timestamp": pd.date_range(start, periods=len(values), freq="1min"),
        "sensor_id": sensor,
        "temperature": values,
    })


def test_exact_15_minute_prediction_and_metrics():
    df = frame(list(range(17)))
    pred = persistence_predictions(df, [15])
    assert len(pred) == 2
    assert pred["predicted_temperature"].tolist() == [0, 1]
    assert pred["observed_temperature"].tolist() == [15, 16]
    metrics = evaluate_predictions(pred, ["SENSOR02"], [15])
    assert metrics["per_sensor"]["SENSOR02"]["15"]["mae"] == 15
    assert metrics["per_sensor"]["SENSOR02"]["15"]["rmse"] == 15


def test_missing_origin_and_target_are_excluded():
    values = list(range(32))
    values[0] = np.nan            # invalid origin for target minute 15
    values[31] = np.nan           # invalid target for origin minute 16
    pred = persistence_predictions(frame(values), [15])
    assert len(pred) == 15         # possible 17 - 2 missing pairs
    assert pred["origin_timestamp"].min() == pd.Timestamp("2024-09-01 00:01:00")
    assert pred["origin_timestamp"].max() == pd.Timestamp("2024-09-01 00:15:00")


def test_missing_timestamp_not_treated_as_next_minute():
    df = frame(list(range(17))).drop(index=15)
    pred = persistence_predictions(df, [15])
    assert len(pred) == 1
    assert pred.iloc[0]["origin_timestamp"] == pd.Timestamp("2024-09-01 00:01:00")


def test_two_sensors_not_mixed():
    df = pd.concat([frame(list(range(17)), "SENSOR01"),
                    frame(list(range(100, 117)), "SENSOR02")], ignore_index=True)
    pred = persistence_predictions(df, [15])
    assert len(pred) == 4
    assert set(pred.loc[pred.sensor_id == "SENSOR01", "predicted_temperature"]) == {0, 1}
    assert set(pred.loc[pred.sensor_id == "SENSOR02", "predicted_temperature"]) == {100, 101}


def test_split_boundary_is_not_crossed():
    train = frame(list(range(20)))
    validation = frame(list(range(20, 40)), start="2024-09-01 00:20:00")
    pred = persistence_predictions(validation, [15])
    assert len(pred) == 5
    assert pred["origin_timestamp"].min() == pd.Timestamp("2024-09-01 00:20:00")
    assert pred["target_timestamp"].min() == pd.Timestamp("2024-09-01 00:35:00")
    assert not set(pred.target_timestamp).intersection(set(train.timestamp))


def test_empty_pairs_have_null_metrics():
    pred = persistence_predictions(frame([np.nan] * 20), [15])
    result = evaluate_predictions(pred, ["SENSOR02"], [15])
    assert result["per_sensor"]["SENSOR02"]["15"] == {"count": 0, "mae": None, "rmse": None}


def test_rmse_is_computed_from_squared_errors_not_mean_absolute_errors():
    df = frame([10.0] * 15 + [11.0, 13.0])
    pred = persistence_predictions(df, [15])
    result = evaluate_predictions(pred, ["SENSOR02"], [15])
    assert result["per_sensor"]["SENSOR02"]["15"]["mae"] == 2.0
    assert result["per_sensor"]["SENSOR02"]["15"]["rmse"] == pytest.approx(np.sqrt(5.0))


def test_reject_duplicate_sensor_timestamps():
    df = frame([1, 2])
    with pytest.raises(ValueError, match="Duplicate"):
        validate_partition(pd.concat([df, df.iloc[[0]]], ignore_index=True))


def test_reject_bad_horizon():
    with pytest.raises(ValueError, match="Unsupported"):
        persistence_predictions(frame([1, 2]), [20])
    with pytest.raises(ValueError, match="Duplicate"):
        persistence_predictions(frame([1, 2]), [15, 15])


def test_supported_horizons_have_correct_timedelta():
    df = frame([7.] * 65)
    pred = persistence_predictions(df, [15, 30, 60])
    assert {15, 30, 60} == set(pred.horizon_minutes)
    for h, group in pred.groupby("horizon_minutes"):
        assert (group.target_timestamp - group.origin_timestamp == pd.Timedelta(minutes=int(h))).all()
        assert len(group) == 65 - h
