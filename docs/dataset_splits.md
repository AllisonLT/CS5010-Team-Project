# Dataset Splits and Held-Out Evaluation Protocol (Issue #14)

## Issue and milestone context

- **Issue:** #14 — Define leakage-safe dataset splits
- **Milestone:** M1: Requirements and dataset feasibility
- **Depends on:** #11 (`docs/dataset_selection.md`), #12 (`docs/spoilage_dataset_selection.md`), #13 (`docs/shelf_life_feasibility.md`)

This document freezes the dataset choices and the rules for training, validation, and final testing. It records the split already implemented on the `Leyang` branch (`services/ml/src/cold_storage_ml/preprocessing.py`, `chronological_split`) and adds the rules that the final evaluation in M8 (#42) must follow.

## Frozen dataset selections

| Module | Source | Files used |
|---|---|---|
| 1. Temperature forecasting | Zenodo record 15130001 (Henrichs, Stoll, Krupitzer 2025), CC BY 4.0 | `Preprocessed.zip` (md5 `952eb73cdbc03f5d6319aaf8607c98e6`) → `SENSOR01_preprocessed.csv`, `SENSOR02_preprocessed.csv`, `SENSOR04_preprocessed.csv` |
| 2. Shelf life | Tsaloumi et al. (2022), DOI 10.1016/j.psj.2022.101985 | Published parameters only; no data files (see #12, #13) |

The other six sensors in the Zenodo record (SENSOR03, 05–09) are not used for training or tuning. They stay available as an extra held-out set for checking how forecasts behave on sensors the model has not seen, if the team decides to do that in M8.

## Module 1: temperature forecasting

### Partition rule

1. Readings are averaged into 1-minute bins (a bin is kept only if it has at least 75% of the expected 5-second readings). Missing bins stay missing. Nothing is interpolated before splitting.
2. The distinct 1-minute timestamps shared by all sensors are sorted and cut at 70% and 85%. All sensors use **the same clock boundaries**, so no sensor's test period overlaps another sensor's training period.
3. Each forecast example uses a feature history and a target at `origin + horizon`. **Both must fall inside the same partition.** Examples whose history or target crosses a boundary are dropped, so up to 60 minutes of examples at the start of validation and of test are lost on purpose.
4. Rolling and lag features use only readings at or before the origin.
5. Scalers, encoders, and any imputation are fitted on the training partition only.

### Resulting boundaries

Reproduced on 2026-11-07 by running the `Leyang` branch's `preprocess_dataset.py` on the three files above:

| Partition | Time range (naive local timestamps) | Rows (3 sensors × 1-minute bins) |
|---|---|---|
| Train | 2024-09-02 09:36 to before 2024-09-08 18:05 | 27,447 |
| Validation | 2024-09-08 18:05 to before 2024-09-10 02:45 | 5,880 |
| Test | 2024-09-10 02:45 to 2024-09-11 11:25 | 5,883 |

Temperatures are in °C. The logger code in the Zenodo record stores `temperatureC = dht.readTemperature()`. The preprocessing report should be updated from "not confirmed" to Celsius.

### Rules for model selection and tuning

- **Training** uses `train.csv` only.
- **Model and hyperparameter selection** uses `validation.csv`. Results reported on validation after selecting on validation are exploratory. The forecasting handoff already notes this for the damped-trend lag and weight choice.
- **For the final model, tuning should move to an inner chronological split of `train.csv`** (for example, the last 15% of train as an inner validation set). Validation can then give an honest pre-test check.
- **`test.csv` is not read by any training, selection, or tuning script.** The existing scripts already follow this.

## Module 2: shelf life

Module 2 (Option A in #13) fits no parameters to our data. The growth parameters come from the published study, so there is nothing to split and no way for our test data to leak into the model.

- **Evaluation** uses two independent references: the paper's published shelf-life values at constant temperature, and the paper's dynamic temperature profile (see #13). Neither comes from our datasets.
- **Demo histories** for the UI may be taken from any partition of the temperature data, because Module 2 is not tuned on them.
- **If the team chooses Option B** (an ML model trained on simulated labels), the simulated histories must be generated with separate random seeds for train, validation, and test. Real cold-room histories from the Zenodo record are then used for testing only, never for training.

## Final held-out evaluation protocol (for M8 #42)

1. **Freeze first.** Before the test run, commit the final preprocessing code, model artifacts or their reproducible training commands, and configuration. Record the commit hash in the evaluation report.
2. **Run once.** The test partition is scored once, with the frozen models, for 15, 30, and 60-minute horizons:
   - Point forecasts: MAE and RMSE, compared with the persistence baseline on exactly the same examples.
   - Threshold warnings: precision and recall per horizon, plus incident-level lead time, at the agreed demo threshold.
3. **No changes after the run.** If a model has to change after the test run, the report must say so and keep the first results. Any re-run is labeled as no longer a clean held-out result.
4. **Module 2:** report the reproduction error against the paper's reference values and the dynamic-profile check, as defined in #13.
5. **Report what is limited:** nine days of data, three sensors, one cold room, and air temperature rather than product temperature.

## Acceptance criteria (Issue #14)

- [x] Document training/validation/test partition rules for each dataset. Module 1: shared-clock 70/15/15 split. Module 2: no fitted parameters, so no split; Option B rules given if needed.
- [x] Prevent overlapping time windows, product batches, and repeated samples from crossing splits as applicable. Shared clock boundaries; history and target must be in the same partition; train-only fitting of transforms.
- [x] Freeze the dataset selections and final held-out evaluation protocol. See the two sections above.
