from __future__ import annotations

import joblib
import numpy as np
import pandas as pd
import pytest

from cold_storage_ml.forecast_models import (
    FEATURE_COLUMNS, MODEL_NAMES, create_model, evaluate_one_model,
    features_for_partition, metrics, supervised_examples, validate_splits,
)


def make_data(sensors=("SENSOR01", "SENSOR02"), minutes=200, start="2024-09-02 00:00"):
    times = pd.date_range(start, periods=minutes, freq="min")
    return pd.DataFrame([
        {"timestamp": t, "sensor_id": sid, "temperature": (i + n / 10),
         "humidity": (60 + n / 20)}
        for i, sid in enumerate(sensors) for n, t in enumerate(times)
    ])


def test_lag_features_use_past_only_and_do_not_mix_sensors():
    data = make_data()
    feats = features_for_partition(data)
    point = feats[(feats.sensor_id == "SENSOR02") &
                  (feats.origin_timestamp == pd.Timestamp("2024-09-02 01:00"))].iloc[0]
    assert point["temperature"] == pytest.approx(7.0)
    assert point["temperature_lag_5"] == pytest.approx(6.5)
    assert point["temperature_mean_5"] == pytest.approx(np.mean([6.6, 6.7, 6.8, 6.9, 7.0]))
    assert point["delta_temperature_15"] == pytest.approx(1.5)


def test_future_mutation_does_not_change_past_features():
    data = make_data()
    a = features_for_partition(data)
    data.loc[data.timestamp >= pd.Timestamp("2024-09-02 01:10"), "temperature"] = 999
    b = features_for_partition(data)
    cols = ["sensor_id", "origin_timestamp", *FEATURE_COLUMNS]
    pd.testing.assert_frame_equal(
        a.loc[a.origin_timestamp <= pd.Timestamp("2024-09-02 01:00"), cols].reset_index(drop=True),
        b.loc[b.origin_timestamp <= pd.Timestamp("2024-09-02 01:00"), cols].reset_index(drop=True),
    )


def test_exact_horizon_and_partition_boundary():
    data = make_data(minutes=180)
    examples = supervised_examples(data, 60)
    assert all(examples.target_timestamp - examples.origin_timestamp == pd.Timedelta(minutes=60))
    assert examples.origin_timestamp.min() == pd.Timestamp("2024-09-02 01:00")
    assert examples.target_timestamp.max() == data.timestamp.max()
    assert examples.origin_timestamp.max() == data.timestamp.max() - pd.Timedelta(minutes=60)


def test_gap_does_not_become_false_lag_and_later_recovers():
    data = make_data(minutes=240)
    bad_times = pd.date_range("2024-09-02 01:20", periods=15, freq="min")
    data = data.loc[~data.timestamp.isin(bad_times)].copy()  # absent rows
    examples = supervised_examples(data, 15)
    assert not examples.origin_timestamp.isin(pd.date_range("2024-09-02 01:20", "2024-09-02 02:34", freq="min")).any()
    assert examples.origin_timestamp.max() == pd.Timestamp("2024-09-02 03:44")


def test_missing_humidity_or_temperature_excludes_examples():
    data = make_data(minutes=140)
    data.loc[data.timestamp == pd.Timestamp("2024-09-02 01:00"), "humidity"] = np.nan
    examples = supervised_examples(data, 15)
    assert not (examples.origin_timestamp == pd.Timestamp("2024-09-02 01:00")).any()
    data.loc[data.timestamp == pd.Timestamp("2024-09-02 01:55"), "temperature"] = np.nan
    examples = supervised_examples(data, 15)
    assert not (examples.target_timestamp == pd.Timestamp("2024-09-02 01:55")).any()


def test_all_models_fit_and_predict_on_same_examples():
    train = supervised_examples(make_data(minutes=220), 15)
    val = supervised_examples(make_data(minutes=140, start="2024-09-03 00:00"), 15)
    for name in MODEL_NAMES:
        model = create_model(name)
        model.fit(train[["sensor_id", *FEATURE_COLUMNS]], train["observed_temperature"])
        preds, summary = evaluate_one_model(model, val, name)
        assert len(preds) == len(val)
        assert summary["model"]["count"] == summary["matched_persistence"]["count"] == len(val)
        assert set(preds.sensor_id) == {"SENSOR01", "SENSOR02"}


def test_metrics_math_and_empty():
    assert metrics(np.array([0, 2]), np.array([1, 0]))["mae"] == pytest.approx(1.5)
    assert metrics(np.array([0, 2]), np.array([1, 0]))["rmse"] == pytest.approx(np.sqrt(2.5))
    assert metrics(np.array([]), np.array([]))["count"] == 0


def test_reject_train_validation_overlap_and_sensor_mismatch():
    data = make_data()
    with pytest.raises(ValueError, match="overlap"):
        validate_splits(data.loc[data.timestamp < pd.Timestamp("2024-09-02 02:00")], data.loc[data.timestamp >= pd.Timestamp("2024-09-02 01:30")])
    with pytest.raises(ValueError, match="sensor sets differ"):
        validate_splits(data, make_data(sensors=("SENSOR03",), start="2024-09-03"))
    validate_splits(data, make_data(start="2024-09-03"))


def test_reject_unsupported_horizons_and_duplicate_timestamps():
    data = make_data()
    with pytest.raises(ValueError, match="Horizon"):
        supervised_examples(data, 17)
    with pytest.raises(ValueError, match="Duplicate"):
        supervised_examples(pd.concat([data, data.iloc[[0]]]), 15)


def test_joblib_roundtrip(tmp_path):
    train = supervised_examples(make_data(minutes=200), 15)
    X = train[["sensor_id", *FEATURE_COLUMNS]]
    model = create_model("ridge")
    model.fit(X, train.observed_temperature)
    before = model.predict(X.head(5))
    path = tmp_path / "model.joblib"
    joblib.dump({"model": model, "horizon_minutes": 15}, path)
    after = joblib.load(path)["model"].predict(X.head(5))
    np.testing.assert_allclose(before, after)
