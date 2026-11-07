# Local development

## Prerequisites

- JDK 21, with `JAVA_HOME` pointing to the JDK.
- Standard CPython 3.11 or newer with pip and venv, or Anaconda/Miniconda.
- Docker Engine/Desktop with Compose v2, or a locally installed MongoDB 7.
- Internet access for the first Maven wrapper/dependency and pip downloads.

The committed Maven wrapper selects Maven 3.9.11; no global Maven installation is
required. JavaFX native libraries are resolved by Maven for the host OS. The desktop
requires a graphical session; CI compiles it but does not open a window.

All commands below run from the repository root in PowerShell. On Linux/macOS,
use `bash ./mvnw` instead of `.\mvnw.cmd`, and `.venv/bin/python` instead of
`.\.venv\Scripts\python.exe`.

## Install and check

For Anaconda/Miniconda, create a project-specific environment without modifying
the base environment:

```powershell
conda create --prefix .\.conda-env python=3.11 pip --yes
.\.conda-env\python.exe -m pip install -e ".\services\ml[dev]"
.\mvnw.cmd --batch-mode verify
.\.conda-env\python.exe -m pytest services\ml\tests
```

Use `.\.conda-env\python.exe` instead of `.\.venv\Scripts\python.exe` in the
startup commands below. On Linux/macOS, the Conda executable is
`.conda-env/bin/python`. No shell activation is necessary.

Alternatively, with standard CPython:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".\services\ml[dev]"
.\mvnw.cmd --batch-mode verify
.\.venv\Scripts\python.exe -m pytest services\ml\tests
```

Avoid MSYS2/MinGW Python for this setup: its virtual environment layout and binary
wheel compatibility differ from standard Windows CPython. If `python` resolves
to MSYS2, use Conda or explicitly invoke a standard CPython installation.

JUnit and pytest failures return nonzero exit codes. CI runs the same available
suites on all pushes and pull requests without MongoDB, model artifacts, or a GUI.
No project linter is configured yet.

## Start local MongoDB

```powershell
docker compose up -d --wait mongodb
docker compose exec mongodb mongosh --quiet --eval "db.adminCommand({ping: 1})"
```

Compose binds MongoDB to loopback only and preserves database files in a named
volume. This unauthenticated instance is for local development only. Do not expose
it to a network or use it for sensitive data. Alternatively, run local MongoDB 7
on `127.0.0.1:27017`; the backend's default database is `cold_storage`.

## Start the components

Use separate terminals, each at the repository root. Start MongoDB, then ML,
backend, and desktop:

```powershell
# Terminal 1: ML service
.\.venv\Scripts\python.exe -m uvicorn cold_storage_ml.app:app --host 127.0.0.1 --port 8000

# Terminal 2: backend
.\mvnw.cmd -pl services\backend spring-boot:run

# Terminal 3: desktop
.\mvnw.cmd -pl apps\desktop javafx:run
```

Start them in this order. The backend checks MongoDB on startup, and the desktop asks the backend for status as
soon as it opens. The ML service may start later: until it is ready, the desktop's status bar shows
"ML service: DOWN" or "NOT READY" and forecasts return a clear error instead of a result.

The forecasting endpoint needs trained artifacts in `models/artifacts/forecasting` (or the folder named by
`FORECAST_ARTIFACT_DIR`); see `docs/_ML_Forecasting_Handoff.md` on the ML branch for how to create them.

Check service liveness in another terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
```

Expected responses are `{"status":"ok","service":"backend"}` and
`{"status":"ok","service":"ml"}`. These do not check dependency/model readiness. For that, use the backend's combined status:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/status
```

which reports `backend`, `database`, and `mlService` as `UP`, `DOWN`, or `NOT_READY`.
FastAPI's starter documentation is at `http://127.0.0.1:8000/docs`.

## Configuration and data

Set environment variables in the terminal that launches the backend:

| Variable | Default | Purpose |
|---|---|---|
| `BACKEND_PORT` | `8080` | Backend HTTP port; loopback binding is fixed in application properties. |
| `MONGODB_URI` | `mongodb://127.0.0.1:27017/cold_storage` | MongoDB connection. |
| `ML_BASE_URL` | `http://127.0.0.1:8000` | FastAPI service used for predictions. |
| `UPLOAD_DIRECTORY` | `./data/uploads` | Uploaded CSV files and their one-minute versions, relative to the backend working directory. |
| `BACKEND_URL` | `http://127.0.0.1:8080` | Set in the desktop's terminal if the backend runs elsewhere. |

The backend refuses to start if `UPLOAD_DIRECTORY` cannot be created or written, and names the folder in the
error. MongoDB calls give up after 3 seconds when the database is down, so requests fail quickly with
`DATABASE_UNAVAILABLE` instead of hanging.

`spring-boot:run` uses the backend module as its working directory. Set an absolute root-level upload path so
uploads land in the git-ignored `data/uploads`:

```powershell
$env:UPLOAD_DIRECTORY = Join-Path (Get-Location) "data\uploads"
```

Do not commit credentials, downloaded datasets, runtime CSV files, virtual
environments, or trained model artifacts. Small samples under `data/sample` need
license/provenance documentation; see `dataset_selection.md`.
There is no automatic `.env` loader: use actual environment variables.

## Tests that need Docker

`DatasetWorkflowIntegrationTest` starts its own MongoDB 7 container with Testcontainers. When Docker is not
running, the test class is skipped rather than failing; start Docker Desktop to run it. GitHub Actions runners
have Docker, so CI runs it.

## Stop and troubleshoot

Stop foreground services with Ctrl+C and close the desktop window. Run
`docker compose stop mongodb` to stop MongoDB without deleting stored data.
If startup reports a port conflict, stop only your own process or choose another
service port. Set `BACKEND_PORT` or change Uvicorn's `--port`; keep the future
backend ML client URL consistent through `ML_BASE_URL`.
MongoDB connection errors require checking the container/local process and URI;
an HTTP liveness response is not evidence of database connectivity.
