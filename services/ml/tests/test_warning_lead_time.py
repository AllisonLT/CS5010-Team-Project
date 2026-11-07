import pandas as pd
import pytest
from cold_storage_ml.warning_lead_time import extract_incidents, evaluate_lead_time


def observations(values, sensor="SENSOR02"):
    idx = pd.date_range("2024-09-09 09:00", periods=len(values), freq="min")
    return pd.DataFrame({"timestamp": idx, "sensor_id": sensor, "temperature": values})


def predictions(obs, origins, horizon=5, trigger=None, threshold=10):
    trigger = set(trigger or [])
    rows=[]
    for i in origins:
        rows.append({"sensor_id": obs.sensor_id.iloc[0], "horizon_minutes": horizon,
             "origin_timestamp": obs.timestamp.iloc[i],
             "window_end_timestamp": obs.timestamp.iloc[i]+pd.Timedelta(minutes=horizon),
             "current_temperature":obs.temperature.iloc[i],
             "currently_above_threshold":bool(obs.temperature.iloc[i]>=threshold),
             "predicted_future_crossing":bool(i in trigger), "threshold":threshold})
    return pd.DataFrame(rows)


def test_one_incident_multiple_warning_windows_earliest_lead():
    o=observations([7]*8+[11]*4+[7]*5)
    p=predictions(o, range(1,8), trigger=[4,5,6,7])
    incidents,warnings,report=evaluate_lead_time(o,p,10)
    r=report["horizons"]["5"]["overall"]
    assert r["confirmed_incidents"]==1
    assert r["detected_incidents"]==1
    assert r["median_lead_minutes"]==4
    assert r["unmatched_warning_windows"]==0


def test_missed_incident():
    o=observations([7]*8+[11]*3+[7]*4)
    p=predictions(o, range(3,8))
    _,_,r=evaluate_lead_time(o,p,10)
    assert r["horizons"]["5"]["overall"]["missed_scorable_incidents"]==1


def test_false_warning_episodes_are_deduplicated():
    o=observations([7]*20)
    p=predictions(o, range(2,12), trigger=[2,3,4,8,9])
    _,_,r=evaluate_lead_time(o,p,10)
    v=r["horizons"]["5"]["overall"]
    assert v["unmatched_warning_windows"]==5
    assert v["unmatched_warning_episodes"]==2
    assert v["incident_recall"] is None


def test_nan_break_is_uncertain_not_confirmed():
    o=observations([7,8,float("nan"),11,11,7])
    e=extract_incidents(o,10)
    assert len(e)==1 and not bool(e.iloc[0].onset_confirmed)


def test_partition_left_censored():
    e=extract_incidents(observations([11,11,7,11,7]),10)
    assert len(e)==2
    assert not bool(e.iloc[0].onset_confirmed)
    assert bool(e.iloc[1].onset_confirmed)


def test_exact_threshold_is_crossing():
    e=extract_incidents(observations([9,10,10,9]),10)
    assert len(e)==1 and e.iloc[0].duration_observed_minutes==2


def test_no_warning_after_threshold_already_crossed():
    o=observations([7]*5+[11]*3+[7]*5)
    p=predictions(o,[3,4,5],trigger=[4])
    _,_,r=evaluate_lead_time(o,p,10)
    assert r["horizons"]["5"]["overall"]["detected_incidents"]==1


def test_reject_duplicate_forecast_keys():
    o=observations([7]*15)
    p=predictions(o,[1,1])
    with pytest.raises(ValueError,match="Duplicate"):
        evaluate_lead_time(o,p,10)


def test_reject_threshold_mismatch():
    o=observations([7]*15)
    p=predictions(o,[1])
    with pytest.raises(ValueError,match="threshold"):
        evaluate_lead_time(o,p,11)


def test_no_eligible_origin_means_unscorable():
    o=observations([7]*8+[11]*3+[7]*3)
    p=predictions(o,[0,1])
    _,_,r=evaluate_lead_time(o,p,10)
    x=r["horizons"]["5"]["overall"]
    assert x["confirmed_incidents"]==1 and x["scorable_incidents"]==0


def test_missing_minute_breaks_incident():
    o=observations([7,11,11,11,7]);o=o.drop(index=2)
    e=extract_incidents(o,10)
    assert len(e)==2 and bool(e.iloc[0].onset_confirmed) and not bool(e.iloc[1].onset_confirmed)
