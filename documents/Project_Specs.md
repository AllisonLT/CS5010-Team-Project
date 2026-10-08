# Software Requirements Specification (SRS)

## Smart Cold Storage Monitor

**Team:** TBD

### Team Members

To be determined.

---

## Problem Statement

Temperature fluctuations in cold storage environments can affect the quality and shelf life of perishable food products. Conventional temperature monitoring systems generally rely on fixed thresholds and alerts, which only identify problems after temperatures have exceeded acceptable limits.

These systems also provide limited information about how historical temperature exposure affects stored products. Consequently, operators may struggle to predict temperature-related problems, identify potentially deteriorating products, and estimate their remaining shelf life.

We propose an AI-powered cold storage monitoring system that analyzes historical temperature datasets, predicts future temperature conditions, and estimates food spoilage risk and remaining shelf life.

The project will use publicly available Kaggle datasets rather than physical sensors, allowing development and evaluation of the predictive models without hardware dependencies.

## Objective / Goal

Develop a software-based cold storage monitoring and prediction platform with two primary AI modules:

**Module 1: AI Temperature Forecasting**

Analyze historical cold storage temperature records and predict future temperature changes. The module will identify potential temperature excursions before they occur and provide early warnings when forecast temperatures are expected to exceed configurable thresholds.

**Module 2: AI Spoilage and Shelf-Life Prediction**

Analyze temperature exposure history and food product characteristics to estimate product deterioration, potential spoilage risk, and remaining shelf life.

The modules will be integrated into a Java-based application, enabling users to upload datasets, visualize temperature trends, generate predictions, and evaluate the potential impact of changing storage conditions on food products.

Additional objectives include:

- Apply machine learning to practical cold storage management problems.
- Compare predictive models against baseline methods.
- Provide clear visual explanations of predictions.
- Maintain basic prediction and activity logs aligned with relevant NIST SP 800-53 auditing principles.
- Minimize external services, deployment complexity, and hosting costs.

## One Use Case (Primary)

**User:** Cold storage manager or facility operator

1. The user uploads a CSV dataset containing historical cold storage temperature records.
2. The application validates and processes the dataset, identifying timestamps, temperature readings, and available environmental variables.
3. The temperature forecasting module analyzes recent temperature trends and predicts temperature conditions over the next configurable period.
4. The application displays historical and predicted temperatures on a graph, highlighting potential threshold violations.
5. The user selects a food product, enters its relevant storage information, and associates it with a temperature history.
6. The spoilage and shelf-life prediction module evaluates the product's historical temperature exposure and estimates its deterioration level or remaining shelf life, using an appropriate trained model.
7. The application presents the predicted shelf-life estimate, supporting factors, and uncertainty or model limitations.
8. The user can adjust future temperature scenarios to compare their potential effects on product shelf life.
9. The application stores prediction results and relevant user actions for future review.

All predictions are intended for educational and decision-support purposes, not for certifying food safety.

## Scope of the Project

### Module 1 — AI Temperature Forecasting

- Historical temperature dataset upload and processing
- Temperature data cleaning and missing-value handling
- Time-series feature engineering
- Machine learning model training and evaluation
- Short-term temperature forecasting
- Configurable prediction horizons
- Detection of predicted temperature threshold violations
- Historical and forecast temperature visualization
- Comparison of forecast performance against baseline methods

### Module 2 — AI Spoilage and Shelf-Life Prediction

- Food product profile management
- Association of products with historical temperature records
- Analysis of cumulative temperature exposure
- Food deterioration or spoilage-risk estimation
- Remaining shelf-life prediction where suitable training labels or validated models are available
- Comparison of different storage temperature scenarios
- Visualization of predicted shelf-life changes
- Presentation of prediction confidence, uncertainty, and limitations

### General Application Features

- Dataset upload and management
- Interactive monitoring dashboard
- Prediction history
- Data visualization and reporting
- Basic user activity and prediction logging
- Integration between Java application components and Python ML services
- Local execution with optional lightweight cloud deployment

### Out of Scope

- Physical temperature sensors or IoT integration
- Actual refrigeration equipment control
- Real-time environmental data collection
- Pharmaceutical product stability certification
- Commercial food safety certification
- Fully automated food disposal decisions
- Enterprise-scale cloud infrastructure
- Continuous model retraining in production

---

## Tech Stack

### Frontend

- **Language:** Java
- **Framework:** JavaFX
- **UI Components:** JavaFX controls and FXML
- **Data Visualization:** JavaFX Charts
- **Styling:** JavaFX CSS

### Backend

- **Language:** Java
- **Framework:** Spring Boot
- **API:** RESTful API
- **Data Processing:** CSV parsing, validation, and data transformation
- **ML Integration:** HTTP communication with Python prediction endpoints

### Database

- **Primary Database:** H2 or SQLite for lightweight local data storage
- **Stored Information:** Dataset metadata, product profiles, prediction history, and audit records

### Machine Learning

