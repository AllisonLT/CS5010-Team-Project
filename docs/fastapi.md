# Checkpoint 5 — FastAPI forecasting and warning contract

## Scope and compatibility

- Existing route `GET /api/v1/health` remains unchanged.
- New routes: `GET /api/v1/ready`, `POST /api/v1/predictions/temperature`, `POST /api/v1/alerts/current`, `POST /api/v1/alerts/evaluate`.
- This is local-only, model-backed Python inference. No modifications to the JavaFX or Spring Boot modules are included. Backend team should agree on JSON field names and timestamp/unit policy before integration.
- Saved artifacts must already exist from Checkpoint 4: `models/artifacts/forecasting/trend/damped_trend_{15,30,60}min.json` and `models/artifacts/forecasting/warnings/peak_quantile_{15,30,60}min.joblib`. The code does not retrain at request time. If they are absent, readiness returns 503.
- Trend selection was tuned on validation; its observed performance is exploratory. Test split untouched.

## Setup on PowerShell (repository root)

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".\services\ml[dev]"
.\.venv\Scripts\python.exe -m pytest services\ml\tests -v
$env:FORECAST_ARTIFACT_DIR = (Join-Path (Get-Location) 'models\artifacts\forecasting')
.\.venv\Scripts\python.exe -m uvicorn cold_storage_ml.app:app --host 127.0.0.1 --port 8000
```

Use **one Uvicorn worker** for the in-memory alert demo. From another PowerShell terminal at the repository root:

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
Invoke-RestMethod http://127.0.0.1:8000/api/v1/ready
.\.venv\Scripts\python.exe services\ml\scripts\make_forecast_request.py --input data\processed\forecasting\all_sensors.csv --sensor SENSOR02 --threshold 10
$body = Get-Content -Raw data\processed\forecasting\sample_forecast_request.json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/api/v1/predictions/temperature -ContentType 'application/json' -Body $body | ConvertTo-Json -Depth 10
```

Open `http://127.0.0.1:8000/docs` for FastAPI OpenAPI schema.

## Request schema

```json
{
  "sensor_id": "SENSOR02",
  "threshold": 10.0,
  "horizons_minutes": [15, 30, 60],
  "history": [
    {"timestamp": "2024-09-02T10:00:00", "temperature": 7.4, "humidity": 71.0}
  ]
}
```

The one-reading example above illustrates the JSON shape only; **61 or more consecutive 1-minute observations are required**. Timestamps must be naive, whole-minute, strictly increasing, unique, and consistent with the Checkpoint 2 processed CSV. Humidity is required; values must be finite within 0–100. All horizons are configurable among 15/30/60. Unknown sensor IDs are rejected (trained sensors only). Sensor input stays separate from other sensors. No missing values or timestamp gaps in the last 61 minutes. Rejection is 422.

`POST /api/v1/predictions/temperature` is *stateless* and can be repeated with the same request. Response includes sensor, forecast origin, source-unit temperature, threshold, `currently_above_threshold`, `predictions` (one per requested horizon), `predicted_min_temperature`, `predicted_max_temperature`, `min_max_scope` and caveats. Per horizon: `target_timestamp`, `predicted_temperature` (damped-trend point estimate), `predicted_peak_temperature` (90th-quantile estimator of max across the future interval), and `predicted_threshold_crossing`.

**Important:** The two min/max numbers summarize *the requested point predictions only*. They are NOT the actual full-period low/high. `predicted_peak_temperature` is an estimated upper quantile for a future period, not a guaranteed maximum and not a crossing probability. The model does not provide a justified estimate of continuous future minimum yet. Do not show these as safety guarantees.

The input CSV has no confirmed physical temperature unit/time zone; this API intentionally retains its original scale and naive timestamps. Before user-facing product safety claims, agree with teammates on canonical Celsius conversion and time-zone semantics. Thresholds use the same scale as the input readings.

## Warning policy

`POST /api/v1/alerts/current` with `{"sensor_id":"SENSOR02","temperature":10,"threshold":10}` performs an *immediate model-independent* observed threshold check (`>=`). This endpoint works even if model artifacts are missing.

`POST /api/v1/alerts/evaluate` accepts the forecasting request but **requires all three horizons**. It computes forecasts and returns `{"forecast": {...}, "alert": {"severity": ..., "event": ..., "deduplicated": ..., "clear_readings_remaining": ...}}`.

Severity:

- `current_violation`: measured temperature is already at/above threshold (highest priority).
- `imminent`: estimated crossing at the 15-minute horizon.
- `warning`: estimated crossing at the 30-minute horizon but not 15.
- `advisory`: estimated crossing at 60 but not at 15/30.
- `clear`: no observed or predicted violation.

`event` can be `raised`, `escalated`, `updated`, `cleared` or `none`. Repeated alerts for the same sensor and threshold do not generate additional `raised` events, and 3 consecutive clear request readings are required before clearing. If severity drops while still positive, the registry reports `updated` (in particular, it never labels a resolved current violation as still active). Requests must arrive in increasing timestamp order per `(sensor_id, threshold)` or return 409.

**The alert registry is only in RAM in one Python process.** It resets on restart, cannot synchronize multiple workers, and is not a durable audit/history store. For the final desktop application, Spring Boot/MongoDB must own persisted alert state, audit events, and device notification delivery. Run with one Uvicorn worker for this demonstration. This endpoint is not suitable for backfilling historical data out of order.

## Error behavior

- 422 invalid/missing columns/history/horizon/sensor or nonfinite values.
- 503 missing trained models or readiness failure.
- 409 out-of-order stateful alert submissions.
- 200 for a valid request, including predictions that do not cross the threshold.

Training data and test labels must not be sent as request features. Predictions are not proof of future events; incidents and lead time are only scored offline on observed future readings.
