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
| Maven | No separate installation required; use the included Maven Wrapper (`mvnw.cmd` / `mvnw`) |
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

The install includes FastAPI, Uvicorn, NumPy, pandas, scikit-learn, joblib, and testing dependencies as declared in `services/ml/pyproject.toml`.

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
./mvnw -pl services/backend spring-boot:run
```

**Terminal 3 — JavaFX desktop UI**

```bash
./mvnw -pl apps/desktop javafx:run
```

Keep these three terminals open while using the application. The desktop communicates only with Spring Boot; Spring Boot calls FastAPI via HTTP and persists application records to MongoDB.

**Configuration (optional overrides):** The project uses local defaults, so no credentials or API keys are needed for the standard setup. To override defaults, set real environment variables in the terminal that starts the corresponding service; the application does not automatically load a `.env` file.

| Variable | Default | Used by |
|---|---|---|
| `ML_BASE_URL` | `http://127.0.0.1:8000` | Spring Boot → FastAPI |
| `MONGODB_URI` | `mongodb://127.0.0.1:27017/cold_storage` | Spring Boot → MongoDB |
| `BACKEND_PORT` | `8080` | Spring Boot |
| `UPLOAD_DIRECTORY` | `./data/uploads` (relative to backend working directory) | Spring Boot file storage |
| `BACKEND_URL` | `http://127.0.0.1:8080` | JavaFX → Spring Boot |
| `FORECAST_ARTIFACT_DIR` | `models/artifacts/forecasting` (relative to the ML process working directory) | FastAPI model loading |

Do not commit credentials or machine-specific `.env` files.

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

1. On the **Datasets** screen, click **Upload CSV…** and choose a source file such as `data/raw/SENSOR02_preprocessed.csv`. The sensor ID is read from the filename, so keep the `SENSORxx` part; if the filename has no sensor ID, the app asks for one. Wait until the dataset shows **READY**.
2. Open **Forecast** and choose the uploaded **READY** dataset and its sensor (`SENSOR01`, `SENSOR02`, or `SENSOR04`).
3. Under **Forecast from**, choose **Latest available** or **Choose time** (format `yyyy-MM-dd HH:mm`, e.g. `2024-09-09 14:00`), then select horizons (15/30/60 minutes) and a temperature threshold in °C.
4. Click **Run forecast** to request a real prediction via JavaFX → Spring Boot → FastAPI.
5. Check the displayed point forecasts, future-peak estimates, threshold warnings, and (when available) observed temperatures.

Each prediction requires **61 consecutive, valid one-minute readings** ending at the selected origin. Other sensor IDs are not supported by the currently trained models.

### 9. Build and Run Automated Tests

These commands can be run after installing dependencies. Stop any running app instances if needed; the Maven verification command also runs the Java test suite.

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
./mvnw --batch-mode verify

