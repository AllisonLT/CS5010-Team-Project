# Data Model: MongoDB Collections and Local CSV Storage (Issue #15)

## Issue and milestone context

- **Issue:** #15 — Design MongoDB collections and local CSV storage
- **Milestone:** M2: Architecture, contracts, and development setup
- **Depends on:** #11, #12, #13
- **Used by:** #16 (API contracts), #19 (audit controls), #21–#24 (M3 implementation)

Only the Spring Boot backend reads or writes MongoDB and the upload directory (`docs/architecture.md`). The desktop app and the ML service never touch them directly.

## Conventions

| Topic | Rule |
|---|---|
| Database | `cold_storage` (from `spring.data.mongodb.uri`) |
| Identifiers | Backend-generated UUID v4 strings in `_id`. They never change, so a dataset or product selected before a restart is the same one after it. |
| System timestamps | `createdAt`, `updatedAt`, audit `timestamp`: stored as BSON Date (UTC), shown in the UI in the machine's local time zone |
| Reading timestamps | Kept as **naive local date-times** (`"2024-09-08T18:05:00"`), because the source CSV has no time zone. Never converted to UTC. |
| Temperatures | Always degrees Celsius, field names end in `C` (`temperatureC`) |
| Schema version | Every document has `schemaVersion: 1`. Code that changes a document's shape bumps the number and handles older versions. |
| Deletion | Datasets and products are soft-deleted (`status: "DELETED"`) so past predictions and audit records still resolve. Audit records are never deleted (see #19). |

## Collections

### `datasets`

One document per accepted upload. Rejected uploads create **no** dataset document; they only create an audit record.

The example below is the real metadata for `SENSOR02_preprocessed.csv` from the #11 dataset.

```json
{
  "_id": "6f0c1d1e-8a54-4f3e-9b35-0e2a4f6f1c11",
  "schemaVersion": 1,
  "displayName": "Cold room sensor 02",
  "originalFilename": "SENSOR02_preprocessed.csv",
  "status": "READY",
  "csvFormat": "SENSOR_SEMICOLON",
  "storage": {
    "sourcePath": "datasets/6f0c1d1e-8a54-4f3e-9b35-0e2a4f6f1c11/source.csv",
    "processedPath": "datasets/6f0c1d1e-8a54-4f3e-9b35-0e2a4f6f1c11/minute.csv",
    "sha256": "eb597c438c042289fbf3456f718c7c4e4827366e252e64629111e5d726c72bec",
    "sizeBytes": 4774493
  },
  "sensorIds": ["SENSOR02"],
  "columns": ["Date", "Time", "Temperature", "Humidity"],
  "recordCount": 156835,
  "acceptedRecordCount": 156835,
  "timeRange": { "start": "2024-09-02T09:35:25", "end": "2024-09-11T11:24:55" },
  "minuteSummary": {
    "minuteCount": 13070,
    "missingMinuteCount": 1,
    "longestGapMinutes": 1,
    "temperatureMinC": 5.8,
    "temperatureMaxC": 38.67
  },
  "warnings": [],
  "createdAt": { "$date": "2026-11-07T19:40:12Z" },
  "updatedAt": { "$date": "2026-11-07T19:40:12Z" }
}
```

| Field | Notes |
|---|---|
| `status` | `READY` (selectable), `MISSING_FILE` (file disappeared; not selectable), `DELETED` |
| `csvFormat` | `SENSOR_SEMICOLON` (Zenodo layout: `Date;Time;Temperature;Humidity`, dates as `dd.mm.yyyy`, one sensor per file) or `CANONICAL` (`timestamp,sensor_id,temperature_c,humidity`). Parsing rules belong to #21. |
| `sensorIds` | For `SENSOR_SEMICOLON`, taken from a `SENSORnn` pattern in the filename or entered by the user at upload |
| `recordCount` / `acceptedRecordCount` | Data rows read / rows that passed validation |
| `minuteSummary` | Computed from the 1-minute file (see storage below) |
| `warnings` | Non-fatal problems, e.g. `{ "code": "ROWS_SKIPPED", "message": "...", "rowNumbers": [812, 813] }`. At most 20 row numbers per warning, to keep the document small. |

**Indexes:** `{ status: 1, createdAt: -1 }` for the dataset list; `{ "storage.sha256": 1 }` (not unique) to warn about uploading the same file twice.

### `products`

A food product and the temperature history it is linked to.

```json
{
  "_id": "0b9a7c2e-3e1f-4c55-9d8e-2a7d5b8f9e40",
  "schemaVersion": 1,
  "name": "Chicken breast batch 14",
  "category": "FRESH_POULTRY_AEROBIC",
  "initialLogCount": 2.0,
  "physiologicalStateAlpha0": 1.0,
  "temperatureSource": {
    "datasetId": "6f0c1d1e-8a54-4f3e-9b35-0e2a4f6f1c11",
    "sensorId": "SENSOR02",
    "start": "2024-09-08T18:05:00",
    "end": "2024-09-10T02:45:00"
  },
  "notes": "Received 2024-09-08",
  "status": "ACTIVE",
  "version": 3,
  "createdAt": { "$date": "2026-11-07T19:45:00Z" },
  "updatedAt": { "$date": "2026-11-07T19:52:31Z" }
}
```

| Field | Notes |
|---|---|
| `category` | Only `FRESH_POULTRY_AEROBIC` is supported (#13). The field exists so new categories can be added later without a schema change. |
| `initialLogCount` | log CFU/cm², 1.0–4.0, default 2.0 (#13) |
| `physiologicalStateAlpha0` | 0.1–1.0, default 1.0 (#13) |
| `temperatureSource` | Optional until the user links a history. Must reference a `READY` dataset and one of its `sensorIds`; `start < end`, both inside the dataset's `timeRange`. |
| `status` | `ACTIVE` or `DELETED` |
| `version` | Optimistic locking (Spring Data `@Version`), so two edits cannot silently overwrite each other |

**Indexes:** `{ status: 1, name: 1 }`; `{ "temperatureSource.datasetId": 1 }` to find products affected when a dataset is deleted.

### `predictions`

One document per **successful** prediction. Failed predictions are never saved here; they produce an audit record with outcome `FAILURE` instead. This keeps a failed request from appearing as a valid result in the history table.

```json
{
  "_id": "c3a2e9f1-1d7b-4a0e-8f4c-6b2d9e0a7f55",
  "schemaVersion": 1,
  "type": "TEMPERATURE_FORECAST",
  "datasetId": "6f0c1d1e-8a54-4f3e-9b35-0e2a4f6f1c11",
  "sensorId": "SENSOR02",
  "productId": null,
  "request": {
    "originTimestamp": "2024-09-09T14:00:00",
    "horizonsMinutes": [15, 30, 60],
    "thresholdC": 10.0
  },
  "result": { "...": "response body from the ML service, as defined in #16" },
  "summary": {
    "warning": true,
    "headline": "Forecast exceeds 10.0 °C within 30 minutes"
  },
  "model": { "name": "damped_trend+peak_quantile", "version": "forecasting-2026-11-07", "mlServiceVersion": "0.1.0" },
  "durationMs": 412,
  "createdAt": { "$date": "2026-11-07T20:01:09Z" }
}
```

| Field | Notes |
|---|---|
| `type` | `TEMPERATURE_FORECAST` or `SHELF_LIFE` |
| `productId` | Set for `SHELF_LIFE`, `null` otherwise |
| `request` | The user's choices, not the full reading history. The history can be rebuilt from the dataset file and the time window. |
| `result` | The ML response stored as-is, so the history page shows exactly what the user saw |
| `summary` | Short values for the history table, so it does not have to parse `result` |
| `durationMs` | End-to-end backend time, used by #20 |

Prediction documents are written once and not edited.

**Indexes:** `{ createdAt: -1 }`; `{ type: 1, createdAt: -1 }`; `{ datasetId: 1, createdAt: -1 }`; `{ productId: 1, createdAt: -1 }`.

### `audit_logs`

Kept in its own collection so it can have separate database permissions. The document shape, event list, and permissions are defined in #19 (`docs/audit_controls.md`). In outline:

```json
{
  "_id": "9e1f4b7a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
  "schemaVersion": 1,
  "timestamp": { "$date": "2026-11-07T20:01:09.412Z" },
  "eventType": "PREDICTION_CREATED",
  "source": "backend.PredictionService",
  "actor": "local-user",
  "outcome": "SUCCESS",
  "refs": { "datasetId": "6f0c...", "productId": null, "predictionId": "c3a2..." },
  "details": { "type": "TEMPERATURE_FORECAST", "durationMs": 412 },
  "error": null
}
```

**Indexes:** `{ timestamp: -1 }`; `{ eventType: 1, timestamp: -1 }`.

### Relationships

```text
datasets 1 ──< products.temperatureSource.datasetId
datasets 1 ──< predictions.datasetId
products 1 ──< predictions.productId
audit_logs.refs ──> datasets / products / predictions (by id, no cascade)
```

MongoDB does not enforce these references. The backend checks them on write: a product can only link to a `READY` dataset, and a prediction can only be created for a `READY` dataset and an `ACTIVE` product.

## Local CSV storage

### Layout

```text
${app.storage.upload-directory}/            default ./data/uploads (git-ignored)
  tmp/
    <uuid>.part                             upload in progress
  datasets/
    <datasetId>/
      source.csv                            exact bytes the user uploaded
      minute.csv                            1-minute means used for predictions
```

`minute.csv` has the columns `timestamp,sensor_id,temperature_c,humidity,reading_count`. It follows the same rule as the ML preprocessing (`preprocessing.py`): bins are right-closed and right-labeled, so `09:36` covers `(09:35, 09:36]`. A bin is kept only if it has at least 75% of the expected readings. Missing bins are written as empty values, never filled. A shared fixture (one small input file and its expected `minute.csv`) is checked by both JUnit and pytest so the Java and Python versions cannot drift apart.

Paths in MongoDB are stored relative to the upload directory, so the project folder can be moved.

### Lifecycle

| Step | What happens | If it fails |
|---|---|---|
| 1. Receive | Stream the upload to `tmp/<uuid>.part`. Reject files over 50 MB or without a `.csv` name. | Delete the part file; audit `DATASET_UPLOAD_REJECTED` |
| 2. Validate | Parse and validate (#21). Compute SHA-256 while reading. | Delete the part file; audit `DATASET_UPLOAD_REJECTED` with the reason and row numbers |
| 3. Store | Create `datasets/<id>/`, move the part file to `source.csv` (atomic move on the same disk), write `minute.csv` | Delete `datasets/<id>/`; audit `DATASET_UPLOAD_FAILED` |
| 4. Register | Insert the `datasets` document with `status: READY` | Delete `datasets/<id>/`; audit `DATASET_UPLOAD_FAILED` |
| 5. Confirm | Audit `DATASET_UPLOADED` | If the audit write fails, the upload is reported as failed and steps 3–4 are undone (see #19) |

The metadata document is inserted **last**, so a failure at any earlier step cannot leave a dataset that looks valid.

### Startup checks

When the backend starts:

1. Delete `tmp/*.part` files older than one hour (left by a crash during upload).
2. For each `READY` dataset whose `source.csv` or `minute.csv` is missing, set `status: MISSING_FILE` and write an audit record.
3. Leave `datasets/<id>/` folders that have no `datasets` document alone, and log a warning. They are not deleted automatically, in case MongoDB was simply not reachable.

### Deleting a dataset

1. Set `status: DELETED` and `updatedAt`.
2. Delete `datasets/<id>/` from disk.
3. Products linked to it keep the link but show "temperature history no longer available" until the user links another dataset.
4. Past predictions stay readable, because their results are stored in full.
5. Audit `DATASET_DELETED`.

## Acceptance criteria (Issue #15)

- [x] Document dataset metadata, product profiles, prediction history, and separate audit record schemas
- [x] Define identifiers and references between products, datasets, and predictions
- [x] Specify local CSV paths, storage lifecycle, and failed-upload cleanup
