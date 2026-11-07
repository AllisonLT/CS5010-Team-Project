# Architecture and repository layout

The project follows the local single-user architecture in `CS5010 Project Spec.pdf`
and `PROJECT_TIMELINE.md`. This scaffold addresses the foundation of M2 #17/#18,
not acceptance of the entire milestone.

```text
JavaFX desktop --HTTP/JSON--> Spring Boot --HTTP/JSON--> FastAPI
                                  |
                                  +--> MongoDB (metadata and records)
                                  +--> Local filesystem (uploaded CSV)
```

Only the backend owns application persistence. The desktop must not connect
directly to MongoDB or the ML service. Offline training is separate from online
inference; it must not run as part of a prediction request.

## Layout and ownership

```text
apps/desktop/             Maven JavaFX UI module
  src/main/java/         Screens, UI controllers, and backend HTTP client
services/backend/        Maven Spring Boot module
  src/main/java/         Application entry point and API controllers
  src/main/resources/    Local service configuration
  src/test/java/         JUnit 5 tests
services/ml/             Installable Python FastAPI package
  src/cold_storage_ml/   Inference service
  tests/                 pytest tests
data/raw/                Downloaded source datasets (ignored)
data/processed/          Derived training data (ignored)
data/uploads/            Backend-managed runtime CSV files (ignored)
data/sample/             Small redistributable samples with attribution
models/artifacts/        Offline-trained artifacts (ignored)
docs/                    Setup, dataset provenance, and architecture
.github/workflows/       Java and Python CI
pom.xml                  Java reactor and shared dependency versions
compose.yaml             Optional local MongoDB development instance
```

As features arrive, separate backend `api`, `service`, `domain`, `repository`,
`storage`, and `inference` packages under `edu.northeastern.cs5010.backend`.
Keep MongoDB repositories and CSV storage in the backend, not in UI controllers.
Add desktop `ui` and `client` packages under `edu.northeastern.cs5010.desktop`
when implementing screens and HTTP calls. Separate Python inference, preprocessing,
and offline training inside `cold_storage_ml`; notebooks must not be runtime dependencies.
Do not add empty classes or unused dependencies just to reserve these packages.

## Current starter boundary

The UI opens a development-status window. Both services expose
`GET /api/v1/health`, returning their service identity. These are process-liveness
checks only: they do not certify MongoDB connectivity, loaded models, or prediction
readiness. No dataset/product/prediction/history/audit endpoints exist yet.
`app.ml.base-url` and `app.storage.upload-directory` reserve configuration keys;
no inference client or storage implementation consumes them yet.

## Remaining M2 work

| Issue | Deliverable still requiring design/review |
|---|---|
| #2 | Screen wireframes, navigation, validation states, and limitations text. Proposed in `docs/wireframes.md`. |
| #15 | MongoDB collection schemas, identifiers/references, CSV lifecycle, failed-upload cleanup. Proposed in `docs/data_model.md`. |
| #16 | Versioned Java/Python contracts with timestamps, Celsius units, supported horizons, thresholds, success/error examples, and model limitations. Proposed in `docs/api_contracts.md`. |
| #17 | Each contributor must independently run the starters; this scaffold does not establish teammate acceptance. |
| #18 | Push the branch and confirm actual GitHub Actions checks, including failure propagation. |
| #19 | AU-2/AU-3/AU-9/AU-12 events, automatic generation, required fields, separate audit storage, and normal-application modification restrictions. Proposed in `docs/audit_controls.md`. |
| #20 | Dataset sizes, horizons, product inputs, machine configuration, and end-to-end timing for the five-second prediction requirement, excluding offline training. Proposed in `docs/latency_workload.md`. |

M1 feasibility and supported product/model decisions remain prerequisites for
final prediction contracts. Do not invent supported shelf-life outputs or accuracy
guarantees while those decisions are unresolved. The MongoDB development container
is not an implementation of the audit permission design.
