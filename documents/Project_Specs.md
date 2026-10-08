# SRS

## Smart Cold Storage Monitor

**Team:** [Team Name / Number]

### Team Members

Not specified.

---

## Problem Statement

Cold storage facilities for perishable food and temperature-sensitive pharmaceuticals (e.g., vaccines, biologics, active pharmaceutical ingredients) face critical risks from unpredicted equipment failures, improper door handling, and undetected thermal drift. Conventional cold chain monitoring solutions rely either on manual, periodic temperature checks or reactive threshold alarms that notify operators only after an excursion has already breached critical limits. By that point, valuable inventory is frequently compromised or spoiled, resulting in severe financial loss, public health hazards, and regulatory violations. Furthermore, many legacy systems lack standardized, tamper-evident audit logging compliant with institutional standards, making it impossible to produce verifiable records of compliance, chain-of-custody, and corrective actions during inspections.

---

## Objective / Goal

The goal of this project is to build an intelligent, end-to-end cold storage monitoring platform that continuously tracks freezer temperatures and environmental metrics in real time. The platform integrates machine learning predictive spoilage models to forecast thermal deviations and impending compressor failures hours before threshold breaches occur. To ensure regulatory and enterprise compliance, the system is strictly aligned with the **NIST SP 800-53** security framework, implementing tamper-evident audit logging (Audit and Accountability - AU control family), robust access control, and cryptographic log verification for audit readiness.

---

## One Use Case (Primary)

**User:** Cold Storage Facility Operations Specialist / Quality Assurance Auditor

1. Industrial IoT digital temperature sensors deployed inside vaccine storage freezers continuously stream temperature and humidity telemetry to the monitoring backend via secure MQTT.
2. The system's predictive spoilage model evaluates sliding-window time-series data, comparing current compressor cycles, defrost intervals, and ambient drift against baseline patterns.
3. The AI model detects an anomalous warming trend and predicts a temperature excursion exceeding the -20°C pharmaceutical safety limit within the next 3.5 hours.
4. The system immediately triggers a high-severity predictive alarm, notifying the on-duty facility technician through the web dashboard and automated SMS/email alerts.
5. In compliance with **NIST SP 800-53 (AU-2, AU-3, AU-6, AU-9)**, the platform automatically generates an immutable audit record capturing the event timestamp, sensor ID, anomaly confidence score, threshold limits, recipient of the alert, and full system state.
6. The technician inspects the refrigeration unit, replaces a failing door gasket, and inputs the corrective action report directly into the dashboard.
7. The quality assurance auditor reviews the incident timeline and exports a cryptographically verifiable audit log report for FDA cGMP and regulatory compliance filings.

---

## Scope of the Project

* Real-time telemetry ingestion and multi-point temperature/humidity tracking for food and pharma cold storage.
* AI-driven predictive spoilage and thermal excursion forecasting using time-series anomaly detection.
* NIST SP 800-53 compliant audit logging system with immutable records and role-separated access.
* Proactive multi-channel alerting engine (SMS, email, dashboard notifications) with escalation workflows.
* Dynamic inventory tolerance profiles mapped to storage units (e.g., Ultra-Low Temp -80°C freezers, standard -20°C pharma, 4°C food refrigeration).
* Real-time interactive web dashboard with spatial equipment layouts, live thermal curves, and health score indicators.
* Role-Based Access Control (RBAC) enforcing separation of duties (Operators, Maintenance Techs, Auditors, Administrators).
* Automated compliance reporting and historical audit log export (PDF, CSV, JSON).
* Device management interface for registering, calibrating, and managing IoT sensor nodes.

---

## Tech Stack

### Frontend

* **Framework:** React.js with TypeScript
* **UI Library:** Tailwind CSS and shadcn/ui
* **State Management:** Redux Toolkit or Zustand
* **Data Visualization:** Recharts or Chart.js (for real-time thermal curves, forecast bands, and sensor health charts)

### Backend

* **Framework:** Python FastAPI or Node.js with Express.js
* **API Architecture:** RESTful API with WebSockets for sub-second live telemetry updates
* **Authentication & Security:** OAuth 2.0 with JWT, Role-Based Access Control (RBAC), and Argon2 / bcrypt hashing

