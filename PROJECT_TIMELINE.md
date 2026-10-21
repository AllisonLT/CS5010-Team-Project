# Project Timeline

## Planning basis

- **Source of truth:** [CS5010 Project Spec.pdf](<CS5010 Project Spec.pdf>), especially its requirements and Estimated Timeline sections.
- **Start:** October 21, 2026. **Final submission:** December 21, 2026.
- **Calendar constraint:** 62 calendar days inclusive (8 weeks, 6 days), rather than the PDF's estimated 10 weeks. Shorten setup and overlap the two ML workstreams; retain separate integration, quality assurance, and release gates.
- **Scope:** A single-local-user JavaFX desktop app, Spring Boot REST backend, Python FastAPI ML service, local MongoDB, and Kaggle CSV datasets. No sensors, live telemetry, accounts/RBAC, cloud deployment, automatic retraining, pharmaceuticals, or regulatory certification.
- **Clarification:** Storage-scenario comparisons are excluded, following the PDF's explicit out-of-scope statement and the team's planning decision.
- **Existing documentation:** `SRS.md` and the README describe a different simulator/cloud architecture. They are not the implementation baseline for this plan; reconcile them with the PDF during M1.
- **Ownership:** Assign a primary owner and reviewer to every task issue at kickoff. Suggested parallel workstreams are Java/UI, data/ML, and integration/storage/quality; these are responsibilities, not preassigned people.

## Milestone calendar

Dates are inclusive. Dependencies are release gates: work can be prepared earlier, but the milestone cannot close until its prerequisites are met.

| ID | Milestone | Work window | Due | Depends on |
|---|---|---|---|---|
| M1 | Requirements and dataset feasibility | Oct 21-25 | Oct 25 | None |
| M2 | Architecture, contracts, and development setup | Oct 26-Nov 1 | Nov 1 | M1 |
| M3 | Local application and dataset workflow | Nov 2-8 | Nov 8 | M2 |
| M4 | Java-to-Python inference integration | Nov 9-12 | Nov 12 | M3 |
| M5 | Temperature forecasting model | Nov 13-19 | Nov 19 | M1, M4 |
| M6 | Spoilage and shelf-life model | Nov 13-22 | Nov 22 | M1, M4 |
| M7 | Complete two-module application | Nov 23-29 | Nov 29 | M5, M6 |
| M8 | Automated QA and held-out evaluation | Nov 30-Dec 6 | Dec 6 | M7 |
| M9 | End-to-end validation and release candidate | Dec 7-13 | Dec 13 | M8 |
| M10 | Local release, documentation, and submission | Dec 14-21 | Dec 21 | M9 |

## Working rhythm and dependency management

- At kickoff, assign owners/reviewers, confirm team availability through late November and December, and agree on a short recurring integration check-in.
- Build focused JUnit 5 and pytest tests with features from M3 onward; M8 is a verification gate, not the first time testing begins.
- Review milestone checklists and blockers at each due date. Keep both ML branches using the same versioned inference contracts.
- The main dependency chain is M1 -> M2 -> M3 -> M4 -> M6 -> M7 -> M8 -> M9 -> M10; M5 runs alongside M6 and must also finish before M7 closes.
- Begin setup instructions, evaluation notes, and the user manual alongside implementation; M10 finalizes them.
- Do not add excluded functionality to recover from a blocked dataset or model. Escalate scope decisions explicitly and record their effect on the deadline.

## M1: Requirements and dataset feasibility

**Window:** October 21-25. **Due:** October 25. **Dependencies:** None.

**Work breakdown**

- Oct 21: Review the PDF together, reconcile the old README/SRS, and make a requirements-to-deliverables checklist for both modules, interfaces, audit controls, and non-functional requirements.
- Oct 22-23: Inspect candidate Kaggle temperature and food freshness/spoilage datasets, their access conditions, licenses, sizes, units, timestamps, features, and labels.
- Oct 24: Test whether the spoilage data supports associating food/product information with temperature exposure. Define supported food categories, input features, outputs, and limitations.
- Oct 25: Freeze the initial dataset choices and pass a documented feasibility review before model implementation.

