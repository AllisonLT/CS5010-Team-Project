# JavaFX Screen Wireframes (Issue #2)

## Issue and milestone context

- **Issue:** #2 — Design the JavaFX screen wireframes
- **Milestone:** M2: Architecture, contracts, and development setup
- **Depends on:** #1, #13 (`docs/shelf_life_feasibility.md`)
- **Uses:** API A in `docs/api_contracts.md` (#16)
- **Implemented by:** #3 (navigation), #23 (datasets), #37 (forecast), #38 (shelf life), #39 (history), #40 (audit)

The PDF asks for "a simple JavaFX interface with text-based results and tables" built from TableView, Label, TextField, ComboBox, and Alert dialogs. The wireframes follow that: every result is shown as text and in a table. One optional line chart is marked **[optional]**; the PDF does not require it and it can be dropped without changing anything else.

## Layout shared by all screens

```text
+--------------------------------------------------------------------------------+
| Smart Cold Storage Monitor                                                     |
+------------------+-------------------------------------------------------------+
| > Home           |                                                             |
|   Datasets       |                  (selected screen)                          |
|   Temperature    |                                                             |
|     Forecast     |                                                             |
|   Products &     |                                                             |
|     Shelf Life   |                                                             |
|   Prediction     |                                                             |
|     History      |                                                             |
|   Audit Log      |                                                             |
+------------------+-------------------------------------------------------------+
| Backend: UP   Database: UP   ML service: NOT READY (models missing)   [Refresh]|
+--------------------------------------------------------------------------------+
```

- **Left:** a `ListView` for navigation. This is the PDF's "Main Menu".
- **Bottom:** a status bar filled from `GET /api/v1/status`, refreshed on start, every 30 seconds, and by the Refresh button.
- If the backend cannot be reached at all, every screen is replaced by:

```text
+--------------------------------------------------------------------------------+
|  Cannot connect to the backend at http://127.0.0.1:8080.                       |
|  Start it with the command in docs/development.md, then click Retry.           |
|                                                                [ Retry ]       |
+--------------------------------------------------------------------------------+
```

- Long-running calls (upload, predictions) disable their button and show "Working…" with a `ProgressIndicator`. They run on a background thread so the window stays responsive.
- Every error from the backend opens an `Alert` (ERROR) with the response's `detail` text. If there is a list of `errors`, it is shown in the expandable section of the dialog.

## 1. Home

```text
+-------------------------------------------------------------+
| Home                                                        |
|                                                             |
|  Datasets uploaded: 3          Products: 2                  |
|  Last prediction: 2026-11-07 12:01  Temperature forecast    |
|                                                             |
|  [ Upload a dataset ]  [ Run a forecast ]  [ Estimate shelf life ] |
|                                                             |
|  These predictions support decisions only. They are not     |
|  food safety certifications.                                |
+-------------------------------------------------------------+
```

The three buttons jump to the matching screens.

## 2. Datasets

```text
+------------------------------------------------------------------------------+
| Datasets                                          [ Upload CSV… ]  [ Delete ] |
+------------------------------------------------------------------------------+
| Name                  | File                       | Sensors  | Rows    | Status       |
|-----------------------+----------------------------+----------+---------+--------------|
| Cold room sensor 02   | SENSOR02_preprocessed.csv  | SENSOR02 | 156,835 | Ready        |
| Cold room sensor 04   | SENSOR04_preprocessed.csv  | SENSOR04 | 156,835 | Ready        |
| Old upload            | test.csv                   | SENSOR01 |   1,200 | File missing |
+------------------------------------------------------------------------------+
| Details: Cold room sensor 02                                                 |
|  Columns:      Date, Time, Temperature, Humidity                             |
|  Time range:   2024-09-02 09:35:25 to 2024-09-11 11:24:55                    |
|  Minutes:      13,070 (1 missing; longest gap 1 minute)                      |
|  Temperature:  5.8 °C to 38.7 °C                                             |
|  Warnings:     none                                                          |
+------------------------------------------------------------------------------+
```

**Upload dialog** (opened by "Upload CSV…" after a `FileChooser` limited to `*.csv`):

```text
+---------------------------------------------------+
| Upload dataset                                    |
|  File:          SENSOR02_preprocessed.csv (4.8 MB)|
|  Display name:  [ Cold room sensor 02         ]   |
|  Sensor ID:     [ SENSOR02  ]  (from file name)   |
|                                                   |
|                      [ Cancel ]  [ Upload ]       |
+---------------------------------------------------+
```

**States:**

| State | What the user sees |
|---|---|
| Success | Dialog closes. The new row is selected and its details are shown. |
| Validation error (422 `CSV_INVALID`) | Error dialog: "The CSV file could not be used. 3 rows have an invalid date. Expected dd.mm.yyyy." Expandable list: "Row 2, Date: '2024-09-02' is not dd.mm.yyyy". Nothing is added to the table. |
| File too large / not CSV | Error dialog with the limit ("Files must be .csv and at most 50 MB"). |
| Rows skipped (warnings) | Upload succeeds; the details panel lists the warnings, e.g. "61 rows had a non-numeric temperature and were skipped". |
| File missing | Row status shows "File missing"; the row cannot be selected on the other screens. |
| Delete | Confirmation: "Delete 'Cold room sensor 02'? Products linked to it will lose their temperature history. Past predictions stay in the history." |

## 3. Temperature Forecast

```text
+------------------------------------------------------------------------------+
| Temperature Forecast                                                         |
|  Dataset:    [ Cold room sensor 02       v ]   Sensor: [ SENSOR02 v ]        |
|  Forecast from: [ 2024-09-11 11:25 ]  ( ) Latest available  (*) Choose time  |
|  Horizons:   [x] 15 min  [x] 30 min  [x] 60 min                              |
|  Threshold:  [ 10.0 ] °C                                    [ Run forecast ] |
+------------------------------------------------------------------------------+
|  !  WARNING: The temperature may reach 10.0 °C within 30 minutes.            |
+------------------------------------------------------------------------------+
|  Current reading (2024-09-09 14:00): 8.1 °C (below threshold)                |
|                                                                              |
|  Horizon | Time             | Forecast | Peak estimate | Crosses? | Actual   |
|  --------+------------------+----------+---------------+----------+--------- |
|  15 min  | 2024-09-09 14:15 |  8.4 °C  |  9.1 °C       | No       |  8.6 °C  |
|  30 min  | 2024-09-09 14:30 |  8.9 °C  | 10.3 °C       | Yes      | 10.4 °C  |
|  60 min  | 2024-09-09 15:00 |  9.6 °C  | 11.2 °C       | Yes      |  9.2 °C  |
|                                                                              |
|  Predicted minimum: 8.4 °C    Predicted maximum: 9.6 °C                      |
|  (Minimum and maximum cover the forecast points above only, not every        |
|   minute of the period. The peak estimate is an upper estimate, not a        |
|   guarantee or a probability.)                                               |
|                                                                              |
|  [optional] Line chart: last 60 minutes of readings, forecast points,        |
|             threshold line, and actual readings after the forecast time.     |
|                                                                              |
|  Saved to Prediction History.  Model: forecasting-2026-11-07                 |
+------------------------------------------------------------------------------+
```

The "Actual" column appears only when the dataset has readings after the chosen time. This lets the user check the forecast against what really happened.

**Warning banner by level:**

| Level | Banner text | Style |
|---|---|---|
| `CURRENT_VIOLATION` | "The current reading is already at or above 10.0 °C." | Red |
| `IMMINENT` | "The temperature may reach 10.0 °C within 15 minutes." | Red |
| `WARNING` | "… within 30 minutes." | Orange |
| `ADVISORY` | "… within 60 minutes." | Yellow |
| `NONE` | "No threshold crossing is forecast in the selected horizons." | Gray |

Each banner also has a text prefix ("!", "!!"), so the meaning does not depend on color alone.

**Input checks before sending:** at least one horizon checked; threshold is a number between −50 and 60. Invalid fields get a red border and a message under the field, and Run is disabled.

**Error states:**

| Error | Message |
|---|---|
| `INSUFFICIENT_HISTORY` | "There are not 61 consecutive minutes of readings before 2024-09-09 14:00. Choose a later time." |
| `UNSUPPORTED_SENSOR` | "The forecasting model was not trained for SENSOR07. Supported sensors: SENSOR01, SENSOR02, SENSOR04." |
| `ML_UNAVAILABLE` | "The prediction service is not running or its models are missing. No forecast was made." |
| `ML_TIMEOUT` | "The prediction service did not answer in time. No forecast was made. Try again." |

On any error, the results area is cleared, so an old result is never shown as if it were new.

## 4. Products and Shelf Life

```text
+------------------------------------------------------------------------------+
| Products & Shelf Life                                   [ New ] [ Delete ]   |
+--------------------------------+---------------------------------------------+
| Product              | Linked? | Name:      [ Chicken breast batch 14     ] |
|----------------------+---------| Category:  [ Fresh poultry (aerobic)  v ]  |
| Chicken breast b. 14 | Yes     | Initial contamination (N0):                 |
| Thigh fillets 3      | No      |            [ 2.0 ] log CFU/cm²  (1.0–4.0)   |
|                      |         | Physiological state (α0): [ 1.0 ] (0.1–1.0)|
|                      |         | Temperature history:                        |
|                      |         |   Dataset [ Cold room sensor 02 v ]         |
|                      |         |   Sensor  [ SENSOR02 v ]                    |
|                      |         |   From    [ 2024-09-08 18:05 ]              |
|                      |         |   To      [ 2024-09-10 02:45 ]              |
|                      |         | Notes:     [ Received 2024-09-08         ] |
|                      |         |                                  [ Save ]   |
+--------------------------------+---------------------------------------------+
| Estimate shelf life                                                          |
|  Future storage temperature: [ 4.0 ] °C  (0–30; default: last reading)       |
|                                                       [ Estimate shelf life ]|
|                                                                              |
|  Remaining shelf life:  64 hours (2.7 days) at 4.0 °C                        |
|  Spoilage risk:         MEDIUM (72% of shelf life used)                      |
|  Estimated count:       5.6 log CFU/cm²  (spoilage level: 7.0)               |
|                                                                              |
|  Factor                                   | Value                            |
|  -----------------------------------------+-------------------------------   |
|  Time above 4 °C                          | 28 h 50 min                      |
|  Peak temperature                         | 21.9 °C                          |
|  Mean temperature                         | 9.8 °C                           |
|  Share of growth that happened above 4 °C | 91%                              |
|  Minutes filled by interpolation          | 3                                |
|                                                                              |
|  Limitations: Modeled estimate for fresh poultry in aerobic storage, based   |
|  on air temperature in the storage room. Not a food safety determination.    |
|  Model: Tsaloumi et al. (2022), doi:10.1016/j.psj.2022.101985                |
+------------------------------------------------------------------------------+
```

- The Category `ComboBox` lists only the categories from `GET /api/v1/capabilities` (currently one).
- "Estimate shelf life" is disabled until the product is saved and linked to a temperature history.
- When `flags` contains `OUTSIDE_VALIDATED_RANGE`, an extra line appears: "Some readings were outside 0–30 °C, where the model has not been validated."
- When the product is past the spoilage level: "Remaining shelf life: 0 hours. The modeled count has passed the spoilage level." with risk HIGH.

**Error states:**

| Error | Message |
|---|---|
| `VALIDATION_FAILED` on save | Messages under the fields, e.g. "Must be between 1.0 and 4.0" |
| `VERSION_CONFLICT` | "This product was changed somewhere else. Reload it and try again." [Reload] |
| `INSUFFICIENT_HISTORY` | "The linked history has a gap of 47 minutes starting 2024-09-09 03:10. Choose a window without gaps longer than 10 minutes." |
| `PRODUCT_NOT_LINKED` | Prevented by the disabled button |
| `DATASET_NOT_READY` | "The linked dataset is no longer available. Link another dataset." |

## 5. Prediction History

```text
+------------------------------------------------------------------------------+
| Prediction History     Type: [ All v ]   From [ 2026-11-01 ] To [ 2026-11-07 ]|
+------------------------------------------------------------------------------+
| Time              | Type        | Dataset / Product     | Result                          |
|-------------------+-------------+-----------------------+---------------------------------|
| 2026-11-07 12:05  | Shelf life  | Chicken breast b. 14  | 64 h remaining, MEDIUM risk     |
| 2026-11-07 12:01  | Forecast    | Cold room sensor 02   | May reach 10.0 °C within 30 min |
+------------------------------------------------------------------------------+
|                                         [ < Prev ]  Page 1 of 1  [ Next > ]  |
+------------------------------------------------------------------------------+
```

Double-clicking a row opens the saved result in the same layout as the Forecast or Shelf Life screen, read-only, with "Saved prediction from 2026-11-07 12:01" at the top. Only successful predictions appear here; failed attempts are in the Audit Log.

## 6. Audit Log

```text
+------------------------------------------------------------------------------+
| Audit Log   Event: [ All v ]  Outcome: [ All v ]  From [ … ] To [ … ] [Apply]|
+------------------------------------------------------------------------------+
| Time (local)         | Event                  | Outcome | Object            | Details                 |
|----------------------+------------------------+---------+-------------------+-------------------------|
| 2026-11-07 12:05:41  | PREDICTION_CREATED     | Success | Chicken breast…   | Shelf life              |
| 2026-11-07 12:03:10  | PREDICTION_FAILED      | Failure | Cold room sens. 02| ML_UNAVAILABLE          |
| 2026-11-07 11:40:12  | DATASET_UPLOADED       | Success | Cold room sens. 02| 156,835 rows            |
+------------------------------------------------------------------------------+
| Audit records are created automatically and cannot be edited or deleted.     |
+------------------------------------------------------------------------------+
```

Selecting a row shows all fields (source, host, actor, IDs, details, error) in a read-only panel below the table. There are no edit, delete, or clear buttons.

## Flow review against the PDF use case

| PDF use case step | Screen and control |
|---|---|
| 1. Upload a CSV of historical temperature records | Datasets → Upload CSV… |
| 2. Validate and process; identify timestamps, temperatures, other variables | Upload result: details panel, or error dialog listing invalid rows |
| 3. Forecast over a configurable period | Temperature Forecast → horizons and Run |
| 4. Show historical and predicted temperatures; explain threshold violations | Current reading, forecast table, Actual column, warning banner |
| 5. Select a product, enter storage information, associate a temperature history | Products & Shelf Life → product form and Temperature history fields |
| 6. Estimate deterioration or remaining shelf life | Estimate shelf life |
| 7. Present estimate, supporting factors, uncertainty or limitations | Result text, factor table, limitations text |
| 8. Store results and user actions for review | Prediction History and Audit Log |

**PDF user interface items also covered:** Main Menu (navigation list and Home), Dataset Upload Interface (screen 2), Temperature Forecasting Interface with summary of minimum and maximum (screen 3), Spoilage and Shelf-Life Prediction Interface (screen 4), Prediction History Interface (screen 5). The audit table (screen 6) is required by the PDF's functional requirement "Allow users to review audit logs in a simple table."

**Not included, per the PDF's scope:** storage scenario comparison, user accounts, and settings for turning audit logging off.

## Acceptance criteria (Issue #2)

- [x] Provide wireframes for navigation, dataset upload/selection, temperature forecasting, product/spoilage prediction, history, and audit review
- [x] Show validation/error states, forecast controls, and limitations text
- [x] Review the flows against the PDF user journeys
