import numpy as np
import pandas as pd
import pytest

from cold_storage_ml.preprocessing import (
    chronological_split, load_sensor_csv, prepare_sensors, resample_sensor,
)


def write_sensor(tmp_path, name, rows):
    path = tmp_path / name
    pd.DataFrame(rows).to_csv(path, sep=";", index=False)
    return path


def test_load_source_schema_and_sort(tmp_path):
    p = write_sensor(tmp_path, "SENSOR02_preprocessed.csv", {
        "date": ["02.09.2024", "02.09.2024"],
        "time": ["09:35:10", "09:35:05"],
        "temperature": [8.0, 7.0], "humidity": [70, 65]})
    df = load_sensor_csv(p)
    assert df["sensor_id"].tolist() == ["SENSOR02", "SENSOR02"]
    assert df["temperature"].tolist() == [7.0, 8.0]


def test_invalid_dates_and_duplicate_timestamps_rejected(tmp_path):
    p = write_sensor(tmp_path, "SENSOR01.csv", {
        "date": ["02.09.2024", "02.09.2024"],
        "time": ["09:35:00", "09:35:00"], "temperature": [6, 7]})
    with pytest.raises(ValueError, match="Duplicate timestamps"):
        load_sensor_csv(p)
    q = write_sensor(tmp_path, "SENSOR03.csv", {
        "date": ["99.09.2024"], "time": ["09:35:00"], "temperature": [6]})
    with pytest.raises(ValueError, match="Invalid timestamps"):
        load_sensor_csv(q)


def test_resampling_does_not_fill_long_gap():
    ts = pd.date_range("2024-09-02 00:00:05", periods=12, freq="5s")
    # First minute: exactly 12 values; next 2 minutes have no rows; fourth minute full.
    ts2 = pd.date_range("2024-09-02 00:03:05", periods=12, freq="5s")
    frame = pd.DataFrame({"timestamp": ts.append(ts2), "sensor_id": "SENSOR02",
                          "temperature": [6.0] * 12 + [8.0] * 12,
                          "humidity": [70.0] * 24})
    res = resample_sensor(frame)
    assert res.loc[res.timestamp == pd.Timestamp("2024-09-02 00:01:00"), "temperature"].iloc[0] == 6
    assert res.loc[res.timestamp == pd.Timestamp("2024-09-02 00:02:00"), "temperature"].isna().all()
    assert res.loc[res.timestamp == pd.Timestamp("2024-09-02 00:03:00"), "temperature"].isna().all()
    assert res.loc[res.timestamp == pd.Timestamp("2024-09-02 00:04:00"), "temperature"].iloc[0] == 8


def test_min_coverage_and_invalid_readings(tmp_path):
    ts = pd.date_range("2024-09-02 00:00:05", periods=12, freq="5s")
    p = write_sensor(tmp_path, "SENSOR04.csv", {
        "date": ["02.09.2024"] * 12,
        "time": [t.strftime("%H:%M:%S") for t in ts],
        "temperature": [7.0] * 8 + [200.0] * 4,
        "humidity": [71.0] * 12})
    data = load_sensor_csv(p)
    assert data["temperature"].isna().sum() == 4
    res = resample_sensor(data, min_coverage=0.75)
    assert np.isnan(res["temperature"].iloc[0])
    assert res["humidity"].iloc[0] == 71


def test_shared_chronological_splits_and_no_overlap():
    times = pd.date_range("2024-09-02", periods=100, freq="1min")
    frame = pd.DataFrame([{"timestamp": t, "sensor_id": sensor, "temperature": i,
                            "humidity": 70, "temperature_count": 12, "humidity_count": 12}
                           for i, t in enumerate(times) for sensor in ("SENSOR01", "SENSOR02")])
    parts, boundaries = chronological_split(frame)
    assert [len(parts[k]) for k in ("train", "validation", "test")] == [140, 30, 30]
    assert set(parts["train"].timestamp).isdisjoint(parts["validation"].timestamp)
    assert set(parts["validation"].timestamp).isdisjoint(parts["test"].timestamp)
    assert parts["train"].timestamp.max() < pd.Timestamp(boundaries["validation_start"])
    assert parts["validation"].timestamp.max() < pd.Timestamp(boundaries["test_start"])


def test_multiple_sensors_preserve_identity(tmp_path):
    files = []
    for sensor in ("SENSOR01", "SENSOR02"):
        files.append(write_sensor(tmp_path, f"{sensor}_preprocessed.csv", {
            "date": ["02.09.2024"] * 12,
            "time": [t.strftime("%H:%M:%S") for t in pd.date_range("2024-09-02 00:00:05", periods=12, freq="5s")],
            "temperature": [6.0] * 12, "humidity": [70] * 12}))
    combined = prepare_sensors(files)
    assert set(combined.sensor_id) == {"SENSOR01", "SENSOR02"}
    assert len(combined) == 2
    with pytest.raises(ValueError, match="Duplicate sensor IDs"):
        prepare_sensors([files[0], files[0]])
