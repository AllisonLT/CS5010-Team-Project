# CS5010 Smart Cold Storage Monitor — ML Forecasting & Warning Handoff

**Status:** Local Python forecasting and predictive-warning implementation through Checkpoint 5 (November 2026).  
**Repository:** https://github.com/AllisonLT/CS5010-Team-Project  
**Scope:** Python preprocessing, offline model training/evaluation, and local FastAPI inference. This does **not** mean JavaFX → Spring Boot → FastAPI → MongoDB integration is complete.

> **Start here if you are a teammate setting this up:** Clone/pull the shared repository, obtain the three source sensor CSVs, create a Python 3.11 virtual environment, install the Python package, reproduce processed data and saved artifacts, run tests, then start FastAPI and submit a sample request. **Do not expect `data/` or `models/artifacts/` files to arrive through Git**—they are ignored intentionally. The steps below assume the Checkpoint 2–5 source files have been committed to your branch; check this before running them.

## 1. Work completed

| Phase | What was implemented | Key outputs |
|---|---|---|
| Exploratory data analysis | Examined SENSOR01, SENSOR02, SENSOR04 readings, missingness, gaps, and temperature/humidity patterns. | Quality reports/plots (local). |
| Checkpoint 2 — preprocessing | Parse the sensor CSVs; resample original ~5-second measurements to **one-minute means**; reject insufficiently covered minutes without fabricating readings; chronological **70% train / 15% validation / 15% test** split with common timestamp boundaries. | `data/processed/forecasting/{all_sensors,train,validation,test}.csv`, `preprocessing_report.json`. |
| Checkpoint 3 — baseline | Persistence forecasts: `predicted_temperature(t+h) = temperature(t)`; validation metrics for **15, 30, 60 minutes**. | `data/processed/forecasting/baseline/`. |
| Checkpoint 4 — forecasting | Compared Ridge, Random Forest, HistGradientBoosting and experimental residual models with the persistence baseline. The point-forecast method currently used by FastAPI is **damped trend** (saved lag/weight configuration per horizon). | `models/artifacts/forecasting/trend/damped_trend_{15,30,60}min.json`. |
| Checkpoint 4 — warnings | Trained quantile-regression models estimating an **upper quantile of future maximum temperature** for each horizon; evaluated threshold-crossing precision/recall and distinct incident lead time. | `models/artifacts/forecasting/warnings/peak_quantile_{15,30,60}min.joblib`; local validation reports. |
| Checkpoint 5 — FastAPI | Model-backed endpoint for forecasts, a readiness check, immediate current-threshold check, and demonstration-only stateful alert evaluation. | `services/ml/src/cold_storage_ml/{app,inference,alert_policy}.py`; tests and demo request builder. |

### Summary of validation findings

These are **validation-period** results, **not** a final held-out test score or a guarantee for newly uploaded sensors.

| Horizon | Persistence MAE (matched forecast examples) | Damped-trend MAE | Approx. relative MAE reduction |
|---|---:|---:|---:|
| 15 minutes | 0.339 | 0.297 | 12.3% |
| 30 minutes | 0.576 | 0.536 | 7.0% |
| 60 minutes | 0.995 | 0.930 | 6.5% |

At an **illustrative threshold of 10 source-temperature units**, the future-peak warning models showed the following *forecast-window* precision/recall:

| Horizon | Precision | Recall |
|---|---:|---:|
| 15 minutes | 45.9% | 69.9% |
| 30 minutes | 47.6% | 88.7% |
| 60 minutes | 53.5% | 84.9% |

Incident-level analysis identified **8 confirmed crossings**, **7 scorable** given available history, and **7/7 warned** on validation. Median advance warning times were **13, 26, and 44 minutes** for the 15-, 30-, and 60-minute warning models respectively. This is only **seven evaluable incidents** and should not be presented as proven 100% real-world detection reliability.

**Important methodological note:** The damped-trend lag/weight parameters were selected on the same validation split on which their above scores were reported. Those gains are **exploratory and may be optimistic**. Keep `test.csv` untouched until the agreed final evaluation; ideally redo tuning within a chronological inner split of `train.csv` before final test assessment.

## 2. Current architecture and dataset behavior