**Acceptance criteria**

- [ ] Repository documentation agrees with the PDF's local Java/Python architecture and explicit exclusions.
- [ ] Both datasets have source links, license/access notes, reproducible retrieval instructions, and small permitted example inputs or a documented way to obtain them.
- [ ] Temperature data has usable timestamps and temperature units; spoilage data has identified target labels and a documented temperature-exposure relationship.
- [ ] Remaining shelf-life prediction is promised only where suitable labels or a validated model support it. If only spoilage-risk labels are available, document the limitation and obtain course/team approval for any reduced deliverable rather than inventing shelf-life values.
- [ ] Record the initial training/validation/test split strategy, preventing overlapping time windows, product batches, or repeated samples from leaking across splits as applicable.
- [ ] Owners and reviewers are assigned, open scope questions are recorded, and dataset feasibility blockers have explicit decisions.

## M2: Architecture, contracts, and development setup

**Window:** October 26-November 1. **Due:** November 1. **Dependencies:** M1.

**Work breakdown**

- Oct 26-27: Wireframe the main menu, CSV upload/selection, forecasting, product/spoilage, prediction history, and audit table.
- Oct 28-29: Define MongoDB collections, local CSV storage, and JavaFX -> Spring Boot -> FastAPI request/response contracts.
- Oct 30-Nov 1: Establish reproducible Maven/JavaFX, Spring Boot, Python, and local MongoDB development environments; add starter JUnit 5/pytest checks.

**Acceptance criteria**

- [ ] Architecture and repository/module layout separate UI, backend, persistence, and ML responsibilities.
- [ ] Versioned contracts define dataset/product identifiers, timestamps, units, forecast horizons, thresholds, prediction outputs, uncertainty/limitations, and validation/error responses.
- [ ] Dataset metadata, product profiles, prediction history, and separate audit records have documented schemas; raw CSV storage location and lifecycle are defined.
- [ ] AU-2, AU-3, AU-9, and AU-12 map to planned events, fields, automatic generation, and normal-application modification restrictions. Do not add hash-chain or certification requirements absent from the PDF.
- [ ] A representative supported input size, forecast horizon, machine configuration, and end-to-end timing method are recorded for the PDF's prediction-within-5-seconds requirement.
- [ ] All contributors can run the starter components and automated checks using documented commands; GitHub CI runs the available JUnit/pytest suites.

## M3: Local application and dataset workflow

**Window:** November 2-8. **Due:** November 8. **Dependencies:** M2.

**Work breakdown**

- Nov 2-3: Implement JavaFX navigation/forms and Spring Boot dataset endpoints.
- Nov 4-5: Add local CSV storage, MongoDB metadata, CSV parsing, timestamp/unit validation, and dataset selection.
- Nov 6-8: Complete the upload-to-selection path, validation feedback, dataset audit events, and focused tests.

**Acceptance criteria**

- [ ] A user can upload a supported CSV through JavaFX, see its filename, record count and attributes, save it, and select it after restarting the application.
- [ ] Invalid or missing columns, malformed timestamps, invalid temperatures, empty files, and missing values follow documented rejection/cleaning rules with actionable messages.
- [ ] Duplicate or irregular timestamps and missing-value handling have explicit policies; preprocessing does not leak future/test information.
- [ ] Dataset operations and failures automatically produce audit records with timestamp, event type, source, associated identifiers, and outcome in the separate collection.
- [ ] JUnit tests cover parsing/validation and persistence behavior, including failed uploads not leaving a valid-looking dataset.

## M4: Java-to-Python inference integration

**Window:** November 9-12. **Due:** November 12. **Dependencies:** M3.

