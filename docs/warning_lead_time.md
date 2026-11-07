# Checkpoint 4 extension: incident-level warning lead-time evaluation

This is an **offline evaluation only**, not a new model or real-time notification service. It reads the previously generated quantile warning predictions and the original `validation.csv`. It does **not** access `test.csv` or change the fitted models.

## What is measured

A temperature incident is a contiguous sequence of observed **1-minute sensor readings at or above** the selected threshold. A confirmed onset must have an observed reading **strictly below** the threshold in the immediately preceding minute. When an incident starts at the validation boundary, after an absent minute, or after a missing reading, the onset is *uncertain* and excluded from onset recall. Periods that continue past the end of validation have a right-censored duration: the reported duration is only time observed within validation.

For a given forecast horizon H, an incident is **scorable** only if at least one valid, below-threshold forecast origin exists in [incident onset − H, incident onset). An incident is **detected** if one of those origin predictions warns of crossing. **Lead time** equals incident onset timestamp minus the earliest matching positive warning origin. Thus lead time is between 1 and H minutes, and cannot be positive when the first warning appears only after the incident began.

Incident-level recall = detected / scorable confirmed incidents. The evaluation also counts the number of detected incidents with >= 5, >= 10, and >= 15 minutes of lead time, plus warning windows with no confirmed onset in their prediction window. Consecutive unmatched warning windows are grouped into approximate *false warning episodes* per sensor and horizon. These episodes are **not** application notifications; there is no cooldown, hysteresis, or alert-state management yet.

Overlap with an uncertain onset is excluded from false-warning counts to avoid labeling an event with unobservable start as a false alarm. For confirmed onsets, the window-match rule is strict (origin < onset <= origin+H).

## Install

Extract ZIP contents into the repo root. These files are **new**; no existing service code is modified:

- `services/ml/src/cold_storage_ml/warning_lead_time.py`
- `services/ml/scripts/evaluate_warning_lead_time.py`
- `services/ml/tests/test_warning_lead_time.py`
- `docs/checkpoint4_warning_lead_time.md`

## Windows PowerShell

From the repo root:

```powershell
.\.venv\Scripts\python.exe -m pytest services\ml\tests -v

.\.venv\Scripts\python.exe services\ml\scripts\evaluate_warning_lead_time.py `
  --validation data\processed\forecasting\validation.csv `
  --predictions data\processed\forecasting\warnings\threshold_10_predictions.csv `
  --threshold 10
```

Outputs by default:

- `data/processed/forecasting/warnings/lead_time/warning_incidents.csv`
- `data/processed/forecasting/warnings/lead_time/warning_window_matches.csv`
- `data/processed/forecasting/warnings/lead_time/warning_incident_metrics.json`

The prediction file and selected `--threshold` must match. For other thresholds, retrain/re-evaluate the warning model with that threshold first, then pass its corresponding predictions CSV to this script.

## Reading the results

`warning_incidents.csv` contains incident onset, observed peak, observed length, whether onset is confirmed, whether it was scorable and detected, first warning timestamp, and lead time per horizon. One physical incident appears three times (once per horizon), so do not add those rows together and call them separate events.

`warning_window_matches.csv` adds `matched_confirmed_incident`, `overlaps_uncertain_onset`, and `false_warning` flags to each prediction window.

`warning_incident_metrics.json` contains pooled and per-sensor counts, recall, and lead-time summaries. `incident_recall: null` means there were no scorable incidents, **not** 0% model accuracy. Episode counts provide a coarse estimate of false-alarm bursts and are not a full alert-state simulation.

## Limitations

1. The threshold of 10 is illustrative; confirm dataset temperature units and application-specific safe limits.
2. Validation data were used for model development. These results are not unbiased test performance.
3. Correlated overlapping forecast origins inflate window counts; incident-level metrics provide a complementary view.
4. There are few distinct incidents in this short validation partition. High incident-level recall may be unstable.
5. Model-reported high-quantile temperature is not a calibrated probability of crossing.
6. An *early warning* in retrospective evaluation does not mean the model can foresee entirely unobserved environmental events.
7. Missing observations and split edges can censor incident onsets, reducing the number of scorable incidents.
8. This evaluation does not implement suppression, cooldown, direct current-violation alerts, or notifications. Those are separate future API/application tasks.
