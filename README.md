# Smart Cold Storage Monitor

Smart Cold Storage Monitor is a local desktop application for cold-storage managers who need to monitor historical temperature conditions and identify possible temperature excursions before they occur. Users can upload sensor CSV datasets, inspect processed measurements, and request short-term temperature forecasts and predictive threshold warnings. The system uses a **JavaFX** desktop UI, a **Spring Boot** REST backend, a **Python FastAPI** forecasting service, and **MongoDB** for application metadata, prediction records, and audit logs. The second module, food spoilage-risk and remaining shelf-life estimation, is partly implemented: the backend product and shelf-life APIs are done, while the ML shelf-life endpoint and its desktop screens are still in progress.

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
├── scripts/                  MongoDB setup for the audit-only database accounts
├── compose.yaml              Local MongoDB service configuration
├── compose.audit.yaml        Optional MongoDB configuration with authentication for audit permissions
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

On macOS, Homebrew's `openjdk@21` is not linked onto `PATH` by default, so `java -version` may report another version or none. Point `JAVA_HOME` at it in each terminal that runs Maven (or add these lines to `~/.zshrc`):

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)"
export PATH="$JAVA_HOME/bin:$PATH"
```

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

1. On the **Datasets** screen, click **Upload CSV…** and choose a source file such as `data/raw/SENSOR02_preprocessed.csv`. The sensor ID is read from the filename, so keep the `SENSORxx` part; if the filename has none, the app asks for one.
2. Open **Forecast** and choose the uploaded **READY** dataset and its sensor (`SENSOR01`, `SENSOR02`, or `SENSOR04`).
3. Under **Forecast from**, choose **Latest available** or **Choose time** (format `yyyy-MM-dd HH:mm`), then select horizons (15/30/60 minutes) and a threshold in °C.
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

## Feature Status

Statuses compare the code on this branch with the **Phase 1 SRS functional requirements** (FR-1 to FR-20). "Complete" means the feature works through the desktop app and has automated tests. "Partial" means part of the path is missing; the note says which part.

| Functional Requirement (from Phase 1) | Status | Where to Find It |
|---|---|---|
| FR-1: Upload a CSV file of temperature records | Complete | `DatasetController`, `DatasetService`, `DatasetStorage` (backend); `DatasetsView` (desktop) |
| FR-2: Reject files without timestamp/temperature columns and report invalid rows with row number and reason | Complete | `csv/CsvParser`, `CsvRejectedException` (backend); error dialog in `DatasetsView` |
| FR-3: Fill short gaps and flag longer gaps | Partial | `csv/MinuteResampler` resamples to one-minute readings and reports the longest gap per dataset; gaps are no longer filled at upload (see Scope Changes) |
| FR-4: List, select, and delete datasets | Complete | `DatasetController`, `DatasetService.delete` (soft delete); `DatasetsView` |
| FR-5: Set forecast starting point, period, and threshold | Complete | `ForecastView`, `TemperatureForecastService.chooseOrigin`; period is 15, 30, and/or 60 minutes (see Scope Changes) |
| FR-6: Forecast temperatures with the trained model | Complete | `services/ml/src/cold_storage_ml/inference.py`, `trend_forecasting.py`; backend `inference/MlClient` |
| FR-7: Show history, forecast, and actual readings on a line chart and in a table | Partial | `ForecastView` shows forecasts, peak estimates, and actual readings in a table; the line chart is not built yet |
| FR-8: Warn for each period that exceeds the threshold, with the expected start time | Complete | `threshold_warning.py` (ML); `TemperatureForecastService.warning`; `ForecastView` |
| FR-9: Evaluate the forecasting model against persistence on held-out data | Partial | Validation results in [`docs/model_baseline.md`](docs/model_baseline.md) and [`docs/model_improvement_experiment.md`](docs/model_improvement_experiment.md); the held-out test run waits until the models are frozen |
| FR-10: Create, edit, and delete product profiles | Partial | Backend API complete: `product/ProductController`, `ProductService` (`/api/v1/products`); desktop product screen not built yet |
| FR-11: Link a product to a dataset and time range | Partial | Backend complete: `ProductService.checkSource` validates the dataset, sensor, and window; desktop screen not built yet |
| FR-12: Compute exposure features (time above 4 °C, peak, cumulative exposure) | Partial | Backend sends the product's one-minute window and stores the returned factors (`prediction/ShelfLifeService`); the ML shelf-life endpoint is not implemented yet |
| FR-13: Return a risk level and remaining shelf life in hours | Partial | `POST /api/v1/predictions/shelf-life` in `PredictionController` and `ShelfLifeService` (saved and audited); depends on the ML shelf-life endpoint and the desktop Shelf Life screen |
| FR-14: List the main factors behind each estimate | Partial | Factors and model limitations are part of the `ShelfLife.Response`; not yet shown in the desktop app |
| FR-15: Evaluate the Module 2 method against reference cases | Not started | Reference case defined in [`docs/api_contracts.md`](docs/api_contracts.md) (section 3, shelf-life example check) |
| FR-16: Save every prediction and show it in the prediction history | Partial | `PredictionHistory`, `PredictionController` save and list both forecast and shelf-life results; `PredictionHistoryView` reopens forecasts only |
| FR-17: Audit uploads, deletions, product changes, predictions, warnings, and errors | Complete | `audit/AuditService`, `AuditEventType`; FastAPI `services/ml/src/cold_storage_ml/audit.py` |
| FR-18: Each audit record has time, event type, source, affected IDs, outcome, and details | Complete | `audit/AuditRecord` |
| FR-19: Audit records can only be inserted and read | Complete | `AuditRepository` (insert/read only), `AuditController` rejects changes with 405; MongoDB accounts in [`scripts/setup-mongo-audit.js`](scripts/setup-mongo-audit.js) |
| FR-20: View audit records in a read-only table filtered by type and date | Complete | `AuditController` (`/api/v1/audit-logs`); `AuditView` filters by category, event, outcome, and date |

## Progress

### Milestone Tracker

The project is tracked across ten milestones. See [PROJECT_TIMELINE.md](PROJECT_TIMELINE.md) for deliverables and acceptance criteria.

**Last updated** November 21, 2026.

| Milestone | Description | Due Date | Status |
|---|---|---|---|
| M1 | Requirements and Dataset Feasibility | Oct 25, 2026 | ✅ Completed |
| M2 | Architecture, Contracts, and Development Setup | Nov 1, 2026 | ✅ Completed |
| M3 | Local Application and Dataset Workflow | Nov 8, 2026 | ✅ Implemented |
| M4 | Java-to-Python Inference Integration | Nov 12, 2026 | ✅ Implemented |
| M5 | Temperature Forecasting Model | Nov 19, 2026 | ✅ Implemented |
| M6 | Spoilage and Shelf-Life Model | Nov 22, 2026 | 🟡 In progress. Backend shelf-life and product APIs are done; the ML shelf-life endpoint is pending. |
| M7 | Complete Two-Module Application | Nov 29, 2026 | 🟡 In progress. Prediction History and Audit Log screens are done; product and Shelf Life screens are pending. |
| M8 | Automated QA and Held-Out Evaluation | Dec 6, 2026 | 🟡 In progress. Held-out evaluation and latency measurements pending. |
| M9 | End-to-End Validation and Release Candidate | Dec 13, 2026 | ⚪ Not Started |
| M10 | Local Release, Documentation, and Submission | Dec 21, 2026 | 🟡 Documentation Started |

### Working End to End

**Temperature forecasting (primary use case, Module 1)** works through all components:

1. In **Datasets**, the user uploads a sensor CSV. Spring Boot validates it, stores the file and its one-minute version, and saves the metadata in MongoDB.
2. In **Forecast**, the user selects a dataset, sensor, horizon(s), and threshold.
3. Spring Boot sends the 61 one-minute readings ending at the chosen time to FastAPI, which returns forecasts and peak estimates.
4. The backend saves the prediction and its audit events, and the desktop shows the forecasts, warnings, and actual readings.
5. **Prediction History** reopens the saved forecast, and **Audit Log** shows the Spring Boot and FastAPI events.

**Shelf-life estimation (Module 2)** works from the backend API down: a product created through `/api/v1/products` and linked to a dataset window can be estimated through `/api/v1/predictions/shelf-life`, and the result is saved, audited, and listed in the prediction history API. It still needs the ML shelf-life endpoint and the desktop screens.

## Scope and Design Changes Since Phase 1

| Change | Reason |
|---|---|
| Temperature data comes from Zenodo record 15130001 instead of Kaggle | It is real sensor data from a cold storage room (5-second readings, 2–11 September 2024), which fits our scenario, and it is openly licensed under CC BY 4.0 ([`docs/dataset_selection.md`](docs/dataset_selection.md)). |
| Module 2 uses a published, validated microbial growth model (Tsaloumi et al. 2022, fresh poultry) instead of a trained ML model or a generic Q10 factor | No public dataset with spoilage labels matched our temperature data (see [`docs/spoilage_dataset_selection.md`](docs/spoilage_dataset_selection.md)). A square-root growth model validated for poultry is more defensible than one Q10 value per category ([`docs/shelf_life_feasibility.md`](docs/shelf_life_feasibility.md)). |
| Only one product category, `FRESH_POULTRY_AEROBIC` | The growth model's parameters are validated for that product only. The `category` field stays so more categories can be added later. |
| Product profiles store the initial microbial load (N0) and physiological state (α0) instead of "recommended temperature" and "initial shelf life" | Remaining shelf life is now calculated by the growth model, so the user enters the model's inputs rather than a shelf life. |
| Forecast period is fixed at 15, 30, and 60 minutes instead of a free period such as 6 hours | The data has one-minute resolution and accuracy falls quickly with distance; three fixed horizons can each be trained and validated. |
| Point forecasts use a damped-trend method, and warnings use quantile models of the future peak, instead of one XGBoost regressor | On validation data the persistence baseline beat Ridge, Random Forest, and HistGradientBoosting; damped trend was the only candidate that improved on it ([`docs/model_improvement_experiment.md`](docs/model_improvement_experiment.md)). |
| Short gaps are not filled at upload; minutes with too few readings are stored as missing | Filling gaps would feed invented values to the forecasting model. The backend now matches the ML preprocessing exactly (checked against a shared fixture), forecasts require 61 complete minutes, and the shelf-life model bridges gaps of up to 10 minutes itself. |
| CSV parsing uses our own `CsvParser` instead of Apache Commons CSV | It reads the dataset's semicolon layout, reports every invalid row with its reason, and keeps the same rules as the Python preprocessing. |
| Desktop screens are built in Java code instead of FXML | Screens share layout helpers (`Views`, `MainWindow`), which kept them consistent with less duplication. |
| Docker Compose starts only MongoDB; the two services run from Maven and Python | JavaFX needs the local desktop, and running the services directly made debugging easier during development. |
| Audit records are split into one MongoDB collection per category, with separate database accounts for the backend and the ML service | Each service can be limited to inserting into its own collections, which enforces FR-19 in the database and not only in code. |

## Design Patterns and Principles Applied

Examples come from the implemented dataset, forecasting, shelf-life, product, prediction-history, and audit code across the JavaFX desktop, Spring Boot backend, Python ML service, and MongoDB. The first table covers patterns from the course handout *Creational Design Patterns*; the second covers other patterns and principles.

### Patterns from the course

| Pattern / Principle | Where | Why |
|---|---|---|
| **Builder** | `BackendClient` builds its JSON mapper, HTTP client, and each request with `builder()` / `newBuilder()` ([BackendClient.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/client/BackendClient.java)) | Modules, timeouts, headers, and bodies are configured step by step before the object is created, instead of through long constructors. |
| **Simple Factory** | Python `create_model(model_name)` ([forecast_models.py](services/ml/src/cold_storage_ml/forecast_models.py#L92)); the view-supplier registry in `MainWindow` ([MainWindow.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/ui/MainWindow.java#L45)) | Training code gets the same pipeline interface for every model choice, and navigation asks for a screen by its identifier and builds it only when needed. |
| **Factory Method (framework hook)** | FastAPI `AuditedRoute` overrides `APIRoute.get_route_handler()` ([audit.py](services/ml/src/cold_storage_ml/audit.py#L82)) | FastAPI keeps its dispatch logic, while our subclass decides how each handler is built so it also records audit events. |
| **Singleton-style reuse, lazy initialization** | FastAPI `get_service()` ([app.py](services/ml/src/cold_storage_ml/app.py#L29)); Spring's singleton-scoped services such as `TemperatureForecastService` | Model artifacts are loaded once per process on first use, and every request shares the same service instance. |
| **Constructor Dependency Injection** | `TemperatureForecastService`, `ShelfLifeService`, `ProductService` receive their repositories, `MlClient`, `AuditService`, and settings through constructors ([TemperatureForecastService.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/prediction/TemperatureForecastService.java#L59)); `DesktopApplication` passes a `BackendClient` into `MainWindow` | Classes do not create their own collaborators, so tests pass mocks and a fixed clock without starting MongoDB or FastAPI. |
| **Object pool (executor)** | The desktop `Async` helper's cached thread pool ([Async.java](apps/desktop/src/main/java/edu/northeastern/cs5010/desktop/ui/Async.java)) | Backend calls run off the JavaFX thread, so the window stays responsive, and idle worker threads are reused for later calls. |

### Other patterns and design principles

| Pattern / Principle | Where | Why |
|---|---|---|
| **Layered architecture, separation of concerns** | JavaFX views → Spring controllers → Java services → FastAPI inference ([architecture.md](docs/architecture.md)); the upload path splits `CsvParser`, `MinuteResampler`, and `DatasetStorage` ([DatasetService.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/dataset/DatasetService.java)) | Each layer has one job, and each step of the upload can be tested on its own. |
| **Thin controllers, single responsibility** | `ProductController` and `PredictionController` only map HTTP; rules live in `ProductService` and `ShelfLifeService` ([ProductService.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/product/ProductService.java), [ShelfLifeService.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/prediction/ShelfLifeService.java)) | Business rules can be unit-tested without HTTP, and each class changes for one reason. |
| **Repository, interface segregation** | `DatasetRepository`, `PredictionRepository`, `ProductRepository`; `AuditRepository` exposes only insert and read ([AuditRepository.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/audit/AuditRepository.java)) | MongoDB details stay in one layer, and no application code is given a way to modify audit records (FR-19). |
| **Facade, protocol adaptation** | Desktop `BackendClient` (`forecast()`, `predictionHistory()`, …); backend `MlClient` ([MlClient.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/inference/MlClient.java)) | Callers use plain Java methods and types; HTTP, JSON, and FastAPI error formats are handled in one place. |
| **Data Transfer Objects, stable contracts** | Java records such as `MlDtos`, `TemperatureForecast`, `ShelfLife`, `Products`; Python Pydantic models ([inference.py](services/ml/src/cold_storage_ml/inference.py)); versioned examples in [`docs/contracts/examples`](docs/contracts/examples) | Both sides of each API agree on one documented shape, and contract tests catch changes. Saved results can be reopened without the model service or source CSV. |
| **Optimistic concurrency control** | `@Version` field on `ProductDocument` ([ProductDocument.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/product/ProductDocument.java)) | An edit made from an outdated copy is refused with `409 VERSION_CONFLICT` instead of silently overwriting a newer change, without locking records. |
| **Observer, event-driven behavior** | JavaFX selection listeners in `MainWindow` and `AuditView`; Spring lifecycle listener `ApplicationLifecycleAudit` ([ApplicationLifecycleAudit.java](services/backend/src/main/java/edu/northeastern/cs5010/backend/audit/ApplicationLifecycleAudit.java)) | Screens react to user selection, and startup/shutdown are audited without the code that starts the app knowing about auditing. |
| **Decorator-style request wrapping** | `AuditedRoute` wraps each FastAPI handler ([audit.py](services/ml/src/cold_storage_ml/audit.py#L82)) | Success, validation failure, and system errors are audited around the call, keeping audit code out of the model calculation. |
| **Immutable records, compensating actions** | `DatasetDocument.withStatus()`, `ProductDocument.withVersion()`; the audit-then-undo steps in `DatasetService`, `ProductService`, and `ShelfLifeService` | The previous state is kept as an unchanged value, so a change whose audit record cannot be written is undone exactly (docs/audit_controls.md). |
| **Reuse, consistent presentation** | `ForecastView.resultContent()` used by both `ForecastView` and `PredictionHistoryView` | A new forecast and a reopened one are displayed identically. |

## Testing

The repository uses **JUnit 5, Mockito, and AssertJ** for Java and **pytest** for Python. GitHub Actions runs every suite on each push and pull request ([ci.yml](.github/workflows/ci.yml)).

- **Unit tests:** 92 Java tests (11 desktop, 81 backend) and 75 Python tests, all passing. They cover CSV parsing and resampling, the forecast and shelf-life services, product rules, audit writing, the desktop HTTP client and form rules, and the ML preprocessing, models, inference, warnings, and audit events.
- **Integration tests:** `DatasetWorkflowIntegrationTest` runs the backend against a real MongoDB (Testcontainers) for the dataset upload and delete workflow, forecast and shelf-life prediction history, the product create/edit/delete workflow with version checks, and the audit API. `MlClientContractTest` checks the backend against the versioned API examples in [`docs/contracts/examples`](docs/contracts/examples), and `MinuteResamplerTest` checks Java against the Python preprocessing on a shared fixture.
- **Coverage:** not measured yet; adding JaCoCo and pytest-cov reports is part of M8.

```bash
./mvnw --batch-mode verify                    # all Java tests (integration tests need Docker running)
./.venv/bin/python -m pytest services/ml/tests  # Python tests
```

On Windows use `.\mvnw.cmd` and `.\.venv\Scripts\python.exe`. Without Docker, the MongoDB integration tests are skipped rather than failed.

## Known Issues

- The ML service has no shelf-life endpoint yet, so `POST /api/v1/predictions/shelf-life` returns a prediction-service error outside the tests.
- The desktop app has no product or Shelf Life screen yet, and Prediction History cannot reopen shelf-life results.
- The forecast screen shows a table but not the line chart required by FR-7.
- Forecasts support only SENSOR01, SENSOR02, and SENSOR04, the sensors the models were trained on.
- Model results so far are on validation data only; the held-out evaluation (FR-9, NFR-3) and the latency measurement (NFR-1) are still to be run.
- Code coverage is not reported yet (NFR-7).

## Next Steps

- Add the ML shelf-life endpoint and the desktop product and Shelf Life screens, then let Prediction History reopen shelf-life results.
- Add the forecast line chart.
- Freeze the models, run the held-out evaluation for both modules, and measure end-to-end latency.
- Add coverage reports and end-to-end failure tests (ML service or MongoDB unavailable).
- Prepare repeatable demos and the final documentation.

## Team Contributions

### Phase 3

| Member | Contributions |
|---|---|
| **Zi Ye** | Trained the spoilage prediction model and integrated it into the application’s prediction workflow. |
| **Leyang Pan** | Implemented audit logging for temperature forecasts, built the JavaFX Audit Log and Prediction History screens, and connected both screens to the Spring Boot APIs. |
| **Bo Sun** | Built the product API (create, edit, soft delete, temperature links with version checks) and the shelf-life prediction endpoint with saving, auditing, and product names in prediction history; wrote their unit and MongoDB integration tests. |

### Phase 2

| Member | Contributions |
|---|---|
| **Zi Ye** | Managed GitHub issues, milestone planning, and team progress tracking; established the repository architecture, development environment, and CI workflow; researched and documented dataset selection; contributed to full-stack development and project documentation. |
| **Leyang Pan** | Developed and evaluated the temperature forecasting pipeline, including preprocessing and model training; implemented predictive threshold warnings and FastAPI inference endpoints; wrote Python tests, evaluation reports, and ML/Overall documentation. |
| **Bo Sun** | Developed the JavaFX frontend, Spring Boot backend, and MongoDB persistence workflows; implemented CSV processing, dataset management, ML API integration, and audit logging; wrote Java unit and integration tests and technical design documentation. |