- **Language:** Python
- **Libraries:** pandas, NumPy, scikit-learn, XGBoost
- **Time-Series Modeling:** Feature-based regression and time-series forecasting methods
- **Spoilage Modeling:** Regression or classification models, supplemented by scientifically supported deterioration models when appropriate
- **Model Evaluation:** MAE, RMSE, and task-specific classification metrics
- **Model Interface:** FastAPI
- **Model Persistence:** joblib

### External Data Sources

- **Primary Dataset Source:** Kaggle
- **Temperature Data:** Publicly available refrigeration, cold storage, or comparable environmental temperature time-series datasets
- **Spoilage Data:** Publicly available food freshness, spoilage, or shelf-life datasets with relevant labels
- **Supplementary Scientific References:** Published food deterioration and microbial growth models, when needed to define or validate shelf-life estimation assumptions

Final datasets will be selected after reviewing feature availability, target labels, data quality, and licensing.

### Development Tools

- **Version Control:** Git and GitHub
- **Project Management:** Jira or GitHub Projects
- **Java IDE:** IntelliJ IDEA
- **Python IDE:** VS Code or PyCharm
- **Java Build Tool:** Maven or Gradle
- **API Testing:** Postman
- **Java Testing:** JUnit 5
- **Python Testing:** pytest
- **Optional Containerization:** Docker

### Hosting / Deployment

- **Primary Deployment:** Local execution
- **Frontend:** JavaFX desktop application
- **Java Backend:** Locally hosted Spring Boot service
- **Python ML Service:** Locally hosted FastAPI service
- **Database:** Local H2 or SQLite instance
- **Optional Cloud Hosting:** A single lightweight server or virtual machine for remote demonstrations

Cloud hosting is not required for the core application.

---

## External Interface Requirements

### User Interfaces

**Dashboard**

- Display historical storage temperatures.
- Show predicted temperature trends.
- Highlight forecast threshold violations.
- Present an overview of associated products and prediction results.

**Dataset Management Interface**

- Upload CSV datasets.
- Validate required columns and timestamps.
- Preview uploaded records.
- Display data quality issues and preprocessing summaries.
- Select datasets for analysis.

**Temperature Forecasting Interface**

- Select historical dataset and forecasting period.
- Generate temperature predictions.
- Compare predicted and historical values.
- Display predicted threshold violations and potential warning times.

**Spoilage and Shelf-Life Prediction Interface**

- Select food product or product category.
- Enter available storage information and initial shelf life.
- Associate the product with temperature history.
- Generate deterioration or shelf-life estimates.
- Visualize cumulative temperature effects.

**Scenario Comparison Interface**

- Modify hypothetical storage temperature conditions.
- Compare predicted shelf-life outcomes between scenarios.
- Identify which temperature conditions are associated with increased modeled deterioration.

**Prediction History Interface**

- Review previously generated predictions.
- View associated model versions and inputs.
- Review stored prediction and activity logs.

### Hardware Interfaces

- Standard computer capable of running Java and Python applications
- No external sensors, microcontrollers, or IoT devices required
- No specialized GPU required for baseline machine learning models

### Software Interfaces

**Java Application to Java Backend**

- Communication through RESTful API endpoints
- JSON request and response format
- Dataset uploads through multipart/form-data

**Java Backend to Python ML Service**

- HTTP REST API communication
- JSON prediction requests and responses
- Separate endpoints for temperature forecasting and spoilage prediction

**Database Interface**

- Java persistence layer for dataset metadata, product information, and prediction records
- Local file storage for uploaded datasets and serialized ML models

---

## Functional Requirements

### Module 1 — AI Temperature Forecasting

**FR-01:** The system shall allow users to upload historical temperature datasets in CSV format.

**FR-02:** The system shall validate dataset structure, including timestamps and temperature measurements.

**FR-03:** The system shall preprocess temperature datasets by handling missing records, inconsistent intervals, and invalid measurements.

**FR-04:** The system shall generate time-series features such as historical temperature lags, rolling averages, temperature changes, and available environmental variables.

**FR-05:** The system shall train and evaluate at least one machine learning forecasting model.

**FR-06:** The system shall generate temperature predictions for a configurable future period.

**FR-07:** The system shall display historical and predicted temperature readings on a time-series graph.

**FR-08:** The system shall allow users to configure acceptable temperature thresholds.

**FR-09:** The system shall identify predicted threshold violations and display early warnings.

**FR-10:** The system shall evaluate forecasting performance using MAE and RMSE against held-out chronological test data.

### Module 2 — AI Spoilage and Shelf-Life Prediction

**FR-11:** The system shall allow users to create and manage food product profiles.

**FR-12:** The system shall allow users to associate food products with uploaded temperature records.

**FR-13:** The system shall calculate relevant temperature-exposure features, including excursion duration, cumulative exposure, and temperature variability.

**FR-14:** The system shall implement a spoilage-risk or shelf-life estimation model appropriate to the available dataset and scientifically justified modeling assumptions.

**FR-15:** The system shall generate product deterioration estimates and, where supported, remaining shelf-life predictions.

**FR-16:** The system shall display influential input factors and relevant prediction limitations.

**FR-17:** The system shall allow users to compare alternative hypothetical storage temperature scenarios.