**Work breakdown**

- Nov 9: Start FastAPI with validated inference schemas and health/readiness checks.
- Nov 10: Implement Spring Boot's HTTP client and mappings for both prediction modules.
- Nov 11-12: Exercise the complete JavaFX -> Spring Boot -> FastAPI path and error handling.

**Acceptance criteria**

- [ ] Both inference endpoints implement the agreed contracts; temporary deterministic test doubles are clearly identified and never presented as trained-model predictions.
- [ ] A representative dataset/product request travels through the Java application to Python and returns a correctly mapped response.
- [ ] Invalid inputs, unavailable service, timeouts, and malformed responses produce useful UI/backend errors and automatic error audit events, not fabricated predictions.
- [ ] JUnit/pytest contract checks cover success and failure paths; startup order and local service URLs are documented.

## M5: Temperature forecasting model

**Window:** November 13-19. **Due:** November 19. **Dependencies:** M1, M4.

**Work breakdown**

- Nov 13-14: Implement reproducible temperature cleaning, chronological splits, and time-series features.
- Nov 15-17: Train a suitable forecasting model and baseline; implement supported configurable horizons.
- Nov 18-19: Evaluate on validation data, save inference artifacts, and connect the real model to FastAPI.

**Acceptance criteria**

- [ ] A reproducible pipeline trains a baseline and at least one ML forecasting model without fitting preprocessing or selecting models on the held-out test set.
- [ ] Predictions honor supported user-selected horizons, preserve timestamp/temperature units, and include predicted minimum/maximum values.
- [ ] Configured threshold violations are identified consistently; boundary behavior and unsupported horizons are tested.
- [ ] Validation MAE/RMSE and baseline comparisons are recorded by relevant forecast horizon; the PDF specifies no numerical accuracy target, so do not invent a passing threshold.
- [ ] Saved model and preprocessing artifacts load through the inference service with documented versions, inputs, and limitations.
- [ ] Focused pytest cases cover missing/insufficient history, model loading, horizon behavior, and threshold warning behavior.

## M6: Spoilage and shelf-life model

**Window:** November 13-22. **Due:** November 22. **Dependencies:** M1, M4. Runs in parallel with M5.

**Work breakdown**

- Nov 13-15: Prepare supported product features, cumulative temperature-exposure features, target labels, and leakage-safe splits.
- Nov 16-19: Train spoilage-risk and, where supported, remaining-shelf-life estimators; compare appropriate baselines.
- Nov 20-22: Validate the model, document uncertainty and supported products, save artifacts, and expose real inference.

**Acceptance criteria**

- [ ] Product/category inputs and associated temperature histories are transformed consistently at training and inference time.
- [ ] The service estimates spoilage risk and provides remaining shelf life only where the M1 feasibility decision supports it; unsupported requests get an explicit explanation.
- [ ] Validation uses task-appropriate metrics, such as MAE/RMSE for shelf-life regression or precision/recall/F1 for classification, and compares baseline methods.
- [ ] Prediction output includes relevant contributing factors and justified uncertainty or clear model limitations; no unsupported confidence values or food-safety guarantees are shown.
- [ ] Artifacts and training instructions are reproducible, with focused pytest cases for unsupported categories, missing features, and invalid exposure histories.
- [ ] Hypothetical storage-scenario comparison remains excluded.

## M7: Complete two-module application

**Window:** November 23-29. **Due:** November 29. **Dependencies:** M5, M6.

**Work breakdown**

- Nov 23-24: Connect both trained models to the production application flow and finish product-profile/temperature-history association.
- Nov 25-26: Complete JavaFX prediction tables, forecast min/max, threshold warnings, spoilage/shelf-life results, and limitations.
- Nov 27-29: Finish persisted history, audit review, and a two-module integration demonstration. Confirm holiday availability in advance.

**Acceptance criteria**