```text
CURRENT LOCAL ML WORKFLOW
Raw per-sensor CSV files (local)
   │
   ▼
preprocess_dataset.py → one-minute data → train.csv / validation.csv / test.csv
   │                               │
   │                               ├── Offline training/evaluation scripts
   │                               │      └── saved trend JSON + peak-model joblib artifacts
   │                               ▼
   └── all_sensors.csv → make_forecast_request.py → sample_forecast_request.json
                                                       │ HTTP POST
                                                       ▼
                                         FastAPI temperature prediction endpoint
                                                       │
                                                       ▼
                                      Timestamped forecasts + warning indicators
```

**What `POST /api/v1/predictions/temperature` actually does:** It does **not** read a whole CSV or query MongoDB. It receives one selected sensor's observed history **as JSON**. It requires at least **61 consecutive one-minute readings** (a 60-minute elapsed period, including both endpoints). From the final history timestamp `t`, it returns direct point forecasts at **`t+15`, `t+30`, and `t+60` minutes**, plus upper-quantile future-peak estimates and threshold-crossing indicators. No training occurs during inference; no ground-truth future labels are needed or used.

**Current demo selection behavior:** `make_forecast_request.py` deliberately selects the **first valid 61-reading window** for a reproducible API smoke test. It **does not select the newest window**. The API uses the history supplied by the caller; it does not automatically choose a location in the dataset.

**Offline evaluation is different:** The preprocessing and evaluation scripts create historical rolling forecasts and compare them with the recorded future observations. The general FastAPI endpoint does **not** backtest an uploaded CSV automatically.

**Planned integrated workflow (repository architecture):**

```text
JavaFX: upload/select a dataset, sensor, forecast horizons, threshold
    ↓
Spring Boot: handle upload and selection; resolve metadata in MongoDB;
             load CSV from backend-managed local file storage;
             prepare/select the most recent complete sensor history
    ↓ HTTP JSON
FastAPI: load locally trained artifacts, generate forecasts/warning estimates
    ↓ HTTP JSON
Spring Boot: persist prediction/alert/audit information to MongoDB
    ↓
JavaFX: show results and alerts
```

The repo's documented storage design is **CSV contents in `data/uploads/` and dataset metadata/application records in MongoDB**; uploading whole CSVs as MongoDB documents is **not** what the current architecture requires. The desktop should not query MongoDB or FastAPI directly. Backend integration is still outstanding.

## 3. Prerequisites and fresh Windows setup

Use **Windows PowerShell** and **Python 3.11** for consistency with this work. Python 3.14 is not the tested environment. Java/Maven/MongoDB are **not needed for the Python-only smoke test**; they will be needed for full application integration.

```powershell
# 1) Clone if necessary, or open your existing checkout
 git clone https://github.com/AllisonLT/CS5010-Team-Project.git
 cd CS5010-Team-Project

# 2) Check that the ML scripts have been committed/pulled
Get-ChildItem services\ml\scripts
Get-ChildItem services\ml\src\cold_storage_ml

# 3) Verify that a Python 3.11 installation is available
py -3.11 --version

# 4) Create the local Python environment (run once)
py -3.11 -m venv .venv

# 5) Install local ML service and test dependencies
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -e ".\services\ml[dev]"

# 6) Run tests
.\.venv\Scripts\python.exe -m pytest services\ml\tests -v
```

If the Python launcher cannot find 3.11, install Python 3.11 first and rerun `py -3.11 --version`. The virtual environment is intentionally **not tracked by Git**.

**Known legacy-test adjustment:** The starter's `services/ml/tests/test_health.py` originally asserted `404` from `/api/v1/predictions/temperature`, because that endpoint had not existed. It now exists and correctly returns **422** for an empty `{}` payload. Replace the old test with a test expecting **422** rather than changing the endpoint back to 404. In the original local Checkpoint 5 test run, the outdated test was the only failure (73 passed, 1 failed); retest after updating it.

## 4. Obtain the source CSVs and run preprocessing

The work so far used files named:

```text
data/raw/SENSOR01_preprocessed.csv
data/raw/SENSOR02_preprocessed.csv
data/raw/SENSOR04_preprocessed.csv
```

Place these under `data/raw/` after obtaining them from the dataset source or a teammate. The original data was downloaded from the Zenodo record: **https://zenodo.org/records/15130001**. Check the source file names, attribution, column definitions, units, and license. The preprocessing script accepts per-sensor CSVs containing `date` + `time` + `temperature` (and humidity), or an ISO `timestamp`; it handles semicolon/comma separators. Sensor IDs are inferred from filenames.