**FR-18:** The system shall visually compare modeled deterioration or remaining shelf life between scenarios.

**FR-19:** The system shall evaluate spoilage model performance against appropriate labeled test data, where ground-truth outcomes are available.

### General System Requirements

**FR-20:** The system shall provide an integrated dashboard for both prediction modules.

**FR-21:** The system shall allow users to view and manage uploaded datasets.

**FR-22:** The system shall record prediction requests, timestamps, model versions, and prediction results.

**FR-23:** The system shall maintain basic audit records for relevant data-management actions and prediction events.

**FR-24:** The system shall handle invalid datasets, unavailable models, and prediction errors gracefully.

**FR-25:** The system shall support local execution without relying on paid cloud services.

---

## Non-Functional Requirements

### Performance

- Prediction requests should complete within 5 seconds for representative preprocessed datasets under normal local operating conditions.
- Dataset upload and preprocessing performance should be evaluated using defined sample file sizes.
- The application should remain responsive during model inference and data visualization.
- Model training should be performed separately from ordinary prediction requests.

### Accuracy and Reliability

- Temperature forecasting performance shall be evaluated using chronological training, validation, and testing splits.
- Temperature predictions should outperform a persistence baseline where feasible.
- Spoilage models shall be evaluated against independent labeled data when available.
- Predictions based on synthetic labels or assumed deterioration formulas shall be clearly identified.
- The system shall not present model outputs as definitive food safety assessments.

### Usability

- Provide an intuitive interface for users without machine learning expertise.
- Clearly distinguish historical measurements from AI predictions.
- Present temperatures in degrees Celsius.
- Include understandable warning messages and prediction explanations.
- Display missing-data warnings and unsupported-input errors.

### Security and Auditability

- Validate uploaded file types and contents.
- Log significant user actions and prediction events.
- Include event timestamps, event types, and relevant identifiers.
- Prevent ordinary application users from modifying historical audit entries through the interface.
- Apply applicable concepts from the NIST SP 800-53 Audit and Accountability control family.

### Maintainability

- Separate Java frontend, Java backend, and Python ML components.
- Provide documented REST APIs.
- Maintain versioned ML model artifacts.
- Use modular components to support independent development and testing.
- Keep application dependencies and deployment requirements minimal.

---

## Estimated Timeline

### 1. Planning and Design (2 weeks)

**Week 1–2:**

- Finalize software requirements and project scope.
- Research suitable Kaggle temperature and food spoilage datasets.
- Verify available target labels for shelf-life modeling.
- Select initial machine learning approaches.
- Design application architecture and database schema.
- Define Java–Python API contracts.
- Create JavaFX interface mockups.
- Set up GitHub repository and project management tools.

### 2. Development — Core Features (2 weeks)

**Week 3–4:**

- Implement JavaFX application structure.
- Set up Spring Boot backend.
- Create FastAPI ML service.
- Implement database integration.
- Develop CSV upload and validation functionality.
- Implement temperature history visualization.
- Create basic food product management functionality.
- Establish communication between Java backend and Python ML service.

### 3. Machine Learning and Application Development (3 weeks)

**Week 5–6:**

- Clean and preprocess selected datasets.
- Implement temperature forecasting baseline.
- Train and evaluate temperature forecasting model.
- Implement temperature forecasting API.
- Develop threshold warning functionality.
- Implement spoilage dataset preprocessing and feature engineering.
- Develop initial spoilage or shelf-life estimation model.
- Evaluate models against suitable baselines.

**Week 7:**

- Integrate spoilage prediction API.
- Develop shelf-life visualization.
- Implement temperature scenario comparison.
- Add prediction history and audit logging.
- Integrate results from both prediction modules into the dashboard.

### 4. Testing and Quality Assurance (2 weeks)

**Week 8:**

- Java unit testing.
- Python model testing.
- REST API integration testing.
- Dataset validation and error-handling tests.
- Verification of prediction logging.
- Evaluate forecasting and spoilage models using held-out data.

**Week 9:**

- End-to-end system testing.
- Usability testing.
- Performance testing.
- Resolve integration issues.
- Improve model evaluation and visualization.
- Prepare demonstration scenarios.

### 5. Deployment and Documentation (1 week)

**Week 10:**

- Finalize local application deployment.
- Optionally deploy backend services to a lightweight cloud instance.
- Prepare installation and configuration instructions.
- Document datasets, model assumptions, and evaluation results.
- Finalize API documentation and user manual.
- Create demo video and project presentation.
- Submit final application and documentation.

---

## Expected Deliverables

1. JavaFX desktop application with integrated dashboard.
2. Spring Boot backend with dataset and prediction management APIs.
3. Python temperature forecasting model.
4. Python spoilage and shelf-life estimation model.
5. FastAPI prediction service connecting the ML modules to the Java backend.
6. Kaggle dataset preprocessing and training scripts.
7. Model evaluation results and baseline comparisons.
8. Temperature visualization, spoilage estimation, and scenario comparison interfaces.
9. Basic prediction history and audit logging functionality.
10. Source code, SRS, technical documentation, and demonstration materials.
