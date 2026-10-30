# Dataset Selection and Feasibility (Issue #11)

## Purpose
This document records the selected temperature dataset and evaluates feasibility for Issue #11.

## Issue and milestone context
- **Issue:** #11 — Select and document a usable temperature dataset
- **Milestone:** M1: Requirements and dataset feasibility
- **Target completion date:** 2026-10-23
- **Scope note:** This task is one milestone item only; storage-scenario comparisons are excluded.

---

## 1) Dataset identification

- **Selected dataset name:** `Temperature and Humidity Time Series of Cold Storage Room Monitoring`
- **Source platform:** `Zenodo`
- **Source URL:** `https://zenodo.org/records/15130001`
- **DOI:** `https://doi.org/10.5281/zenodo.15130001`
- **Publish date:** `Apr. 3, 2025`
- **Access date (YYYY-MM-DD):** `Ocs. 21st, 2026`
- **Dataset owner/author(s):** `Henrichs Elia, Stoll Florian, Krupitzer Christian`

### Why this dataset
Briefly justify selection:
- This dataset provides raw and preprocessed data collected from sensors in a cold storage room. This fits our target scenario.

---

## 2) License and access conditions

- **License name:** `Creative Commons Attribution 4.0 International`
- **License URL/text reference:** [CC BY 4.0 summary](https://creativecommons.org/licenses/by/4.0/) and [full legal text](https://creativecommons.org/licenses/by/4.0/legalcode.en).
- **Commercial use allowed?:** Yes, subject to the license conditions.
- **Modification allowed?:** Yes, including cleaning, filtering, resampling, and creating derived datasets.
- **Redistribution allowed?:** Yes, including original files and representative samples, subject to attribution and the other license conditions.
- **Attribution requirements:** Credit Elia Henrichs, Florian Stoll, and Christian Krupitzer; identify the dataset and link to its DOI; link to CC BY 4.0; and indicate any changes, retaining notices of earlier modifications. Retain any supplied copyright, license, and warranty-disclaimer notices. Attribution may use any reasonable format, but must not imply author endorsement.
- **Access constraints:** Open access. Public file downloads require no account, API key, or separate terms-acceptance step; reuse remains subject to CC BY 4.0.
- **Verification evidence (2026-10-29):** The [Zenodo record metadata](https://zenodo.org/api/records/15130001) identifies version `v1`, license `cc-by-4.0`, and access right `open`. A [direct CSV download](https://zenodo.org/records/15130001/files/experiment_actions.csv?download=1) was accessible without authentication.

### Attribution text for project use
For unmodified files:

> Henrichs, Elia; Stoll, Florian; and Krupitzer, Christian (2025). Temperature and Humidity Time Series of Cold Storage Room Monitoring (v1) [Dataset]. Zenodo. https://doi.org/10.5281/zenodo.15130001. Licensed under Creative Commons Attribution 4.0 International (CC BY 4.0): https://creativecommons.org/licenses/by/4.0/. No changes made by this project.

For a modified sample, use the same credit and replace the final sentence with an accurate description of the actual changes (for example, row selection or timestamp normalization).

### Compliance notes for this project
- Retrieve and analyze the data under CC BY 4.0. When adding a sample in Section 5.1, accompany it with the attribution above and describe the exact selection and preprocessing steps; do not label modified data as unchanged.
- Keep the dataset's license and attribution distinct from the project's software license. Preserve supplied notices and do not impose additional legal restrictions or technological measures that restrict recipients' licensed reuse of the data.
- Redistribution is permitted, so Section 5.1 is the applicable representative-input option. The sample and its provenance still need to be prepared; this license review does not complete that work.

---

## 3) Reproducible retrieval instructions

> Goal: another teammate can retrieve exactly the same raw files.

### Option A: Manual download steps
1. Go to: `<dataset URL>`
2. Select version: `<version info>`
3. Download file(s):
   - `<filename_1>`
   - `<filename_2>`
4. Save under:
   - `data/raw/<filename_1>`
   - `data/raw/<filename_2>`

### Option B: Scripted retrieval (if supported)
```bash
# Example only — replace with actual commands
mkdir -p data/raw
curl -L "<direct_download_url>" -o data/raw/<filename>
```

### Integrity / reproducibility checks
- **File name:** `<filename>`
- **Expected size:** `<bytes/MB>`
- **Checksum type/value:** `<SHA256 ... | N/A>`
- **Record/version pinning method:** `<how you ensure stable version>`

---

## 4) Feasibility inspection summary

## 4.1 Data format and schema snapshot
- **Primary format(s):** `<CSV/JSON/Parquet/etc.>`
- **Key columns (initial):**
  - `<timestamp column>`
  - `<temperature column>`
  - `<location/station column>`
  - `<other key field>`

## 4.2 Timestamp coverage
- **Earliest timestamp:** `<YYYY-MM-DD ...>`
- **Latest timestamp:** `<YYYY-MM-DD ...>`
- **Timezone info:** `<UTC/local/unspecified>`
- **Coverage assessment:** `<sufficient/insufficient + why>`

## 4.3 Units and semantics
- **Temperature unit(s):** `<C/F/K>`
- **Unit consistency across files:** `<yes/no + notes>`
- **Any conversion required?:** `<yes/no>`
- **Planned canonical unit in app:** `<e.g., Celsius>`

## 4.4 Sampling interval
- **Observed interval(s):** `<hourly/daily/mixed>`
- **Regular or irregular?:** `<regular/irregular>`
- **Handling plan in pipeline:** `<resample/interpolate/store-as-is>`

## 4.5 Missing values and quality
- **Missing marker(s):** `<NaN, blank, -9999, etc.>`
- **Approx. missingness:** `<percent or count>`
- **Outlier/quality flags present?:** `<yes/no>`
- **Planned handling strategy:** `<drop/impute/flag>`

## 4.6 Sample size
- **Total rows (approx/exact):** `<count>`
- **Number of files:** `<count>`
- **Number of unique stations/locations:** `<count>`
- **Estimated raw storage footprint:** `<MB/GB>`

## 4.7 Suitability decision
- **Decision:** `<Use this dataset / Do not use>`
- **Rationale:** `<short rationale tied to requirements>`

---

## 5) Representative input for project use

Choose one depending on license constraints.

## 5.1 If redistribution is permitted
- **Included sample path:** `data/sample/<sample_filename>`
- **How sample was produced:** `<filtering/sampling steps>`
- **Sample contents summary:** `<rows, date range, locations>`

## 5.2 If redistribution is NOT permitted
Provide reproducible generation instructions:
1. Retrieve raw data (Section 3).
2. Run:
   ```bash
   # Example only — replace with your script
   python scripts/make_sample.py --input data/raw/<filename> --output data/sample/<sample_filename> --rows 500
   ```
3. Verify output:
   - `<basic checks>`

---

## 6) Implementation references

Link evidence artifacts in repo:
- **Parsing/loading code:** `<path>`
- **Validation/EDA notebook or script:** `<path>`
- **Config/constants for units/time parsing:** `<path>`
- **Sample-generation script:** `<path>`
- **Any related tests:** `<path>`

---

## 7) Risks, assumptions, and follow-ups

- **Risks:**
  - `<risk>`
  - `<risk>`
- **Assumptions:**
  - `<assumption>`
- **Follow-up tasks (if needed):**
  - `<task>`

---

## 8) Acceptance criteria checklist (Issue #11 mapping)

- [ ] Record source, license/access conditions, and reproducible retrieval instructions
- [ ] Inspect timestamp coverage, units, sampling intervals, missing values, and sample size
- [ ] Provide permitted example or instructions to obtain a representative input

### Completion notes
Add final evidence summary and commit/PR references:
- `<commit or PR link>`
- `<issue comment link>`