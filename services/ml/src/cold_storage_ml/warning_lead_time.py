"""Offline incident-level evaluation of threshold warnings.

Uses future observations ONLY to score forecasts, never to generate them.
The observable incident onset requires a directly preceding, below-threshold
minute: otherwise it is left-censored / uncertain and excluded from recall.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from cold_storage_ml.baseline import validate_partition

REQUIRED_PREDICTION_COLUMNS = {
    "sensor_id", "horizon_minutes", "origin_timestamp", "window_end_timestamp",
    "current_temperature", "currently_above_threshold", "predicted_future_crossing",
    "threshold",
}


def _boolean(series: pd.Series, label: str) -> pd.Series:
    if pd.api.types.is_bool_dtype(series):
        return series.astype(bool)
    values = series.astype(str).str.lower()
    if not values.isin(["true", "false"]).all():
        raise ValueError(f"{label} must contain true/false")
    return values.eq("true")


def validate_warnings(predictions: pd.DataFrame, threshold: float) -> pd.DataFrame:
    if not np.isfinite(threshold):
        raise ValueError("Threshold must be finite")
    missing = REQUIRED_PREDICTION_COLUMNS - set(predictions.columns)
    if missing:
        raise ValueError(f"Missing prediction columns: {sorted(missing)}")
    if predictions.empty:
        raise ValueError("No warning prediction rows")
    p = predictions.copy()
    for c in ["origin_timestamp", "window_end_timestamp"]:
        p[c] = pd.to_datetime(p[c], errors="coerce")
        if p[c].isna().any() or p[c].dt.tz is not None:
            raise ValueError(f"Invalid or timezone-aware {c}")
    for c in ["currently_above_threshold", "predicted_future_crossing"]:
        p[c] = _boolean(p[c], c)
    for c in ["threshold", "current_temperature", "horizon_minutes"]:
        p[c] = pd.to_numeric(p[c], errors="coerce")
    if not np.isfinite(p[["threshold", "current_temperature", "horizon_minutes"]].to_numpy()).all():
        raise ValueError("Non-finite numeric prediction fields")
    if not np.allclose(p.threshold, threshold, rtol=0, atol=1e-9):
        raise ValueError("Warning threshold does not match requested threshold")
    if ((p.horizon_minutes <= 0) | (p.horizon_minutes % 1 != 0)).any():
        raise ValueError("Horizon must be positive whole minutes")
    if (p.window_end_timestamp != p.origin_timestamp + pd.to_timedelta(p.horizon_minutes, unit="min")).any():
        raise ValueError("Warning target timestamps do not match horizon")
    if p.duplicated(["sensor_id", "horizon_minutes", "origin_timestamp"]).any():
        raise ValueError("Duplicate forecast origin for sensor/horizon")
    if (p.currently_above_threshold != (p.current_temperature >= threshold)).any():
        raise ValueError("currently_above_threshold inconsistent with current measurement")
    if (p.currently_above_threshold & p.predicted_future_crossing).any():
        raise ValueError("Future crossing warning cannot originate during active violation")
    return p.sort_values(["horizon_minutes", "sensor_id", "origin_timestamp"]).reset_index(drop=True)


def extract_incidents(observations: pd.DataFrame, threshold: float) -> pd.DataFrame:
    """Find contiguous above-threshold episodes and assess onset certainty.

    An onset is confirmed only if previous minute exists and is strictly below.
    NaN and absent timestamps break continuity, not implied normal readings.
    """
    if not np.isfinite(threshold):
        raise ValueError("Threshold must be finite")
    obs = validate_partition(observations)
    result = []
    for sensor, frame in obs.groupby("sensor_id", sort=True):
        frame = frame.sort_values("timestamp").reset_index(drop=True)
        temp = frame.temperature.to_numpy(dtype=float)
        times = frame.timestamp.tolist()
        above = np.isfinite(temp) & (temp >= threshold)
        j = 0
        incident_number = 0
        while j < len(frame):
            if not above[j]:
                j += 1
                continue
            start = j
            end = j
            while end + 1 < len(frame) and above[end + 1] and (times[end + 1] - times[end] == pd.Timedelta(minutes=1)):
                end += 1
            confirmed = (start > 0 and times[start] - times[start-1] == pd.Timedelta(minutes=1)
                         and np.isfinite(temp[start-1]) and temp[start-1] < threshold)
            if start == 0:
                reason = "partition_starts_above_threshold"
            elif times[start] - times[start-1] != pd.Timedelta(minutes=1):
                reason = "missing_preceding_minute"
            elif not np.isfinite(temp[start-1]):
                reason = "unknown_preceding_temperature"
            else:
                reason = "confirmed_onset"
            incident_number += 1
            result.append({
                "sensor_id": sensor, "incident_id": f"{sensor}_E{incident_number:03d}",
                "onset_timestamp": times[start], "last_above_timestamp": times[end],
                "duration_observed_minutes": end-start+1,
                "peak_temperature": float(np.max(temp[start:end+1])),
                "onset_confirmed": bool(confirmed), "onset_status": reason,
            })
            j = end + 1
    return pd.DataFrame(result, columns=["sensor_id", "incident_id", "onset_timestamp",
                "last_above_timestamp", "duration_observed_minutes", "peak_temperature",
                "onset_confirmed", "onset_status"])


def _episode_count(times: pd.Series) -> int:
    if len(times) == 0:
        return 0
    ordered = pd.Series(pd.to_datetime(times)).sort_values().drop_duplicates()
    return int(1 + (ordered.diff().iloc[1:] != pd.Timedelta(minutes=1)).sum())


def _stats(rows: pd.DataFrame, predictions: pd.DataFrame) -> dict:
    count = int(rows.onset_confirmed.sum()) if len(rows) else 0
    eligible = rows.loc[rows.onset_confirmed & rows.scorable] if len(rows) else rows
    hit = eligible.loc[eligible.detected] if len(eligible) else eligible
    lead = hit.lead_minutes.astype(float).to_numpy() if len(hit) else np.array([])
    false = predictions.loc[predictions.false_warning] if len(predictions) else predictions
    return {
        "confirmed_incidents": count,
        "scorable_incidents": int(len(eligible)),
        "unscorable_confirmed_incidents": int(count - len(eligible)),
        "detected_incidents": int(len(hit)),
        "missed_scorable_incidents": int(len(eligible) - len(hit)),
        "incident_recall": float(len(hit)/len(eligible)) if len(eligible) else None,
        "median_lead_minutes": float(np.median(lead)) if len(lead) else None,
        "mean_lead_minutes": float(np.mean(lead)) if len(lead) else None,
        "min_lead_minutes": float(np.min(lead)) if len(lead) else None,
        "max_lead_minutes": float(np.max(lead)) if len(lead) else None,
        "detected_with_at_least_5min_notice": int((lead >= 5).sum()),
        "detected_with_at_least_10min_notice": int((lead >= 10).sum()),
        "detected_with_at_least_15min_notice": int((lead >= 15).sum()),
        "unmatched_warning_windows": int(len(false)),
        "unmatched_warning_episodes": int(sum(_episode_count(g.origin_timestamp) for _,g in false.groupby("sensor_id"))) if len(false) else 0,
    }


def evaluate_lead_time(observations: pd.DataFrame, predictions: pd.DataFrame, threshold: float) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    """Match warning origins to known, confirmed future incident onsets.

    For each horizon and sensor an incident is eligible if at least one valid
    currently-below origin lies in [onset-horizon, onset). Earliest positive
    eligible origin determines lead time. A false warning window is one whose
    forecast horizon contains no confirmed crossing onset. Uncertain onsets
    are explicitly not counted as positives or false warnings.
    """
    p = validate_warnings(predictions, threshold)
    events = extract_incidents(observations, threshold)
    sensors = set(validate_partition(observations).sensor_id)
    if not set(p.sensor_id).issubset(sensors):
        raise ValueError("Predictions refer to sensors missing from observation data")
    incident_rows = []
    warning_rows = []
    by_horizon = {}
    for horizon, group_h in p.groupby("horizon_minutes", sort=True):
        per_sensor = {}
        for sensor, g in group_h.groupby("sensor_id", sort=True):
            g = g.sort_values("origin_timestamp").copy()
            e = events[events.sensor_id == sensor] if len(events) else events
            onset_confirmed = e[e.onset_confirmed] if len(e) else e
            onset_unknown = e[~e.onset_confirmed] if len(e) else e
            g["matched_confirmed_incident"] = False
            g["overlaps_uncertain_onset"] = False
            for event in onset_confirmed.itertuples(index=False):
                valid = ((g.origin_timestamp < event.onset_timestamp) &
                         (g.window_end_timestamp >= event.onset_timestamp) &
                         (~g.currently_above_threshold))
                candidate = g.loc[valid]
                predicted = candidate.loc[candidate.predicted_future_crossing]
                if len(predicted):
                    g.loc[predicted.index, "matched_confirmed_incident"] = True
                earliest = predicted.origin_timestamp.min() if len(predicted) else pd.NaT
                incident_rows.append({**event._asdict(), "horizon_minutes": int(horizon),
                    "scorable": bool(len(candidate)), "detected": bool(len(predicted)),
                    "first_warning_timestamp": earliest,
                    "lead_minutes": float((event.onset_timestamp-earliest).total_seconds()/60) if len(predicted) else np.nan})
            for event in onset_unknown.itertuples(index=False):
                plausible = ((g.origin_timestamp < event.onset_timestamp) &
                             (g.window_end_timestamp >= event.onset_timestamp))
                g.loc[plausible, "overlaps_uncertain_onset"] = True
                incident_rows.append({**event._asdict(), "horizon_minutes": int(horizon),
                    "scorable": False, "detected": False,
                    "first_warning_timestamp": pd.NaT, "lead_minutes": np.nan})
            g["false_warning"] = (g.predicted_future_crossing & ~g.matched_confirmed_incident &
                                  ~g.overlaps_uncertain_onset)
            warning_rows.append(g)
            rows = pd.DataFrame([x for x in incident_rows if x["horizon_minutes"] == horizon and x["sensor_id"] == sensor])
            per_sensor[sensor] = _stats(rows, g)
        by_horizon[str(int(horizon))] = {"overall": _stats(
            pd.DataFrame([x for x in incident_rows if x["horizon_minutes"] == horizon]),
            pd.concat([g for g in warning_rows if int(g.horizon_minutes.iloc[0]) == horizon], ignore_index=True)),
            "per_sensor": per_sensor}
    inc = pd.DataFrame(incident_rows)
    warn = pd.concat(warning_rows, ignore_index=True)
    report = {"threshold": float(threshold), "evaluation_partition": "validation",
        "incident_definition": "consecutive observed minutes with temperature >= threshold, confirmed onset only after directly preceding below-threshold minute",
        "lead_time_definition": "first positive warning's timestamp preceding onset, when onset within its horizon",
        "scope_note": "Offline evaluation only; uses observed future data to score predictions, not to generate them; windows overlap and are not independent events",
        "uncertainty_note": "Boundary/gap-censored onsets excluded from event recall. Scorable means at least one available eligible forecast origin. False warning episodes are contiguous unmatched positive origins, not deduplicated device notifications.",
        "horizons": by_horizon}
    return inc, warn, report
