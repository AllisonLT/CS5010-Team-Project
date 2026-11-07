# Project

Members:
- Zi Ye <ye.z8@northeastern.edu>
- Leyang Pan <yangplypan@gmail.com>
- Bo sun <sun.b2@northeastern.edu>

## Project Description

**Smart Cold Storage Monitor** is a single-user local desktop application for
temperature forecasting and food spoilage/shelf-life estimation from CSV datasets.
JavaFX provides the UI, Spring Boot owns application logic and persistence, and
Python FastAPI serves ML inference. MongoDB stores metadata, products, prediction
history, and separate audit records; CSV files remain on the local filesystem.

This repository currently contains development starters, not working forecasting
or spoilage models. Live sensors, cloud deployment, accounts/RBAC, storage-scenario
comparisons, and hash-chain/certification requirements are outside the scope.

## Dependency requirements

### OS packages (apt)

On an apt-based Linux distribution whose repositories provide JDK 21 and Python
3.11 or newer, copy this package list into `apt-packages.txt`:

```text
openjdk-21-jdk
python3
python3-venv
python3-pip
```

Install the list with:

```bash
sudo apt-get update
xargs -r sudo apt-get install -y < apt-packages.txt
```

Package availability depends on the distribution/release; `python3` must resolve
to version 3.11 or newer. These apt commands are for Linux, not Windows/macOS.
For Windows or Conda setup, see [local setup](docs/development.md).

Additional requirements:

- JDK 21: set `JAVA_HOME` to your own JDK installation; a JRE alone is insufficient.
- Python 3.11 or newer: 3.11 is the CI/Conda baseline; use standard CPython or Conda, not MSYS2/MinGW Python.
- Maven 3.9.11: downloaded by the wrapper; no global Maven installation is needed.
- MongoDB 7.0: install using MongoDB's distribution-specific instructions, or use the supplied `mongo:7.0` container.
- Docker Engine/Desktop and Compose v2: only required for the container option; Docker must be running.
- A graphical desktop session: required to open JavaFX; headless CI only compiles the UI.

MongoDB and Docker are not included in the generic apt list because their packages
and repository setup depend on the Linux distribution.

Internet access is required for initial dependency downloads. Default local ports
are `8080` (backend), `8000` (ML), and `27017` (MongoDB); they must be available or
configured differently as described in [local setup](docs/development.md).

### Java libraries

Maven will install these automatically from `pom.xml`

- `org.openjfx:javafx-controls:21.0.9`
- `org.springframework.boot:spring-boot-starter-web:3.5.6`
- `org.springframework.boot:spring-boot-starter-data-mongodb:3.5.6`
- `org.springframework.boot:spring-boot-starter-test:3.5.6`
- `org.junit.jupiter:junit-jupiter`
- `org.openjfx:javafx-maven-plugin:0.0.8`

These are Maven coordinates, not apt or pip package names.

### Python libraries

The canonical manifest is `services/ml/pyproject.toml`. For a copyable
`requirements.txt` containing the current runtime/test libraries and the local
service package, use:

```text
fastapi==0.115.12
uvicorn==0.34.2
pytest==8.3.5
httpx==0.28.1
-e ./services/ml
```

From the repository root, using your virtual environment's Python:

```bash
python -m pip install -r requirements.txt
```

Alternatively, install directly from the manifest without creating a requirements
file:

```bash
python -m pip install -e "./services/ml[dev]"
```

`apt` cannot install a pip requirements file. FastAPI installs Pydantic and
Starlette transitively; pip supplies the build backend `setuptools>=75,<81`
through its isolated build environment.

ML training libraries are not selected yet; no NumPy, pandas, or scikit-learn
dependency is required by the current health-check starter.

### Portability

Windows: `mvnw.cmd`

Linux/macOS: `mvnw`

## Development

- [Local setup and commands](docs/development.md)
- [Architecture, module layout, and pending M2 work](docs/architecture.md)
- Java checks: `.\mvnw.cmd --batch-mode verify` (Windows) or `bash ./mvnw --batch-mode verify` (Linux/macOS).
- Python checks: install `services/ml` with its `dev` extra, then run `python -m pytest services/ml/tests`.

See `CS5010 Project Spec.pdf` for the full Software Requirements Specification.

See `PROJECT_TIMELINE.md` for the milestone plan.