```powershell
# Run from repository root
.\.venv\Scripts\python.exe services\ml\scripts\preprocess_dataset.py `
    --inputs `
        data\raw\SENSOR01_preprocessed.csv `
        data\raw\SENSOR02_preprocessed.csv `
        data\raw\SENSOR04_preprocessed.csv `
    --output-dir data\processed\forecasting
```

Expected files:

```text
data/processed/forecasting/
  all_sensors.csv
  train.csv
  validation.csv
  test.csv
  preprocessing_report.json
```

The preprocessing policy uses **1-minute means**, minimum **75% coverage per minute**, retains missing bins rather than imputing them, and splits chronologically by shared timestamp boundaries. Run the same processing settings as training, rather than feeding raw five-second CSV rows directly to FastAPI.

## 5. Recreate the trained artifacts locally

**Why this is required:** `.gitignore` excludes `models/artifacts/*`, so cloning the source code will not download the saved trained models. The following commands recreate the artifacts required by FastAPI. Run **after preprocessing**, from the repository root.

```powershell
# A. Reproduce the simple persistence baseline report
.\.venv\Scripts\python.exe services\ml\scripts\evaluate_baseline.py

# B. Train and compare the original ML candidates (Ridge/RF/HGB)
#    Useful for reproducing experiments; not required by current FastAPI inference.
.\.venv\Scripts\python.exe services\ml\scripts\train_forecasting_models.py

# C. Generate selected damped-trend configurations (REQUIRED by FastAPI)
.\.venv\Scripts\python.exe services\ml\scripts\evaluate_trend.py

# D. Train the future-peak warning models (REQUIRED by FastAPI)
#    The threshold configures the evaluation report, not a permanently fixed model threshold.
.\.venv\Scripts\python.exe services\ml\scripts\evaluate_threshold_warnings.py --threshold 10

# E. Optional: recreate incident-level warning lead-time reports
.\.venv\Scripts\python.exe services\ml\scripts\evaluate_warning_lead_time.py `
    --validation data\processed\forecasting\validation.csv `
    --predictions data\processed\forecasting\warnings\threshold_10_predictions.csv `
    --threshold 10
```

If you do not have `evaluate_warning_lead_time.py`, confirm the separate Checkpoint 4 lead-time files have been merged; the script is **optional** for starting FastAPI. The experimental `train_residual_models.py` is also optional and is **not used by the current inference endpoint**.

Verify the required files exist:

```powershell
Get-ChildItem models\artifacts\forecasting\trend\damped_trend_*min.json
Get-ChildItem models\artifacts\forecasting\warnings\peak_quantile_*min.joblib
```

Expected: **three trend JSON configs** and **three warning `.joblib` model files**, one each for 15, 30, 60 minutes.

**Security:** `.joblib` files use Python pickle-based serialization. Load only artifacts created and trusted by this project. Do not load arbitrary user-uploaded model files.

## 6. Run the FastAPI service

In **PowerShell terminal A**, from the repo root:

```powershell
$env:FORECAST_ARTIFACT_DIR = Join-Path (Get-Location) "models\artifacts\forecasting"
.\.venv\Scripts\python.exe -m uvicorn cold_storage_ml.app:app --host 127.0.0.1 --port 8000
```

Keep terminal A open. Run **a single Uvicorn worker** for the demo alert state.

In **PowerShell terminal B**, from the same repo root:

```powershell
# Service liveness
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health

# Model availability (should return status 'ready' and horizons 15/30/60)
Invoke-RestMethod http://127.0.0.1:8000/api/v1/ready

# Build an example request from a local processed dataset
.\.venv\Scripts\python.exe services\ml\scripts\make_forecast_request.py `
    --input data\processed\forecasting\all_sensors.csv `
    --sensor SENSOR02 `
    --threshold 10

# Send the request
$body = Get-Content -Raw data\processed\forecasting\sample_forecast_request.json
Invoke-RestMethod `
    -Method Post `
    -Uri http://127.0.0.1:8000/api/v1/predictions/temperature `
    -ContentType "application/json" `
    -Body $body | ConvertTo-Json -Depth 10
```

Local interactive documentation: **http://127.0.0.1:8000/docs**.

For the earlier local SENSOR02 demonstration (origin **2024-09-02 10:37**), FastAPI returned point predictions approximately **10.992, 10.825, 10.517** at +15/+30/+60 minutes, alongside peak estimates **11.847, 12.582, 14.283**. The threshold was 10, and current observed temperature **11.325** was already above it. A teammate using the same processed data, saved configurations and versions can compare with those values; differences may result from source files, preprocessing, model versions, or training seeds.

## 7. FastAPI endpoint reference

| Method and path | What it does | Trained model required? |
|---|---|---|
| `GET /api/v1/health` | Check FastAPI process liveness. | No |
| `GET /api/v1/ready` | Verify that all required trend/peak artifacts load; 503 if unavailable. | Checks artifacts |
| `POST /api/v1/predictions/temperature` | **Stateless** 15/30/60-minute point forecasts and estimated future peaks using one sensor's provided history. | **Yes** |
| `POST /api/v1/alerts/current` | **Stateless** comparison of current observed temperature to a threshold (`>=`). | No |
| `POST /api/v1/alerts/evaluate` | Forecast plus **in-memory** alert severity, escalation, deduplication and clearing demonstration. Requires all three horizons and chronological requests. | **Yes** |

### Temperature prediction request

```json
{
  "sensor_id": "SENSOR02",
  "threshold": 10.0,
  "horizons_minutes": [15, 30, 60],
  "history": [
    {"timestamp": "2024-09-02T09:37:00", "temperature": 11.2, "humidity": 74.0},
    {"timestamp": "2024-09-02T09:38:00", "temperature": 11.3, "humidity": 74.1}
  ]
}
```

**Schema illustration only:** real requests require **61 or more readings**, most recent **61 consecutive at 1-minute intervals**, valid finite temperature/humidity, humidity in `[0, 100]`, increasing unique whole-minute timezone-naive timestamps and sensor IDs supported by saved artifacts (currently **SENSOR01, SENSOR02, SENSOR04**). Unknown sensors or invalid histories are rejected. `horizons_minutes` may be any unique non-empty subset of `[15,30,60]` for this prediction endpoint.

Each returned horizon has: `target_timestamp`, `predicted_temperature`, `predicted_peak_temperature`, `predicted_threshold_crossing`, `method`, `peak_method`.

Key semantics:

- `predicted_temperature` is the damped-trend **point forecast at one future timestamp**.
- `predicted_peak_temperature` is a **90th-quantile estimate of the maximum** during the next *h* minutes, useful as a warning indicator. It is **not** a calibrated event probability or guaranteed maximum.
- `predicted_threshold_crossing` is `true` only when **current temperature is below threshold** and estimated future peak reaches/exceeds it.
- `currently_above_threshold` separately flags an observed violation; this can be `true` while every `predicted_threshold_crossing` is `false`.
- `predicted_min_temperature` / `predicted_max_temperature` are min/max of the **requested point forecasts**, **not** a full continuous-interval minimum/maximum; see `min_max_scope`.
- Temperature unit and timezone of source data have **not yet been formally established**. The threshold must be in the same numeric unit as readings. Do not assume Celsius in UI or reports until verified.

### Other endpoints and example calls

```powershell
# Immediate observed violation (does not use ML)
Invoke-RestMethod -Method Post `
    -Uri http://127.0.0.1:8000/api/v1/alerts/current `
    -ContentType 'application/json' `
    -Body '{"sensor_id":"SENSOR02","temperature":12.5,"threshold":10}'

# Stateful evaluation for local demo; use the sample history JSON with ALL horizons
Invoke-RestMethod -Method Post `
    -Uri http://127.0.0.1:8000/api/v1/alerts/evaluate `
    -ContentType 'application/json' `
    -Body $body | ConvertTo-Json -Depth 10
```

Alert severity (highest to lowest): `current_violation`, `imminent` (+15m crossing), `warning` (+30m crossing), `advisory` (+60m crossing), `clear`. Repeated eligible warnings are deduplicated; three consecutive eligible clear evaluations are needed to clear the state. This state is **RAM-only and resets on FastAPI restart**. It is **not** MongoDB persistence and should not be treated as a production notification system.

### Expected errors and troubleshooting

| Symptom | Likely explanation / action |
|---|---|
| `422` for `{}` or malformed prediction payload | Correct request validation: add required sensor history/threshold. Old scaffold test expecting 404 is obsolete. |
| `422` insufficient history or unknown sensor | Supply supported sensor ID and a complete last-61-reading window; inspect CSV gaps. |
| `503` from `/ready` or predictions | Missing/incompatible model artifacts; rerun `evaluate_trend.py` and `evaluate_threshold_warnings.py`, verify `FORECAST_ARTIFACT_DIR`. |
| `409` on `/alerts/evaluate` | Stateful evaluations require increasing timestamps per sensor/threshold; replay older windows only via **stateless** `/predictions/temperature`, or restart the demo server to reset alert state. |
| PowerShell can't locate `py -3.11` | Install Python 3.11 / check launcher, then recreate `.venv`. |
| `FileNotFoundError` for source CSV | Obtain original data; Git intentionally excludes `data/raw/` contents. |
| `ModuleNotFoundError: cold_storage_ml` | Re-run editable installation with the selected `.venv` Python and run commands from repo root. |
| `/docs` unavailable | Confirm Uvicorn is running and port 8000 is free; local host binding is `127.0.0.1`. |

## 8. Reproducing warning and lead-time results

The offline warning evaluator scores **forecast windows** using future observations in the validation split (only for evaluation, never as runtime input). The lead-time analysis groups continuous measured threshold violations into **distinct incidents**, matches preceding warnings, and reports first-warning lead time and false-warning episodes.

Outputs expected after the optional evaluation commands:

```text
data/processed/forecasting/
  baseline/validation_metrics.json
  ml/validation_ml_metrics.json
  trend/validation_trend_metrics.json
  warnings/threshold_10_metrics.json
  warnings/threshold_10_predictions.csv
  warnings/lead_time/warning_incidents.csv
  warnings/lead_time/warning_window_matches.csv
  warnings/lead_time/warning_incident_metrics.json
```

All outputs are local, typically ignored by Git. Reports based on the validation set must be described as validation findings. **Do not run or optimize using the held-out `test.csv`** until the final team-agreed evaluation stage.

## 9. Integration handoff / remaining work

1. **Confirm and freeze the API contract** with the backend team (GitHub issues [#16](https://github.com/AllisonLT/CS5010-Team-Project/issues/16), [#31](https://github.com/AllisonLT/CS5010-Team-Project/issues/31)). Agree on JSON examples, sensor IDs, temperature units, time zones, what min/max means, and exact error formats.
2. **Backend dataset selection and persistence:** Spring Boot should load an uploaded CSV from the backend-managed local file path, with dataset metadata in MongoDB. Select the **latest complete 61-reading window**, not the first one used by our test script. Treat stale/missing recent data explicitly rather than silently presenting an old prediction as current.
3. **Java-to-Python integration:** Implement the typed Spring Boot HTTP client (issue [#26](https://github.com/AllisonLT/CS5010-Team-Project/issues/26)), timeout/error handling ([#27](https://github.com/AllisonLT/CS5010-Team-Project/issues/27)), and end-to-end contract tests ([#28](https://github.com/AllisonLT/CS5010-Team-Project/issues/28)). JavaFX should call Spring Boot, **not FastAPI directly**.
4. **Durable alerts:** Put real alert lifecycle, notification delivery, history and audit events under Spring Boot/MongoDB ownership. The Python in-memory registry is only for demonstrating the policy.
5. **Model limitations:** Current artifacts support only the trained SENSOR01/SENSOR02/SENSOR04 IDs and complete one-minute histories. Agree on a mapping/retraining strategy for uploads containing new sensors. Validate on genuinely unseen data before accuracy claims.
6. **Other inference workstream:** Repository issue [#4](https://github.com/AllisonLT/CS5010-Team-Project/issues/4) also includes a spoilage endpoint, which this forecasting-focused contribution has **not** implemented.
7. **Reproducibility:** Capture training configuration, package versions, artifact provenance and a distribution strategy for models; neither uploaded datasets nor `.joblib` artifacts are committed by default.

### Git hygiene

Current ignore rules appropriately keep these machine-specific outputs out of Git:

```gitignore
.venv/
data/raw/*
!data/raw/.gitkeep
data/processed/*
!data/processed/.gitkeep
data/uploads/*
!data/uploads/.gitkeep
models/artifacts/*
!models/artifacts/.gitkeep
```

**Commit the Python source, tests, docs and `services/ml/pyproject.toml`; do not commit full raw data, processed datasets, secrets or generated artifacts.** Use `git status --short` before committing. This handoff is intended to live at `docs/ML_FORECASTING_HANDOFF.md` (copy/rename as needed).

---

**Recommended teammate verification:** Confirm `py -3.11 --version`; install dependencies; preprocess the three CSVs; build six required artifacts; run `pytest`; start FastAPI; verify `/health` and `/ready`; send the SENSOR02 smoke-test JSON; inspect all three forecast timestamps and threshold fields. **That constitutes a successful local reproduction of the Python-side work**, not completion of Java/MongoDB integration.