# Run Python ML tests.
./.venv/bin/python -m pytest services/ml/tests -v
```

`DatasetWorkflowIntegrationTest` uses Testcontainers and requires Docker. Without Docker, that integration test may be skipped. The Java ML-client contract tests use mock responses; passing them does **not** replace a manual end-to-end forecast check with trained artifacts.

### 10. Subsequent Runs and Shutdown

After completing Steps 3–5 once, you **do not need to preprocess or retrain on every startup** unless source data, training settings, or model files change. For normal development, start MongoDB and run the three components in Step 7.

To stop the application, use **Ctrl+C** in each service terminal and close the JavaFX window. To stop MongoDB without deleting its saved data:

```bash
docker compose stop mongodb
```

For further configuration and troubleshooting, see [`docs/development.md`](docs/development.md) and [`docs/_ML_Forecasting_Handoff.md`](docs/_ML_Forecasting_Handoff.md).

## Progress So Far

The following statuses summarize implementation against the **Phase 1 SRS functional requirements**. "Complete" refers to the presence of the feature and its focused checks in the repository. The temperature forecast path was run end to end on one machine on November 7, 2026 (real SENSOR02 upload, trained artifacts, JavaFX → Spring Boot → FastAPI, plus ML-service-down and unsupported-sensor errors); a full team walkthrough is still pending.

| Functional Requirement (from Phase 1) | Status | Notes |
|---|---|---|
| Upload and validate historical temperature CSV datasets | Complete | JavaFX upload workflow and backend CSV validation/resampling are implemented. |
| Store uploaded datasets and allow dataset selection | Complete | Local CSV storage with MongoDB metadata; datasets can be selected in the desktop UI. |
| Generate configurable short-term temperature forecasts | Complete | Python model-backed forecasts for 15-, 30-, and 60-minute horizons; JavaFX/Spring Boot/FastAPI call path is implemented. |
| Show temperature threshold predictions and warnings | Complete | Future-peak estimates and threshold warnings are returned and displayed by the forecast UI. |
| Compare forecasting model performance with baselines | Partial | Baseline and validation results are documented; the final held-out evaluation remains pending. |
| Manage food products and associate them with temperature histories | Not started | Product management and history association have not been implemented. |
| Estimate food spoilage risk and remaining shelf life | Partial | Feasibility research and a proposed validated growth-model approach are documented; the inference implementation is pending. |
| Display previously saved predictions | Partial | The temperature forecast backend stores predictions in MongoDB, but the desktop history screen is still a placeholder. |
| Generate and review audit logs | Partial | Backend audit-event recording is implemented; the desktop audit viewer and final access-control verification remain pending. |
| Handle invalid data and prediction-service failures | Partial | Focused parser, API, and error-handling tests exist; full multi-service failure scenarios still need verification. |

### Milestone Tracker

The project is tracked across ten milestones. See [PROJECT_TIMELINE.md](PROJECT_TIMELINE.md) for deliverables and acceptance criteria.

**Last updated:** November 7, 2026. M1 and M2 are marked complete based on the team's status decision; other statuses distinguish implementation progress from formal acceptance.

| Milestone | Description | Due Date | Status |
|---|---|---|---|
| M1 | Requirements and Dataset Feasibility | Oct 25, 2026 | ✅ Completed |
| M2 | Architecture, Contracts, and Development Setup | Nov 1, 2026 | ✅ Completed |
| M3 | Local Application and Dataset Workflow | Nov 8, 2026 | 🟡 Nearly Complete |
| M4 | Java-to-Python Inference Integration | Nov 12, 2026 | 🟡 Mostly Implemented |
| M5 | Temperature Forecasting Model | Nov 19, 2026 | 🟡 Nearly Complete |
| M6 | Spoilage and Shelf-Life Model | Nov 22, 2026 | ⚪ Early Development |
| M7 | Complete Two-Module Application | Nov 29, 2026 | 🟡 Partially Implemented |
| M8 | Automated QA and Held-Out Evaluation | Dec 6, 2026 | 🟡 Partially Implemented |
| M9 | End-to-End Validation and Release Candidate | Dec 13, 2026 | ⚪ Not Started |
| M10 | Local Release, Documentation, and Submission | Dec 21, 2026 | 🟡 Documentation Started |

### Working End to End

The **temperature forecasting code path is connected** across the application:

1. In **Datasets**, the user uploads a supported sensor CSV, which Spring Boot validates and stores alongside MongoDB dataset metadata.
2. In **Forecast**, the user selects a ready dataset, sensor, prediction horizon(s), and a temperature threshold.
3. Spring Boot selects 61 consecutive one-minute readings and requests a model-backed forecast from Python FastAPI.
4. The backend saves the prediction and related audit events in MongoDB and returns forecasts and warnings to JavaFX for display.

## Testing So Far

The repository uses **JUnit 5** for Java/JavaFX/backend testing and **pytest** for the Python forecasting service. Tests run automatically through [GitHub Actions](.github/workflows/ci.yml) on pushes and pull requests.

| Test suite | Coverage | Latest verified CI result |
|---|---|---|
| Java — JUnit 5 | JavaFX client/form rules, CSV parsing/resampling, dataset workflow, MongoDB integration, ML HTTP contracts, auditing, and temperature forecast service | **70 passed**, 0 failed, 0 skipped (10 desktop + 60 backend) |
| Python — pytest | ML service health, preprocessing, baselines, forecasting, inference, and warning behavior | **74 passed**, 0 failed (1 warning) |

Results above come from the [November 7, 2026 successful CI run](https://github.com/AllisonLT/CS5010-Team-Project/actions/runs/42415646944) for `main` commit `a219f05d51f155f018382e9330689e92d7215253`. The Java suite includes Docker-backed dataset integration tests; ML client contract tests use mock responses and do not substitute for real FastAPI end-to-end testing. Local test commands are provided in **Setup and Run → Build and Run Automated Tests**.

## Next Steps

- Repeat the temperature forecasting walkthrough on every team member's machine and add the remaining failure scenarios (e.g., MongoDB unavailable, corrupted artifacts).
- Finalize approval and implement the supported spoilage/shelf-life estimation method, product profiles, and temperature-history association.
- Implement the desktop **Shelf Life**, **Prediction History**, and **Audit Log** screens and connect them to backend persistence and inference.
- Finish held-out model evaluation, measured prediction latency, audit-control checks, and end-to-end regression testing.
- Prepare repeatable demos and final release documentation.

## Team Contributions

| Member | Contributions This Phase |
|---|---|
| **Zi Ye** | Managed GitHub issues, milestone planning, and team progress tracking; established the repository architecture, development environment, and CI workflow; researched and documented dataset selection; contributed to full-stack development and project documentation. |
| **Leyang Pan** | Developed and evaluated the temperature forecasting pipeline, including preprocessing and model training; implemented predictive threshold warnings and FastAPI inference endpoints; wrote Python tests, evaluation reports, and ML/Overall documentation. |
| **Bo Sun** | Revised the SRS; wrote API contract, data model, audit-control, and shelf-life feasibility docs; built the backend CSV upload/resampling, audit logging, and ML forecast integration, plus the JavaFX Datasets and Forecast screens; wrote Java unit, integration, and contract tests. |

