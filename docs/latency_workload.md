# Prediction Latency Workload and Measurement Method (Issue #20)

## Issue and milestone context

- **Issue:** #20 — Define the prediction latency measurement workload
- **Milestone:** M2: Architecture, contracts, and development setup
- **Depends on:** #13 (`docs/shelf_life_feasibility.md`), #16 (`docs/api_contracts.md`)
- **Used by:** #43 (measure and meet end-to-end prediction latency), #9 (fresh-install validation)

The project PDF requires: "Generate predictions within 5 seconds under normal operating conditions." This document defines what is timed, on what inputs, on which machine, and what counts as a pass.

## What is timed

**Measured interval:** from the moment the backend receives `POST /api/v1/predictions/temperature` or `POST /api/v1/predictions/shelf-life` to the moment it finishes sending the response.

This includes:

- loading the dataset and product metadata from MongoDB
- reading the needed window from `minute.csv`
- the HTTP call to the ML service and the model computation
- saving the prediction and writing its audit records
- building the JSON response

It excludes:

- offline model training, which never runs during a request (`docs/architecture.md`)
- dataset upload and the creation of `minute.csv`, which happen once, at upload time
- starting the backend, the ML service, or MongoDB, and loading model artifacts at ML startup
- JavaFX rendering after the response arrives

The backend records this interval in `predictions.durationMs` (#15). The measurement script below also times each request from the client side, which adds the local network round trip. **The pass/fail check uses the client-side time**, because it is closer to what the user waits for.

## Workloads

Both workloads use the #11 dataset file `SENSOR02_preprocessed.csv` (156,835 rows, 4,774,493 bytes, 13,070 one-minute bins from 2024-09-02 09:36 to 2024-09-11 11:25). It is one of the three training files, has the fewest missing minutes (one), and its sensor is supported by the forecasting model.

| ID | Request | Why it is representative |
|---|---|---|
| **W1** | Temperature forecast: SENSOR02, default origin (latest valid minute), horizons `[15, 30, 60]`, threshold 10.0 °C | All three horizons, the most work a forecast request can ask for |
| **W2** | Shelf-life estimate: product `FRESH_POULTRY_AEROBIC`, N0 = 2.0, α0 = 1.0, linked to SENSOR02 for the dataset's full range (about 13,070 minutes, 9.1 days), future temperature 4.0 °C | Longest history the dataset allows, close to the API's 14-day limit (20,160 minutes) |
| **W2-max** | Same as W2, but with a synthetic 20,160-minute history (constant 6.0 °C) uploaded as a `CANONICAL` CSV | Checks the worst case the contract allows |

W1 and W2 are the pass/fail workloads. W2-max is reported but is a stress case.

## Reference machine

Each person who runs the measurement records their machine in the report. The first reference machine:

| Item | Value |
|---|---|
| Machine | Apple M4 Pro, 12 CPU cores, 24 GB RAM |
| OS | macOS 15.7.4 |
| Java | JDK 21 (the project's required version; the machine currently has JDK 11 installed and must be upgraded before measuring) |
| Python | 3.11 (project-tested version) |
| MongoDB | 7, local Docker container from `compose.yaml` |

"Normal operating conditions" means: all three services running on the same machine, no other heavy programs running, laptop plugged in, MongoDB holding the demo datasets and no more than 10,000 predictions and 50,000 audit records.

The team should also run the measurement once on a Windows machine, since most setup instructions target Windows.

## Method

1. Start MongoDB, the ML service (one Uvicorn worker), and the backend, following `docs/development.md`.
2. Upload `SENSOR02_preprocessed.csv` and create the W2 product.
3. **Warm-up:** send 3 requests of each workload and discard the results. The first request after startup includes Java class loading and connection setup. Its time is reported separately as "cold first request" and is not part of the pass/fail check.
4. **Measure:** send 30 requests of each workload, one at a time, and record the client-side time of each.
5. Report for each workload: median, 95th percentile, maximum, and the backend's median `durationMs`.
6. Repeat once after restarting all services, to make sure the results do not depend on one lucky run.

The measuring script (`scripts/measure_latency.py`, added in #43) uses only the backend API, so it tests the real request path.

## Pass criteria

| Check | Pass when |
|---|---|
| W1 | All 30 measured requests finish in ≤ 5.0 s |
| W2 | All 30 measured requests finish in ≤ 5.0 s |
| Error rate | 0 failed requests in W1 and W2 |

We use the maximum rather than an average, because the PDF says "within 5 seconds," not "within 5 seconds on average." The 4-second ML response timeout in #16 keeps a slow ML call from pushing a request far past the limit.

## Report template

```text
Machine: <CPU, RAM, OS, Java, Python, MongoDB>
Commit:  <git hash>
Date:    <YYYY-MM-DD>

Workload   n    median   p95     max     backend median durationMs   cold first request
W1         30   ___ s    ___ s   ___ s   ___ ms                      ___ s
W2         30   ___ s    ___ s   ___ s   ___ ms                      ___ s
W2-max     30   ___ s    ___ s   ___ s   ___ ms                      ___ s

Result: PASS / FAIL
```

## Acceptance criteria (Issue #20)

- [x] Record representative supported dataset sizes, horizons, product inputs, and machine configuration
- [x] Define end-to-end timing boundaries excluding offline training
- [x] Document a reproducible method for checking the PDF prediction-within-5-seconds requirement