- [ ] From the main menu, a user can upload/select a dataset, configure a horizon/threshold, and view real temperature predictions and warnings.
- [ ] A user can create/select a supported product profile, associate temperature records, and view the supported spoilage/shelf-life result and explanatory factors.
- [ ] Saved prediction details and timestamps survive a restart and can be reviewed in JavaFX.
- [ ] Dataset operations, predictions, threshold warnings, and system errors automatically create complete audit events; a simple table displays them.
- [ ] Audit records are stored separately and cannot be edited/deleted through normal application operations; application data-access permissions enforce the agreed restriction.
- [ ] Integration tests cover both modules and verify that failed predictions are not saved as successful results.

## M8: Automated QA and held-out evaluation

**Window:** November 30-December 6. **Due:** December 6. **Dependencies:** M7.

**Work breakdown**

- Nov 30-Dec 1: Run and close gaps in Java unit, Python, persistence, and HTTP integration coverage.
- Dec 2-3: Execute the frozen held-out evaluation for both modules and compare models against baselines.
- Dec 4-6: Measure prediction latency, validate audit restrictions, and triage defects against the requirements checklist.

**Acceptance criteria**

- [ ] JUnit 5 and pytest suites pass for supported behavior and documented failure cases; REST contract and CSV validation tests are included.
- [ ] A repository evaluation report records dataset/split definitions, model/baseline metrics, supported tasks, reproducibility commands, and limitations for both modules.
- [ ] Held-out results are not used for repeated tuning. If a model needs revision, document the evaluation protocol and preserve an untouched final test split.
- [ ] End-to-end prediction time, excluding offline training, meets the PDF's within-5-seconds requirement on the M2-defined workload/environment; record measurements and fix failures.
- [ ] Dataset operations, predictions, threshold warnings, and errors have the required audit fields; attempts to modify logs through normal application access are rejected.
- [ ] Blocking defects have owners and are resolved before M9 closes; non-blocking issues have explicit disposition.

## M9: End-to-end validation and release candidate

**Window:** December 7-13. **Due:** December 13. **Dependencies:** M8.

**Work breakdown**

- Dec 7-9: Run both complete user journeys with sample Kaggle inputs and a fresh local installation.
- Dec 10-11: Fix integration, prediction, performance, and usability defects.
- Dec 12-13: Re-run regressions, prepare repeatable demonstration examples, and freeze the release candidate.

**Acceptance criteria**

- [ ] Fresh-install testing covers startup, CSV upload/selection, both prediction modules, threshold warnings, saved history, and audit review.
- [ ] Missing/invalid data, service downtime, database unavailability, and prediction failures are handled without crashes or misleading successful output.
- [ ] A teammate unfamiliar with the tested workflow can complete it using the draft user instructions; record usability feedback and address blocking confusion.
- [ ] Performance is rechecked after fixes on the defined workload; Java/Python regression checks remain green.
- [ ] Repeatable demo inputs and expected outcomes are prepared, and no release-blocking defects remain.
- [ ] Feature freeze is in effect; only release fixes and documentation changes proceed into M10.

## M10: Local release, documentation, and submission

**Window:** December 14-21. **Due:** December 21. **Dependencies:** M9.

**Work breakdown**

- Dec 14-16: Finalize local installation/startup instructions for JavaFX, Spring Boot, FastAPI, MongoDB, datasets, and model artifacts; verify on a clean setup.
- Dec 17-18: Finish the architecture/API documentation, model training/evaluation report, user manual, audit-control mapping, and presentation.
- Dec 19: Rehearse the complete demonstration and freeze the submission package.
- Dec 20: Reserve for packaging, documentation, and last-minute release fixes; rerun affected checks.
- Dec 21: Submit the final project and verify the submitted artifact/repository reference and receipt before the course cutoff. Confirm the exact submission time and timezone rather than treating the GitHub due date as authoritative.

**Acceptance criteria**

