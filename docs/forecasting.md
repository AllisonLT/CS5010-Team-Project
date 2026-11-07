# Checkpoint 4 — Global multi-sensor forecasting models

## Scope

Train a separate **direct regression model per horizon (15, 30, 60 minutes)** using the three sensor time series already processed by Checkpoint 2. The three candidate families are Ridge, RandomForestRegressor and HistGradientBoostingRegressor. All models for a given horizon train on the **same example set**; each is compared against persistence on **its exact validation examples**.

## Install (PowerShell, repository root)

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".\services\ml[dev]"
.\.venv\Scripts\python.exe -m pytest services\ml\tests -v
```

## Train/validate (never pass test.csv)

```powershell
.\.venv\Scripts\python.exe services\ml\scripts\train_forecasting_models.py --train data\processed\forecasting\train.csv --validation data\processed\forecasting\validation.csv
```

Outputs:

- `data/processed/forecasting/ml/validation_ml_metrics.json` — model MAE/RMSE and matched persistence MAE/RMSE per horizon and sensor; includes best ML model by lowest pooled validation MAE then RMSE, plus a persistence-aware recommended method.
- `data/processed/forecasting/ml/validation_ml_predictions.csv` — the exact per-example validation comparisons.
- `models/artifacts/forecasting/{ridge,random_forest,hist_gradient_boosting}_{15,30,60}min.joblib` — nine trained artifacts containing scikit-learn pipeline, feature names, model name and horizon.

## Leakage protections

- `train.csv` and `validation.csv` are loaded **independently**. Strictly earlier training timestamps are required. The pipeline does not read `test.csv`.
- Per-sensor lags are generated on a one-minute timestamp grid; missing timestamp rows become missing values, not artificial short lags.
- Rolling means, minima and maxima are trailing and include the current origin, never future readings.
- A forecast is eligible only if all feature columns and the **actual target at precisely `origin + horizon`** are valid within the same partition. Forecast examples touching long outages are excluded.
- One global model is trained per model family and horizon, with sensor ID one-hot encoded from training data only. The scaler is fit on training data only.
- Matched persistence metrics may differ from Checkpoint 3 metrics because feature history requirements reduce eligibility. Compare within the **Checkpoint 4 report**, not the original unfiltered baseline numbers.

## Important limitations

- Initial feature policy requires complete temperature and humidity history (60-minute lag; 30-minute rolling window) and so excludes additional observations at split starts and after outages. This is conservative; feature eligibility may be relaxed using training-only imputation if needed later.
- The model uses only present/past humidity, not future humidity. All sensor histories are treated separately; a sensor does not use another sensor's contemporaneous readings.
- Sensor IDs are one-hot features. A completely new sensor is not meaningfully validated by this experiment.
- Nine days of measurements are **not** enough to establish performance across seasons or facilities. Sudden temperature spikes are hard to anticipate without additional exogenous variables such as door activity.
- The source CSV's temperature unit and timestamp timezone remain unconfirmed by this pipeline.
- Model selection uses validation data only. Hold out test data until the final evaluation protocol is frozen. Select models per horizon rather than automatically asserting ML beats persistence; if no model wins on validation, persistence may be a better production fallback.
- `joblib.load` unpickles objects: **load only artifacts created by trusted project code**, never files submitted by a user. Reproduce dependencies and Python version when loading.

## Verify

Inspect (1) all pytest tests; (2) three horizon entries and nine model entries in the metrics report; (3) model/persistence counts match within each model report; (4) nine joblib artifacts exist; (5) model errors are not falsely claimed to beat persistence; (6) the output timestamp boundaries are within validation. Submit the `validation_ml_metrics.json` and pytest summary for review before Checkpoint 5.