### Database

* **Time-Series Database:** TimescaleDB or InfluxDB (optimized for high-frequency sensor readings and thermal histories)
* **Relational Database:** PostgreSQL (for user profiles, equipment registries, inventory metadata, and audit logs)
* **Caching & Message Broker:** Redis (for live sensor state caching, rate limiting, and pub/sub messaging)

### Machine Learning

* **Language:** Python
* **Libraries:** scikit-learn, PyTorch / TensorFlow, statsmodels
* **Model:** Time-series forecasting and anomaly detection models (e.g., LSTM / Temporal Convolutional Networks or XGBoost) trained on cold chain thermal drift datasets and equipment degradation signatures
* **Serving:** FastAPI microservice or TorchServe

### External APIs & Hardware Integration

* **Sensor Protocols:** MQTT / HTTPS with TLS 1.3 encryption
* **Edge Gateway / IoT Simulators:** ESP32 / Raspberry Pi gateway firmware supporting digital temperature probes (RTD / PT100 / DHT22)
* **Notification Services:** Twilio API (SMS / Voice emergency alerts) and SendGrid API (email alerts and compliance digests)

### Development Tools

* **Version Control:** Git with GitHub or GitLab
* **Project Management:** Jira with Confluence
* **CI/CD:** GitHub Actions
* **Testing:** Pytest (backend & ML), Vitest / React Testing Library (frontend), Locust (telemetry load testing)
* **Containerization:** Docker & Docker Compose

### Hosting/Deployment

* **Cloud Platform:** AWS (EC2, S3, RDS, TimescaleDB, IoT Core) or Google Cloud Platform
* **Web Server / Reverse Proxy:** Nginx with SSL/TLS termination

---

## External Interface Requirements

### User Interfaces

* **Real-Time Overview Dashboard:** Facility overview showing monitored freezers, current temperatures, system health status, and live alarm banners.
* **Detailed Unit Telemetry View:** Granular sensor data graphs displaying actual temperatures, predicted future trajectory, and defined critical tolerance bands.
* **Predictive Risk & Spoilage Panel:** AI model outputs indicating risk percentages, estimated time-to-spoilage, and component failure warnings.
* **NIST SP 800-53 Audit Log Portal:** Immutable audit log explorer featuring multi-parameter filtering (by event type, user, timestamp, sensor ID, or NIST control identifier) and export functionality.
* **Asset & Inventory Profile Manager:** Interface to assign temperature tolerance parameters based on specific stored pharmaceuticals or food types.
* **Settings & Security Management:** Configuration for RBAC user roles, MFA enforcement, alert escalation paths, and API integrations.

### Hardware Interfaces

* **IoT Sensor Probes:** Calibrated temperature and humidity sensors connected via digital/analog interfaces (I2C, SPI, 1-Wire, Modbus).
* **IoT Edge Gateway:** Microcontroller or industrial gateway running lightweight edge client to aggregate sensor metrics and securely push via MQTT over TLS 1.3.
* **Client Devices:** Standard desktop and tablet/mobile web browsers for facility staff and field technicians.

### Software Interfaces

* **IoT Telemetry Gateway:** MQTT / WebSocket interface over TLS 1.3 accepting structured JSON payloads of sensor readings.
* **Machine Learning Inference Service:** Internal REST/gRPC API accepting windowed telemetry arrays and returning anomaly scores and time-to-excursion predictions.
* **Audit Logging Engine (NIST SP 800-53 AU):** Dedicated backend logging service that generates tamper-evident logs, enforces write-once-read-many (WORM) constraints, and validates cryptographic checksums.
* **Alert Dispatch Gateway:** Integration with external SMS (Twilio) and email (SendGrid) providers for rapid notification delivery.

---

## Functional Requirements

