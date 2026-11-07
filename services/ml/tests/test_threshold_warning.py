import numpy as np
import pandas as pd
import pytest

from cold_storage_ml.threshold_warning import (
    peak_examples, create_peak_model, warning_predictions, crossing_metrics,
)
from cold_storage_ml.forecast_models import validate_splits


def data(n=165, sid="SENSOR02", rising=False):
    times = pd.date_range("2024-01-01", periods=n, freq="1min")
    temp = np.full(n, 7.0)
    if rising:
        temp[125:145] = 12.0
    return pd.DataFrame({"timestamp": times, "sensor_id": sid,
                         "temperature": temp, "humidity": np.full(n, 75.0)})


def test_future_peak_exact_horizon_and_no_origin_as_target():
    f = data(rising=True)
    e = peak_examples(f, 15)
    row = e.loc[e.origin_timestamp.eq(pd.Timestamp("2024-01-01 02:00:00"))].iloc[0]
    assert row.future_peak_temperature == 12.0
    assert row.window_end_timestamp == row.origin_timestamp + pd.Timedelta(minutes=15)
    assert e.origin_timestamp.min() == pd.Timestamp("2024-01-01 01:00")
    assert e.origin_timestamp.max() == pd.Timestamp("2024-01-01 02:29")


def test_future_nan_invalidates_example_not_filled():
    f = data()
    f.loc[130, "temperature"] = np.nan
    e = peak_examples(f, 15)
    # Any origin whose future (t+1,t+15] includes row 130 is absent.
    for i in range(115, 130):
        assert pd.Timestamp("2024-01-01") + pd.Timedelta(minutes=i) not in set(e.origin_timestamp)


def test_missing_history_invalidates_origin():
    f = data()
    f.loc[50, "temperature"] = np.nan
    e = peak_examples(f, 15)
    assert e.origin_timestamp.min() == pd.Timestamp("2024-01-01 01:51")


def test_gap_row_absent_not_treated_as_filled():
    f = data().drop(index=[120])
    e = peak_examples(f, 15)
    assert pd.Timestamp("2024-01-01 01:59") not in set(e.origin_timestamp)


def test_sensor_isolation():
    a, b = data(sid="SENSOR01"), data(sid="SENSOR02", rising=True)
    combined = pd.concat([b, a], ignore_index=True)
    e = peak_examples(combined, 15)
    assert e.loc[e.sensor_id.eq("SENSOR01"), "future_peak_temperature"].eq(7).all()
    assert e.loc[e.sensor_id.eq("SENSOR02"), "future_peak_temperature"].max() == 12


def test_warning_excludes_existing_violation():
    f = data(rising=True)
    e = peak_examples(f, 15)
    class Fixed:
        def predict(self, x):
            return np.full(len(x), 20.0)
    r = warning_predictions(Fixed(), e, 10)
    assert not r.loc[r.currently_above_threshold, "predicted_future_crossing"].any()
    assert r.loc[r.currently_above_threshold, "currently_above_threshold"].all()


def test_confusion_metrics_and_zero_denominators():
    r = pd.DataFrame({"currently_above_threshold": [False]*4,
                      "actual_future_crossing": [True, True, False, False],
                      "predicted_future_crossing": [True, False, True, False]})
    m = crossing_metrics(r)
    assert (m["tp"], m["fp"], m["fn"], m["tn"]) == (1, 1, 1, 1)
    assert m["precision"] == m["recall"] == 0.5
    r["predicted_future_crossing"] = False
    assert crossing_metrics(r)["precision"] is None


def test_invalid_inputs():
    with pytest.raises(ValueError):
        peak_examples(data(), 17)
    with pytest.raises(ValueError):
        create_peak_model(1.0)
    class Fixed:
        def predict(self, x): return np.zeros(len(x))
    with pytest.raises(ValueError):
        warning_predictions(Fixed(), peak_examples(data(), 15), np.nan)


def test_split_validation_rejects_overlap():
    with pytest.raises(ValueError):
        validate_splits(data(165), data(165))
