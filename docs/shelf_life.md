# Poultry shelf-life service

`POST /api/v1/predictions/shelf-life` implements the existing API B contract in
`api_contracts.md`. It uses the published Tsaloumi et al. (2022) parameters, not
a fitted ML model. DOI: https://doi.org/10.1016/j.psj.2022.101985.
This implementation follows the requested poultry-first approach; it does not
establish that a learned-model course requirement or the team approval gate in
issue #13 has been satisfied.

## Supported behavior

- Category: `FRESH_POULTRY_AEROBIC` only.
- Initial count: 1-4 log CFU/cm2. Values below the paper's tested 2-4 range are flagged.
- Physiological state alpha0: 0.1-1, with no-lag behavior at 1.
- History: 60-20,160 consecutive one-minute readings, naive ISO timestamps,
  finite Celsius values or null. No missing timestamps, duplicates, or offsets.
- First/last temperatures must be present. Interior null runs of up to ten
  minutes are linearly interpolated and counted; longer runs name the gap in a 422 error.
- Future temperature: 0-30 C, defaulting to the last reading. If that reading is
  outside the supported future range, supply an explicit valid future temperature.
- Observed temperatures outside 0-30 C are extrapolated and flagged, not silently clipped.
- Each reading represents one minute of exposure, including the final bin.
  Thus 1,440 entries represent 24 hours even though their timestamps span 1,439 minutes.
- Responses include risk, remaining hours, log count, factors, interpolation count,
  limitations, citation, parameter set, and model version `tsaloumi2022-v1`.
- Requests and failures use the existing audited route and `ML_INFERENCE_*` events.
  The backend remains responsible for product references, persisted results, and
  domain-specific high-risk audit events.

The versioned parameter constants live in
`services/ml/src/cold_storage_ml/shelf_life.py`; no model download, training,
or joblib artifact is needed. Forecasting is unchanged: `/api/v1/ready` still
checks forecasting artifacts and may return 503 while shelf-life requests work.
Normal HTTP startup still uses the shared MongoDB audit configuration.

## Calculation

For each minute, accumulate natural-log growth using
`mu(T) = (0.022 * max(0, T + 6.18))^2` per hour.
For accumulated unadjusted growth `G` and `a = alpha0`, the Baranyi adjustment is
`A = log(1 + a * (exp(G) - 1))`. The estimated count is
`initial_log_count + A / log(10)`.

The implementation evaluates this expression without exponentiating large
positive values. Remaining hours solve for the additional growth needed to reach
7 log CFU/cm2 at the future temperature. The existing physiological state carries
forward: lag is not restarted after the supplied history.

Risk is a project-defined fraction of modeled growth used: low below 0.5,
medium from 0.5 to below 0.8, high at 0.8 or above. It is not a calibrated
probability. At the modeled spoilage level, remaining hours are zero.
Factors use the interpolated minute series: time above 4 C, mean/peak temperature,
and fraction of unadjusted modeled growth occurring above 4 C. When total growth
is zero, that fraction is defined as zero.

This is not a food safety determination. It does not apply to beef, other
packaging conditions, pathogens, or histories before the supplied initial count.
It assumes air temperature represents exposure and that future temperature is constant.

## Run and reproduce

Use the Python environment described in `development.md`. From the repository root
in PowerShell (replace the Python path for a standard venv if needed):

```powershell
.\.conda-env\python.exe -m pytest services\ml\tests\test_shelf_life.py -q
.\.conda-env\python.exe services\ml\scripts\evaluate_shelf_life.py
.\.conda-env\python.exe -m uvicorn cold_storage_ml.app:app --host 127.0.0.1 --port 8000
```

In another PowerShell terminal:

```powershell
$start = [datetime]"2026-01-01T00:00:00"
$history = @(0..1439 | ForEach-Object {
    @{ timestamp = $start.AddMinutes($_).ToString("yyyy-MM-ddTHH:mm:ss"); temperature_c = 4.0 }
})
$body = @{
    category = "FRESH_POULTRY_AEROBIC"
    initial_log_count = 2.0
    physiological_state_alpha0 = 1.0
    future_temperature_c = 4.0
    interval_minutes = 1
    history = $history
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8000/api/v1/predictions/shelf-life -ContentType "application/json" -Body $body
```

Expect about 205.5 remaining hours and a used fraction of 0.105.
The evaluation script reproduces five published constant-temperature shelf-life
values within the planned 5% rounding tolerance. It compares 24-hour cold/warm/
dynamic histories against a fixed 4 C use-by baseline, with the same future
temperature of 4 C in every comparison. These differences are not predictive
accuracy metrics: there are no measured outcome labels in these scenarios.
This does not complete held-out evaluation in #42 or a fresh-stack user-journey review.

Recorded reference run:

- N0 = 2: 25.951 days at 0 C, 9.564 days at 4 C, 4.929 days at 8 C.
- N0 = 3 and 4 at 4 C: 7.651 and 5.738 days respectively.
- Errors relative to the five published references: 2.01-2.98%, within the
  planned 5% tolerance for rounded published parameters.
- After 24 hours, with subsequent storage at 4 C: the fixed 4 C baseline leaves
  205.533 hours; an 8 C history leaves 182.967 hours; the 12 h/8 h/4 h dynamic
  profile at 5/10/15 C leaves 177.535 hours.

The separate beef dataset assessment is in `beef_dataset_assessment.md`.
