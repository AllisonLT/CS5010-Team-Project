# Checkpoint 3 — Persistence forecasting

## Scope
A baseline only: each sensor's most recently observed minute-level temperature
is used as the prediction for exactly 15, 30, or 60 minutes later. No model
training, feature engineering, inference API changes, or test-set evaluation.

## Install and run (PowerShell, repository root)

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".\services\ml[dev]"
.\.venv\Scripts\python.exe -m pytest services\ml\tests -v
.\.venv\Scripts\python.exe services\ml\scripts\evaluate_baseline.py --validation data\processed\forecasting\validation.csv
```

Default outputs:

- `data/processed/forecasting/baseline/validation_predictions.csv`
- `data/processed/forecasting/baseline/validation_metrics.json`

To evaluate only 15 and 30 minutes, add `--horizons 15 30`.

## Input contract
`validation.csv` from Checkpoint 2, with columns `timestamp`, `sensor_id`,
`temperature` (extra columns are ignored). Timestamps must be naive and aligned
to one-minute boundaries. Pairing is by exact timestamp, not row offset.

## Leakage protection
The script reads **validation.csv only** and pairs origin and target timestamps
**within this same file** for each sensor. This conservatively excludes forecasts
that need an origin in training or a target in testing. It never interpolates
missing readings. Do not use `test.csv` to tune or compare models in M5.

## Metric definitions
Per sensor and horizon, `MAE = mean(abs(target - forecast))` and
`RMSE = sqrt(mean((target - forecast)^2))`. Overall metrics pool prediction pairs
across sensors (weighted by the number of valid pairs per sensor).
Count=0 has `null` MAE/RMSE rather than a fabricated number.

## Known limitations
- Persistence assumes no change in temperature; rapid warming/cooling will cause errors.
- Temperature units and timezone remain unconfirmed in the supplied CSV metadata;
  outputs inherit the source unit and naive timestamps.
- A global model in Checkpoint 4 must use identical forecast origins/targets
  when comparing with these baseline results. No predictions are produced for
  missing targets or origins.
- A rolling operational forecast could use history before validation starts,
  but this conservative milestone evaluation intentionally does not.