* Ingest continuous temperature, humidity, and door-status telemetry at configurable intervals (e.g., 5 to 60 seconds).
* Process streaming telemetry through AI models to predict temperature excursions and product spoilage risk ahead of real-time failures.
* Record every critical system event into an immutable audit trail aligned with **NIST SP 800-53**:
* Event types: user logins, threshold modifications, alert generations, technician acknowledgments, sensor calibrations, and system errors.
* Required fields: timestamp (UTC), subject identifier (user or system), event category, outcome, sensor/asset identifier, and cryptographic record hash.


* Dispatch prioritized alerts based on predicted risk and actual threshold violations across multiple channels.
* Provide configurable tolerance profiles for distinct storage categories (e.g., ultra-low biologics [-80°C], standard vaccines [-20°C], refrigerated produce [2°C–8°C]).
* Support Role-Based Access Control (RBAC) to ensure strict separation of operational, maintenance, and audit review roles.
* Generate automated, exportable compliance summary reports adhering to FDA/cGMP inspection standards.

---

## Non-Functional Requirements

* **Latency:** Telemetry ingestion to live dashboard refresh latency must be under 2 seconds; predictive inference completed within 5 seconds of telemetry window receipt.
* **Reliability & Availability:** System uptime target of 99.9% with local edge data buffering to prevent data loss during network disruptions.
* **Security & Integrity (NIST SP 800-53 Alignment):**
* Implementation of **AU-2** (Event Logging), **AU-3** (Content of Audit Records), **AU-6** (Audit Record Review, Analysis, and Reporting), and **AU-9** (Protection of Audit Information).
* All telemetry in transit encrypted via TLS 1.3; data at rest encrypted using AES-256.
* Audit logs are append-only with strict protection against modification or unauthorized deletion.


* **Usability:** Clean, high-contrast dashboard optimized for rapid assessment by facility personnel on desktop and mobile viewports.

---

## Estimated Timeline

### 1. Planning and Design (2 weeks)

**Week 1-2:**

* Set up development tools and communication repositories (Git, Jira, Confluence).
* Finalize system architecture and NIST SP 800-53 compliance mapping document.
* Design database schema (TimescaleDB hypertable for metrics + PostgreSQL relational schema for audit logs and entities).
* Define IoT data ingestion contracts and MQTT message payload formats.
* Create UI/UX wireframes and dashboard mockups.

### 2. Development - Core Features & Ingestion (2 weeks)

**Week 3-4:**

* Set up frontend application shell and component libraries.
* Implement user authentication, session security, and Role-Based Access Control (RBAC).
* Build backend API endpoints and integrate the PostgreSQL database.
* Configure MQTT broker and build telemetry ingestion pipeline with TimescaleDB.
* Develop the core live dashboard for real-time temperature graphing and asset status visualization.

### 3. Development - AI Models & Audit Logging (3 weeks)

**Week 5-6:**

* Implement the NIST SP 800-53 audit logging microservice (tamper-evident records, cryptographic hashing, and automated event capture).
* Develop and train the AI predictive spoilage and thermal drift time-series models in Python.
* Deploy ML inference endpoint and connect prediction pipelines to the ingestion stream.
* Implement the threshold and predictive multi-channel alerting service (SMS/Email/In-App).

**Week 7:**

* Build the NIST SP 800-53 audit log explorer and compliance report generation module.
* Implement storage profile configuration (food vs. pharmaceutical temperature bands).
* Integrate alert acknowledgment and corrective action workflow forms.

### 4. Testing and Quality Assurance (2 weeks)

**Week 8:**

* Conduct end-to-end integration and API test suites.
* Perform load and stress testing on telemetry ingestion pipelines simulating multiple concurrent freezer nodes.
* Validate machine learning model inference accuracy and false-alarm rates using synthetic drift datasets.

**Week 9:**

* Perform security and NIST SP 800-53 audit compliance validation (testing log immutability, role isolation, and access controls).
* Conduct User Acceptance Testing (UAT) for facility monitoring workflows.
* Bug fixing, performance optimizations, and UI polish.

### 5. Deployment and Documentation (1 week)

**Week 10:**

* Deploy containerized services to production cloud infrastructure (AWS/GCP).
* Finalize complete documentation (API reference, NIST alignment compliance report, and operator user manual).
* Record demonstration walkthrough and prepare final project presentation.