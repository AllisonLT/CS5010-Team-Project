# Checkpoint 4 extension: Forecast temperature-threshold warnings

## Scope
Research/evaluation only. Does not implement FastAPI (Checkpoint 5).
This is **future threshold crossing**, not detection of every rapid temperature spike. A fast rise that remains below the configured threshold is not labeled a warning event. Separately, a current violation is a **present alarm**, not a forecast success.

## Definition
At origin t with current temperature **below** threshold K, the label is true if **any** observed 1-minute future temperature in (t, t+h] is >= K. Requires all h future minutes to be present; otherwise omit the example. Do not count an already-above-threshold origin as a newly predicted threshold crossing. Consecutive origins may describe the same physical event; metrics are per origin, not per independent incident.

A HistGradientBoostingRegressor with quantile loss (fixed 0.90) estimates the upper 90th-quantile of the future **maximum temperature**. Warning flag = `predicted_peak_temperature >= threshold` (only when currently below). The quantile estimate is *not* a calibrated probability of exceeding a threshold or a guarantee. The numeric threshold is supplied at run time; the model need not be retrained for different thresholds.

This warning model is separate from the best forecast for temperature at exactly t+h. Do not replace the point-temperature regression output with the peak quantile.

## Leakage safeguards
- Per-sensor past-only features and strict preceding 60-minute history from Checkpoint 4.
- Target maximum uses only timestamps t+1 to t+h and stays strictly within the supplied partition.
- No interpolation across missing intervals or split boundaries.
- Train only on `train.csv`; fixed quantile and hyperparameters; validation metrics are not used to select parameters in this script.
- Held-out `test.csv` remains untouched. Repeated analysis/selection on validation will still make validation optimistic.

## Run from repo root (Windows PowerShell)

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".\services\ml[dev]"
.\.venv\Scripts\python.exe -m pytest services\ml\tests -v
.\.venv\Scripts\python.exe services\ml\scripts\evaluate_threshold_warnings.py --threshold 10
```

Use a threshold appropriate to your specific stored product and confirmed temperature unit; `10` is an **illustration**, not a food-safety recommendation. Optional `--threshold 15` to inspect a different threshold. Models are fitted again on reruns; no test partition is opened.

Outputs in `data/processed/forecasting/warnings/`:
- `threshold_10_predictions.csv`
- `threshold_10_metrics.json`

Model artifacts in `models/artifacts/forecasting/warnings/`:
- `peak_quantile_15min.joblib`, `peak_quantile_30min.joblib`, `peak_quantile_60min.joblib`

## Interpreting the report
- **Recall**: fraction of actual future threshold crossings alerted.
- **Precision**: fraction of warnings followed by a threshold crossing.
- **False positive rate**: fraction of no-crossing forecast origins warned.
- When no positive events occur, recall is null. When no warnings fire, precision is null. These are not zero-score failures; the threshold or period may simply lack events.
- For a meaningful alarm, compare false alarms, recall and timeliness, especially ahead of sudden warming. Overall point-forecast MAE alone does not establish warning quality.
- False alarms/recall measured over many overlapping minute origins are correlated. This is a demonstration model trained on roughly nine days in one room, not a validated food-safety protection system.

## Next steps
After local tests, review output metrics and predicted-peak samples for actual crossing events. If warning recall is insufficient, evaluate alternate quantiles **using inner-train validation**, and freeze before final held-out testing. Integration into FastAPI remains Checkpoint 5.