- [ ] Documented commands start the full local stack and a new user can reproduce both supported prediction workflows.
- [ ] Submission includes source, dependency/build instructions, permitted dataset retrieval/sample guidance, model artifacts or reproducible generation instructions, and known limitations.
- [ ] Technical documentation covers component responsibilities, REST contracts, data schemas, training procedures, evaluation results, and AU-2/AU-3/AU-9/AU-12 implementation.
- [ ] User manual explains upload requirements, supported products/horizons, warnings, prediction history, audit review, and troubleshooting.
- [ ] Final presentation/demo is ready, the release revision is identified, and the team verifies successful submission by December 21.

## Contingencies and scope protection

| Risk | Checkpoint | Response |
|---|---|---|
| No usable shelf-life labels or validated model | M1, Oct 25 | Find a compatible labeled dataset immediately; otherwise seek approval for a clearly stated spoilage-risk-only limitation. Never fabricate remaining shelf life. |
| Data cannot support temperature exposure/product association | M1, Oct 25 | Select compatible inputs or document a justified mapping; resolve feasibility before committing to model outputs. |
| Java/Python contract drift | M2 contract freeze; M4 integration | Keep shared examples and automated contract checks; synchronize both callers and endpoints. |
| Model performs worse than baseline | M5/M6 validation; M8 held-out report | Report results honestly, improve only using training/validation data, and document the baseline comparison and limitations. |
| Predictions exceed 5 seconds | M8, Dec 6 | Profile preprocessing, artifact loading, HTTP transfer, and inference on the agreed workload; optimize and remeasure. |
| Integration slips or holiday availability is reduced | Before M7, Nov 23 | Prioritize the PDF's required local workflows; reassign blockers and protect QA and the Dec 20 release buffer. |

## GitHub tracking

**Milestones represent outcomes and deadlines; issues represent specific tasks assigned to those milestones.** There are 52 task issues across the 10 milestones, not one issue duplicating each milestone.

Each task issue has its own target completion date, prerequisite issue references, and acceptance checklist. For example, CSV parsing/validation, dataset persistence, and the JavaFX upload screen are separate tasks within M3. The milestone descriptions retain the overall outcome and phase acceptance criteria from this plan.

Close a task when its own acceptance criteria are satisfied. Close a milestone only when all required tasks are accepted and its overall acceptance criteria are met. Follow each milestone link below to its task list. Assign newly discovered defects as separate, specific issues rather than expanding an unrelated task.

| ID | GitHub milestone and task list | Initial task count |
|---|---|---|
| M1 | [Requirements and dataset feasibility](https://github.com/AllisonLT/CS5010-Team-Project/milestone/1) | 5 |
| M2 | [Architecture, contracts, and development setup](https://github.com/AllisonLT/CS5010-Team-Project/milestone/2) | 7 |
| M3 | [Local application and dataset workflow](https://github.com/AllisonLT/CS5010-Team-Project/milestone/3) | 6 |
| M4 | [Java-to-Python inference integration](https://github.com/AllisonLT/CS5010-Team-Project/milestone/4) | 4 |
| M5 | [Temperature forecasting model](https://github.com/AllisonLT/CS5010-Team-Project/milestone/5) | 5 |
| M6 | [Spoilage and shelf-life model](https://github.com/AllisonLT/CS5010-Team-Project/milestone/6) | 5 |
| M7 | [Complete two-module application](https://github.com/AllisonLT/CS5010-Team-Project/milestone/7) | 6 |
| M8 | [Automated QA and held-out evaluation](https://github.com/AllisonLT/CS5010-Team-Project/milestone/8) | 5 |
| M9 | [End-to-end validation and release candidate](https://github.com/AllisonLT/CS5010-Team-Project/milestone/9) | 4 |
| M10 | [Local release, documentation, and submission](https://github.com/AllisonLT/CS5010-Team-Project/milestone/10) | 5 |
