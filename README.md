# Smart Cold Storage Monitor

Smart Cold Storage Monitor is a local desktop application for cold-storage managers who need to monitor historical temperature conditions and identify possible temperature excursions before they occur. Users can upload sensor CSV datasets, inspect processed measurements, and request short-term temperature forecasts and predictive threshold warnings. The system uses a **JavaFX** desktop UI, a **Spring Boot** REST backend, a **Python FastAPI** forecasting service, and **MongoDB** for application metadata, prediction records, and audit logs. Food spoilage-risk and remaining shelf-life estimation are part of the planned second module and are not yet implemented.

## Team

| Member | GitHub Username | Role |
|---|---|---|
| Zi Ye | [@AllisonLT](https://github.com/AllisonLT) | Fullstack Developer |
| Leyang Pan | [@YuanSu526](https://github.com/YuanSu526) | ML Developer |
| Bo Sun | [@qianxi66](https://github.com/qianxi66) | Fullstack Developer |

## Repository Structure

The repository separates the JavaFX UI, Java backend, and Python ML service, consistent with the Phase 1 system architecture.

```text
CS5010-Team-Project/
├── apps/
│   └── desktop/              JavaFX desktop application and UI tests
├── services/
│   ├── backend/              Spring Boot REST API, CSV handling, MongoDB integration, JUnit tests
│   └── ml/                   FastAPI inference, ML preprocessing/training scripts, pytest tests
├── data/
│   ├── raw/                  Downloaded source CSV datasets (not committed)
│   ├── processed/            Generated ML training/validation/test data (not committed)
│   ├── uploads/              CSV files managed by the backend (not committed)
│   └── sample/               Sample-data directory
├── models/
│   └── artifacts/            Saved ML artifacts (not committed)
├── docs/                     Architecture, API contracts, model reports, and development guides
├── .github/workflows/        GitHub Actions build/test pipeline
├── compose.yaml              Local MongoDB service configuration
├── pom.xml                   Parent Maven project
├── PROJECT_TIMELINE.md       Milestone plan and acceptance criteria
└── README.md                 Project overview and local setup instructions
```

## Setup and Run

This section describes how to build, run, and test the Smart Cold Storage Monitor locally on **Windows (PowerShell)** and **macOS (Terminal)**. The application consists of a JavaFX desktop UI, a Spring Boot backend, a Python FastAPI forecasting service, and MongoDB. Run all commands from the **repository root** unless otherwise noted.

### 1. Prerequisites

Install the following before proceeding:

| Requirement | Version / Notes |
| --- | --- |
| Git | Any recent version |
| JDK | **Java 21** (set `JAVA_HOME` to the JDK) |
| Python | **Python 3.11** recommended (the project's tested baseline) |
| Maven | Use the included Maven Wrapper (`mvnw.cmd` on Windows, `bash ./mvnw` on macOS) |
| Docker Desktop | Docker Engine and Compose v2, for local **MongoDB 7** (or install MongoDB 7 locally instead) |
| Desktop | A graphical desktop environment is required for JavaFX |

Verify the required tools:

**Windows — PowerShell**

```powershell
git --version
java -version
py -3.11 --version
docker compose version
```

**macOS — Terminal**

```bash
git --version
java -version
python3.11 --version
docker compose version
```

Ensure Docker Desktop is running before starting MongoDB. On macOS, if `python3.11` is unavailable, install Python 3.11 and ensure its executable is on `PATH` (e.g., `brew install python@3.11`).

### 2. Clone the Repository

**Windows / macOS**

```bash
git clone https://github.com/AllisonLT/CS5010-Team-Project.git
cd CS5010-Team-Project
git switch main
git pull origin main
```

If the repository is already cloned, open it and run `git switch main` followed by `git pull origin main` instead.

### 3. Set Up the Python ML Environment

Create a Python 3.11 virtual environment and install the ML package and its test dependencies.

**Windows — PowerShell**

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -e ".\services\ml[dev]"
```

**macOS — Terminal**

```bash
python3.11 -m venv .venv
./.venv/bin/python -m pip install --upgrade pip
./.venv/bin/python -m pip install -e './services/ml[dev]'
```

The install includes FastAPI, Uvicorn, NumPy, pandas, scikit-learn, joblib, PyMongo, and testing dependencies as declared in `services/ml/pyproject.toml`.

If you already have a virtual environment, rerun the `pip install -e` command above after pulling the audit changes to install PyMongo in that environment.

### 4. Download and Prepare the Sensor Data (First-Time Setup)

Download the project sensor dataset (Preprocessed.zip) from [Zenodo record 15130001](https://zenodo.org/records/15130001). Place these three source CSV files in `data/raw/`:

```text
data/raw/
├── SENSOR01_preprocessed.csv
├── SENSOR02_preprocessed.csv
└── SENSOR04_preprocessed.csv
```

The source CSVs are not committed to Git. Their expected filenames and formats correspond to the current preprocessing scripts. If the downloaded files have different filenames, adjust the `--inputs` arguments accordingly, preserving the correct sensor IDs.

Run preprocessing to produce one-minute measurements and chronological train/validation/test partitions.

**Windows — PowerShell**

```powershell
.\.venv\Scripts\python.exe services\ml\scripts\preprocess_dataset.py `
  --inputs `
  data\raw\SENSOR01_preprocessed.csv `
  data\raw\SENSOR02_preprocessed.csv `
  data\raw\SENSOR04_preprocessed.csv `
  --output-dir data\processed\forecasting
```

**macOS — Terminal**

```bash
./.venv/bin/python services/ml/scripts/preprocess_dataset.py \
  --inputs \
  data/raw/SENSOR01_preprocessed.csv \
  data/raw/SENSOR02_preprocessed.csv \
  data/raw/SENSOR04_preprocessed.csv \
  --output-dir data/processed/forecasting
```

Expected generated files:

```text
data/processed/forecasting/
├── all_sensors.csv
├── train.csv
├── validation.csv
├── test.csv
└── preprocessing_report.json
```

### 5. Prepare Trained Forecasting Models (First-Time Setup)

The ML inference service **requires saved model artifacts**. These artifacts are excluded from Git, so a fresh clone does not contain them. You must either generate them locally using the commands below **or obtain the same trusted artifact files from a teammate**. This is a one-time setup step, not part of each application startup.

The production inference path currently uses **damped-trend point forecasts** and **future-peak quantile models** for 15-, 30-, and 60-minute horizons. The alternative Ridge, Random Forest, and HistGradientBoosting experiment scripts are **not required** for starting the application.

**Windows — PowerShell**

```powershell
# Generate damped-trend configurations.
.\.venv\Scripts\python.exe services\ml\scripts\evaluate_trend.py

# Train the future-peak quantile models.
.\.venv\Scripts\python.exe services\ml\scripts\evaluate_threshold_warnings.py --threshold 10
```

**macOS — Terminal**

```bash
# Generate damped-trend configurations.
./.venv/bin/python services/ml/scripts/evaluate_trend.py

# Train the future-peak quantile models.
./.venv/bin/python services/ml/scripts/evaluate_threshold_warnings.py --threshold 10
```

The `--threshold 10` argument is used for the offline warning evaluation report. It does **not** fix the threshold for future API predictions; the application supplies the threshold per prediction request.

Verify that all six inference artifacts exist:

```text
models/artifacts/forecasting/
├── trend/
│   ├── damped_trend_15min.json
│   ├── damped_trend_30min.json
│   └── damped_trend_60min.json
└── warnings/
    ├── peak_quantile_15min.joblib
    ├── peak_quantile_30min.joblib
    └── peak_quantile_60min.joblib
```

Do not commit large/raw sensor files, generated data, virtual environments, or trained artifacts to Git. Only load `.joblib` artifacts generated by this project or received from a trusted teammate.

### 6. Start MongoDB

With Docker Desktop running, execute the following from the repository root.

**Windows / macOS**

```bash
docker compose up -d --wait mongodb
docker compose exec mongodb mongosh --quiet --eval "db.adminCommand({ping: 1})"
```

The local MongoDB instance listens on `127.0.0.1:27017` and uses a persistent Docker volume. It is intended for **local development only**.

To enforce audit collection permissions in MongoDB, follow the [authenticated setup](docs/development.md#enable-mongodb-audit-permissions). It uses `compose.audit.yaml` and separate accounts for Spring Boot and FastAPI. Keep the override in subsequent Compose startup commands for that instance.

### 7. Run All Application Components

Use **three separate terminals**, all opened at the repository root, **after** MongoDB has started. Start the services in the order below.

#### Windows — PowerShell

**Terminal 1 — Python FastAPI (port 8000)**

```powershell
.\.venv\Scripts\python.exe -m uvicorn cold_storage_ml.app:app --host 127.0.0.1 --port 8000
```

**Terminal 2 — Spring Boot backend (port 8080)**

```powershell
$env:UPLOAD_DIRECTORY = Join-Path (Get-Location) "data\uploads"
.\mvnw.cmd -pl services/backend spring-boot:run
```

**Terminal 3 — JavaFX desktop UI**

```powershell
.\mvnw.cmd -pl apps/desktop javafx:run
```

#### macOS — Terminal

**Terminal 1 — Python FastAPI (port 8000)**

```bash
./.venv/bin/python -m uvicorn cold_storage_ml.app:app --host 127.0.0.1 --port 8000
```

**Terminal 2 — Spring Boot backend (port 8080)**

```bash
export UPLOAD_DIRECTORY="$(pwd)/data/uploads"
bash ./mvnw -pl services/backend spring-boot:run
```

**Terminal 3 — JavaFX desktop UI**

```bash
bash ./mvnw -pl apps/desktop javafx:run
```

Keep these three terminals open while using the application. The desktop communicates with Spring Boot. Spring Boot calls FastAPI and handles prediction persistence. Both services write audit events to MongoDB.

**Configuration (optional overrides):** The project uses local defaults, so no credentials or API keys are needed for the standard setup. To override defaults, set real environment variables in the terminal that starts the corresponding service; the application does not automatically load a `.env` file.

| Variable | Default | Used by |
|---|---|---|
| `ML_BASE_URL` | `http://127.0.0.1:8000` | Spring Boot → FastAPI |
| `MONGODB_URI` | `mongodb://127.0.0.1:27017/cold_storage` | Spring Boot → MongoDB |
| `ML_AUDIT_MONGODB_URI` | `MONGODB_URI` from the FastAPI terminal, or `mongodb://127.0.0.1:27017/cold_storage` | FastAPI → MongoDB audit inserts |
| `BACKEND_PORT` | `8080` | Spring Boot |
| `UPLOAD_DIRECTORY` | `./data/uploads` (relative to backend working directory) | Spring Boot file storage |
| `BACKEND_URL` | `http://127.0.0.1:8080` | JavaFX → Spring Boot |
| `FORECAST_ARTIFACT_DIR` | `models/artifacts/forecasting` (relative to the ML process working directory) | FastAPI model loading |

Point both services at the same database so the desktop can show audit events from both. In authenticated mode, set `MONGODB_URI` in the backend terminal and `ML_AUDIT_MONGODB_URI` in the FastAPI terminal using their respective accounts. Keep credentials and machine-specific `.env` files outside version control.

### 8. Verify the Services and Run a Forecast

Open another terminal and check the following endpoints after starting the services.

**Windows — PowerShell**

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
Invoke-RestMethod http://127.0.0.1:8000/api/v1/ready
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
Invoke-RestMethod http://127.0.0.1:8080/api/v1/status
```

**macOS — Terminal**

```bash
curl -f http://127.0.0.1:8000/api/v1/health
curl -f http://127.0.0.1:8000/api/v1/ready
curl -f http://127.0.0.1:8080/api/v1/health
curl -f http://127.0.0.1:8080/api/v1/status
```

The ML readiness endpoint should return:

```json
{"status":"ready","horizons_minutes":[15,30,60]}
```

A healthy `/api/v1/health` response alone does **not** mean that the saved ML models are available. If `/api/v1/ready` returns HTTP 503, verify the six files from Step 5 and that FastAPI was started from the repository root.

In the JavaFX application:

1. Upload a supported sensor CSV through the **Datasets** screen and allow processing to complete.
2. Open **Forecast** and choose the uploaded **READY** dataset and its sensor (`SENSOR01`, `SENSOR02`, or `SENSOR04`).
3. Select the forecast origin (latest valid window or a specific timestamp), horizons (15/30/60 minutes), and temperature threshold.
4. Click **Run forecast** to request a real prediction via JavaFX → Spring Boot → FastAPI.
5. Check the displayed point forecasts, future-peak estimates, threshold warnings, and (when available) observed temperatures.
6. Open **Prediction History** and double-click the new row to reopen the stored forecast.
7. Open **Audit Log** and check for a `PREDICTION_CREATED` event from Spring Boot and an `ML_INFERENCE_SUCCEEDED` event from FastAPI.

Each prediction requires **61 consecutive, valid one-minute readings** ending at the selected origin. Other sensor IDs are not supported by the currently trained models.

### 9. Build and Run Automated Tests

Run these commands after installing the dependencies in Step 3. Start Docker Desktop before running Maven if you want the MongoDB integration tests included. Testcontainers starts its own MongoDB instance. Python tests use temporary model fixtures and a mocked audit collection, so you can run them with the application stopped and before downloading the datasets or training the models.

**Windows — PowerShell**

```powershell
# Build and test the Java modules (Spring Boot + JavaFX).
.\mvnw.cmd --batch-mode verify

# Run Python ML tests.
.\.venv\Scripts\python.exe -m pytest services\ml\tests -v
```

**macOS — Terminal**

```bash
# Build and test the Java modules (Spring Boot + JavaFX).
bash ./mvnw --batch-mode verify

# Run Python ML tests.
./.venv/bin/python -m pytest services/ml/tests -v
```

`DatasetWorkflowIntegrationTest` covers the dataset workflow, audit API, and prediction history against MongoDB. Maven skips this class when Testcontainers cannot reach Docker. Use Step 8 to verify the full workflow with trained artifacts and the running desktop app.

### 10. Subsequent Runs and Shutdown

After completing Steps 3–5 once, you **do not need to preprocess or retrain on every startup** unless source data, training settings, or model files change. For normal development, start MongoDB and run the three components in Step 7.

To stop the application, use **Ctrl+C** in each service terminal and close the JavaFX window. To stop MongoDB without deleting its saved data:

```bash
docker compose stop mongodb
```

For further configuration and troubleshooting, see [`docs/development.md`](docs/development.md) and [`docs/_ML_Forecasting_Handoff.md`](docs/_ML_Forecasting_Handoff.md).

## Progress So Far

The table summarizes implementation against the **Phase 1 SRS functional requirements**. For this progress snapshot, the spoilage and shelf-life workflow, including product profiles, saved results, and spoilage audit events, is complete. Those additions still need verification after integration. The temperature forecasting, prediction history, and audit workflows have been exercised locally.

| Functional Requirement (from Phase 1) | Status | Notes |
|---|---|---|
| Upload and validate historical temperature CSV datasets | Complete | JavaFX upload workflow and backend CSV validation/resampling are implemented. |
| Store uploaded datasets and allow dataset selection | Complete | Local CSV storage with MongoDB metadata; datasets can be selected in the desktop UI. |
| Generate configurable short-term temperature forecasts | Complete | Python model-backed forecasts for 15-, 30-, and 60-minute horizons; JavaFX/Spring Boot/FastAPI call path is implemented. |
| Show temperature threshold predictions and warnings | Complete | Future-peak estimates and threshold warnings are returned and displayed by the forecast UI. |
| Compare forecasting model performance with baselines | Partial | Baseline and validation results are documented; the final held-out evaluation remains pending. |
| Manage food products and associate them with temperature histories | Complete | Users can select a supported product profile and link it to a temperature history. |
| Estimate food spoilage risk and remaining shelf life | Complete | The workflow estimates spoilage risk and remaining shelf life for supported products and displays the applicable model limitations. |
| Display previously saved predictions | Complete | Prediction History reopens successful results saved in MongoDB. Forecast history was verified locally. Shelf-life history is included in the implementation assumption. |
| Generate and review audit logs | Complete | The desktop Audit Log displays backend and FastAPI events. Product and spoilage audit events are included in the implementation assumption. See [audit setup](docs/development.md#enable-mongodb-audit-permissions). |
| Handle invalid data and prediction-service failures | Partial | Focused parser, API, and error-handling tests exist; full multi-service failure scenarios still need verification. |

### Milestone Tracker

The project is tracked across ten milestones. See [PROJECT_TIMELINE.md](PROJECT_TIMELINE.md) for deliverables and acceptance criteria.

**Last updated** November 20, 2026. M1 and M2 retain the team's completion decisions. M4, M6, and M7 reflect the  spoilage integration. Formal acceptance still depends on the tests and demonstrations in the project timeline.

| Milestone | Description | Due Date | Status |
|---|---|---|---|
| M1 | Requirements and Dataset Feasibility | Oct 25, 2026 | ✅ Completed |
| M2 | Architecture, Contracts, and Development Setup | Nov 1, 2026 | ✅ Completed |
| M3 | Local Application and Dataset Workflow | Nov 8, 2026 | ✅ Implemented |
| M4 | Java-to-Python Inference Integration | Nov 12, 2026 | ✅ Implemented |
| M5 | Temperature Forecasting Model | Nov 19, 2026 | ✅ Implemented |
| M6 | Spoilage and Shelf-Life Model | Nov 22, 2026 | ✅ Implemented |
| M7 | Complete Two-Module Application | Nov 29, 2026 | ✅ Implemented |
| M8 | Automated QA and Held-Out Evaluation | Dec 6, 2026 | 🟡 In progress. Held-out evaluation and latency measurements pending. |
| M9 | End-to-End Validation and Release Candidate | Dec 13, 2026 | ⚪ Not Started |
| M10 | Local Release, Documentation, and Submission | Dec 21, 2026 | 🟡 Documentation Started |

### Working End to End

The **temperature forecasting code path is connected** across the application:

1. In **Datasets**, the user uploads a supported sensor CSV, which Spring Boot validates and stores alongside MongoDB dataset metadata.
2. In **Forecast**, the user selects a ready dataset, sensor, prediction horizon(s), and a temperature threshold.
3. Spring Boot selects 61 consecutive one-minute readings and requests a model-backed forecast from Python FastAPI.
4. The backend saves the prediction and related audit events in MongoDB and returns forecasts and warnings to JavaFX for display.

## Testing So Far

The repository uses **JUnit 5** for the Java backend and desktop client and **pytest** for the Python ML service. Tests run automatically through [GitHub Actions](.github/workflows/ci.yml) on pushes and pull requests.

| Test suite | Coverage | Verified locally on Windows |
|---|---|---|
| Java — JUnit 5 | JavaFX client and form rules, CSV parsing, dataset workflow, MongoDB integration, ML contracts, audit API, and prediction history | **73 passed**, 0 failed, 0 skipped (11 desktop + 62 backend) |
| Python — pytest | ML service health, preprocessing, baselines, forecasting, inference, warnings, and audit events | **75 passed**, 0 failed (1 warning) |

**Testing still pending**

- **Spoilage and shelf-life model** — Validate estimates for supported products, test invalid or incomplete temperature histories, and verify that results can be saved and reopened in the desktop app.
- **Audit logs** — Focused tests already cover the audit API and FastAPI event generation. Full verification is pending for product and spoilage events, required record fields, and authenticated MongoDB permissions across both prediction workflows.

Run the same suites using [Build and Run Automated Tests](#9-build-and-run-automated-tests).

## Next Steps

- Integrate model training into the dataset upload workflow so validated uploads can trigger training.
- Add unit and integration tests for spoilage inference, saved shelf-life results, and the associated audit events. Verify audit permissions and service failure handling in end-to-end tests.
- Improve temperature forecast and spoilage model accuracy using training and validation data. Compare changes against baselines using forecast errors by horizon, spoilage-risk classification metrics, and shelf-life error where labels support it.
- Freeze the models before running the final held-out evaluation. Record performance against the baselines and measure end-to-end prediction latency for both workflows.
- Prepare repeatable demos and final release documentation.

## Design Patterns and Principles

This analysis uses the course handout *Creational Design Patterns* and examples from the current Smart Cold Storage Monitor code. It covers the JavaFX desktop, Spring Boot backend, Python ML service, and MongoDB persistence. Examples come from the implemented forecasting, dataset, prediction-history, and audit workflows.

### Patterns from the course

| Pattern | Where and why |
|---|---|
| **Builder** | `BackendClient` builds its JSON mapper, HTTP client, and requests with `builder()` or `newBuilder()`. The builders let the client configure modules, timeouts, headers, and request bodies before constructing each object. [BackendClient.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/client/BackendClient.java) |
| **Simple Factory** | Python's `create_model(model_name)` selects an estimator and returns a scikit-learn pipeline. The training code can work with the same pipeline interface across model choices. JavaFX also keeps a registry of view suppliers in `MainWindow`, so navigation can request a screen by its identifier and construct the view when needed. [forecast_models.py](services/ml/src/cold_storage_ml/forecast_models.py#L92) · [MainWindow.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/ui/MainWindow.java#L45) |
| **Factory Method through a framework hook** | FastAPI's `AuditedRoute` overrides `APIRoute.get_route_handler()` to supply a handler that also records audit events. FastAPI keeps its route-dispatch logic, and the route subclass controls how the handler is constructed. [audit.py](services/ml/src/cold_storage_ml/audit.py#L82) |
| **Singleton-style reuse and lazy initialization** | FastAPI's `get_service()` lazily caches the forecasting service after loading its model artifacts. Reuse is per Python process after initialization. Spring also uses singleton-scoped services by default within its application context. [app.py](services/ml/src/cold_storage_ml/app.py#L29) · [TemperatureForecastService.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/prediction/TemperatureForecastService.java) |
| **Constructor Dependency Injection** | Spring supplies the dataset service, ML client, prediction repository, audit service, and configuration to `TemperatureForecastService`. This lets its tests pass mocked collaborators and a fixed clock. On the desktop, `DesktopApplication` constructs a backend client and passes it into `MainWindow`. [TemperatureForecastService.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/prediction/TemperatureForecastService.java#L59) · [DesktopApplication.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/DesktopApplication.java) |
| **Object pooling through an executor** | The desktop's `Async` helper uses a cached thread pool for backend requests. Running calls off the JavaFX thread keeps the interface responsive, and idle workers can handle later calls. [Async.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/ui/Async.java) |

### Other patterns and design principles

| Pattern or principle | Where and why |
|---|---|
| **Layered architecture and separation of concerns** | JavaFX handles interaction and display, Spring controllers expose the API, Java services coordinate workflows, and FastAPI runs model inference. The upload path also separates CSV parsing, minute resampling, and file storage so each can be tested independently. [architecture.md](docs/architecture.md) · [DatasetService.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/dataset/DatasetService.java) |
| **Repository and interface segregation** | `DatasetRepository` and `PredictionRepository` expose MongoDB persistence through repository interfaces. `AuditRepository` exposes insert and read operations, which keeps audit modification out of the application's repository API. [DatasetRepository.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/dataset/DatasetRepository.java) · [PredictionRepository.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/prediction/PredictionRepository.java) · [AuditRepository.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/audit/AuditRepository.java) |
| **Facade and protocol adaptation** | The desktop's `BackendClient` provides methods such as `forecast()` and `predictionHistory()` and handles HTTP and JSON internally. The backend's `MlClient` maps FastAPI responses and errors into Java types, keeping transport details out of the prediction workflow. [BackendClient.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/client/BackendClient.java) · [MlClient.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/inference/MlClient.java) |
| **Data Transfer Objects and stable contracts** | Java request and response records and Python Pydantic models define the data exchanged across HTTP. The prediction history list carries summaries, and its detail endpoint returns the stored original result. This lets the desktop reopen predictions independently of the model service and source CSV. [MlDtos.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/inference/MlDtos.java) · [inference.py](services/ml/src/cold_storage_ml/inference.py) · [PredictionHistory.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/prediction/PredictionHistory.java) |
| **Observer and event-driven behavior** | JavaFX selection listeners refresh the active screen or show the selected audit event. Spring lifecycle listeners record backend startup and shutdown when the corresponding application events are published. [MainWindow.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/ui/MainWindow.java#L54) · [AuditView.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/ui/AuditView.java#L82) · [ApplicationLifecycleAudit.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/audit/ApplicationLifecycleAudit.java) |
| **Decorator-style request wrapping** | `AuditedRoute` wraps the original FastAPI handler and records success, validation failures, or system errors around the call. This keeps audit persistence out of the model calculation. [audit.py](services/ml/src/cold_storage_ml/audit.py#L82) |
| **Immutable state updates** | `DatasetDocument.withStatus()` creates a new record with the updated status and timestamp while preserving its other fields. Callers can retain the original metadata when a failed audit write requires a rollback. [DatasetDocument.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/dataset/DatasetDocument.java#L52) · [DatasetService.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/dataset/DatasetService.java#L214) |
| **Reuse and consistent presentation** | `ForecastView.resultContent()` renders both a new forecast and one reopened through Prediction History. The same renderer keeps temperatures, warnings, and model/version details consistent in both views. [ForecastView.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/ui/ForecastView.java) · [PredictionHistoryView.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/ui/PredictionHistoryView.java) |

## Team Contributions

### Phase 3

| Member | Contributions |
|---|---|
| **Zi Ye** | Trained the spoilage prediction model and integrated it into the application’s prediction workflow. |
| **Leyang Pan** | Implemented audit logging for temperature forecasts, built the JavaFX Audit Log and Prediction History screens, and connected both screens to the Spring Boot APIs. |
| **Bo Sun** | Implemented audit logging and prediction history for spoilage forecasts, and connected the JavaFX interface to the Spring Boot APIs for retrieving those records. |

### Phase 2

| Member | Contributions |
|---|---|
| **Zi Ye** | Managed GitHub issues, milestone planning, and team progress tracking; established the repository architecture, development environment, and CI workflow; researched and documented dataset selection; contributed to full-stack development and project documentation. |
| **Leyang Pan** | Developed and evaluated the temperature forecasting pipeline, including preprocessing and model training; implemented predictive threshold warnings and FastAPI inference endpoints; wrote Python tests, evaluation reports, and ML/Overall documentation. |
| **Bo Sun** | Developed the JavaFX frontend, Spring Boot backend, and MongoDB persistence workflows; implemented CSV processing, dataset management, ML API integration, and audit logging; wrote Java unit and integration tests and technical design documentation. |

