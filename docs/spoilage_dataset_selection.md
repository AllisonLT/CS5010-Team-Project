# Spoilage Dataset Selection (Issue #12)

## Issue and milestone context

- **Issue:** #12 — Select and document a labeled food spoilage dataset
- **Milestone:** M1: Requirements and dataset feasibility
- **Review date:** 2026-11-07
- **Related:** #11 (temperature dataset, `docs/dataset_selection.md`), #13 (feasibility decision, `docs/shelf_life_feasibility.md`)

## Summary

We did not find a public dataset that links a product's **temperature history** to a measured **spoilage or shelf-life outcome**, which is what Module 2 needs. The candidates we found are synthetic, have no time dimension, or keep temperature constant.

Module 2 will therefore use a **published, validated predictive-microbiology model** with parameters taken from a peer-reviewed study instead of a labeled training dataset. The project PDF allows this: its scope promises remaining shelf-life prediction "where suitable training labels or validated models are available." Issue #13 records the decision and the approval it needs.

## What Module 2 needs from data

To estimate spoilage from temperature exposure, a dataset must have:

1. A food product or category.
2. The temperature the product experienced over time, or at least a storage temperature that varies across samples.
3. An outcome measured on the product: microbial count, sensory rejection, or time to spoilage.
4. Real measurements, or a model validated against real measurements.
5. A license that allows use in a course project.

## Candidates reviewed

| Candidate | Source and license | What it contains | Verdict |
|---|---|---|---|
| A Multi-Parameter Dataset for Machine Learning Based Fruit Spoilage Prediction in an IoT-Enabled Cold Storage System | Mendeley Data, DOI [10.17632/czz68d9fwj.1](https://doi.org/10.17632/czz68d9fwj.1), CC BY 4.0 | 10,995 rows; columns `Fruit`, `Temp`, `Humid (%)`, `Light (Fux)`, `CO2 (pmm)`, `Class` (Good/Bad) | **Rejected.** See inspection below. |
| Food Waste Prediction & Demand Forecasting Dataset | Kaggle `kp00011/food-waste-prediction-and-demand-forecasting-dataset`, MIT | Inventory records with storage temperature, expiry duration, and derived risk labels | **Rejected.** The Kaggle description calls it "a synthetic yet realistic simulation of food inventory data". Its labels are generated, not measured. |
| Managing Perishable Inventory Data | Kaggle `minahilfatima12328/managing-perishable-inventory-data`, CC0 | Stock levels, storage conditions, demand, expiration dates | **Rejected.** An inventory-management dataset. It records expiration dates, not spoilage measured under a known temperature history. |
| Multimodal dataset of images, gas sensor responses, and microbiological measurements for rainbow trout fillets | Zenodo, DOI [10.5281/zenodo.23600663](https://doi.org/10.5281/zenodo.23600663), CC BY 4.0 | Real total viable counts over 7 storage days; fillets labeled Fresh (days 1–5) or Spoiled (days 6–7) using 7 log10 CFU/g | **Rejected for Module 2.** All fillets were stored at a single temperature (4 °C), so the data cannot show how temperature changes spoilage. The archive is 21.1 GB. |
| Tsaloumi et al. (2022), pseudomonads growth model for fresh poultry | Poultry Science 101(8):101985, DOI [10.1016/j.psj.2022.101985](https://doi.org/10.1016/j.psj.2022.101985), article CC BY-NC-ND 4.0 | Published secondary model for spoilage bacteria growth vs. temperature (0–30 °C), validated under constant and fluctuating temperatures | **Selected** as a validated model, not a dataset. See below. |

Kaggle metadata was read from the public Kaggle API (`https://www.kaggle.com/api/v1/datasets/view/<owner>/<slug>`) on 2026-11-07.

### Inspection of the Mendeley fruit dataset

We downloaded `Dataset.csv` through the Mendeley public API (385,517 bytes; SHA-256 `218f76f061a12b0a38c55107e5be3b377e9eecb33f1a1840ef2b4e4618a5e5aa`) and profiled it. Findings:

- **Room temperature only:** `Temp` ranges from 21 to 27 °C, with a mean of 23.8 °C. Nothing in it covers cold storage (0–10 °C), which is where our temperature dataset sits.
- **No time:** there is no timestamp or storage-day column, so a row cannot be linked to a temperature history.
- **The label is not a spoilage outcome:** the authors define `Class` as "whether the environmental conditions are optimal or suboptimal for spoilage prevention". It grades the room's conditions, not the fruit.
- **Label driven by light and humidity:** a depth-3 decision tree splits first on humidity and light, not temperature.
- **Data quality:** 2,880 exact duplicate rows (26%), and inconsistent label spelling (`Bad` vs. `BAD`; all 711 banana negatives use `BAD`).

A classifier trained on it would learn the dataset authors' labeling rule for room conditions. It would not estimate the spoilage of food in cold storage.

## Selected source: Tsaloumi et al. (2022)

**Citation:** Tsaloumi, S., Aspridou, Z., Spyrelli, E., Nychas, G.-J. E., and Koutsoumanis, K. (2022). Development and validation of a mathematical model for pseudomonads growth as a basis for predicting spoilage of fresh poultry breast and thigh fillets. *Poultry Science*, 101(8), 101985. https://doi.org/10.1016/j.psj.2022.101985. Open access (PMC9264009).

**Why it fits:**

- It models the specific spoilage organism of aerobically stored fresh poultry (*Pseudomonas* spp.) as a function of temperature from 0 to 30 °C. That range covers our cold-room data.
- It was validated under **fluctuating** temperature profiles, which is exactly our use case. Bias factor 1.01–1.09 and accuracy factor 1.02–1.12 across the two dynamic profiles.
- It defines the end of shelf life as the time to reach about 10^7 CFU/g (7 log), and reports shelf-life values we can use as test references.

**Parameters we use** (unified model, Table 3 of the paper):

| Parameter | Value | Meaning |
|---|---|---|
| Model | √μmax = b × (T − Tmin) | Ratkowsky square-root secondary model (paper Eq. 3) |
| b | 0.022 (95% CL 0.021–0.023) | Slope |
| Tmin | −6.18 °C (95% CL −7.32 to −5.04) | Theoretical minimum growth temperature |
| μmax unit | h⁻¹ (natural-log units) | Maximum specific growth rate |
| Spoilage level | 7 log CFU/g (or /cm²) | End of shelf life |
| Default initial level N0 | 2 log CFU/cm² | Paper's reference scenario; 3 and 4 also reported |
| Lag | α0 = 1 (no lag) by default | Most experiments showed no lag phase |
| Dynamic temperature | Rate adapts instantly to each new temperature | Paper's stated assumption |

**Reference values for testing** (paper, Figure 6 discussion; N0 = 2 log, α0 = 1): shelf life of **25.2 d at 0 °C, 9.3 d at 4 °C, and 4.8 d at 8 °C**. At 4 °C, it is **9.3, 7.5, and 5.6 d** for N0 = 2, 3, and 4 log.

Our check: with the published b = 0.022, exponential growth from 2 to 7 log gives 25.95 d, 9.56 d, and 4.93 d at 0, 4, and 8 °C, which is 2.7–3.0% above the paper's figures. The paper's values are reproduced to within 0.3% by b ≈ 0.0223, so the gap most likely comes from b being rounded to 0.022 in Table 3. Module 2 will use the published 0.022 and test against the paper's values with a 5% tolerance (see #13).

**License note:** we use the published model equation and parameter values, which are facts, and cite the paper. We do not copy its text, figures, or tables into the repository.

## Retrieval instructions

No dataset download is needed for Module 2. The parameters above are the only external inputs and will be stored in a versioned configuration file under `services/ml` during M6 (#33/#34), with the citation.

To reproduce the Mendeley inspection:

```bash
mkdir -p data/raw/spoilage_candidates
curl -L -o data/raw/spoilage_candidates/mendeley_fruit.csv \
  "https://data.mendeley.com/public-files/datasets/czz68d9fwj/files/d3fd328b-8b98-4bed-a423-7af706c1da21/file_downloaded"
shasum -a 256 data/raw/spoilage_candidates/mendeley_fruit.csv   # expect 218f76f0...a5e5aa
```

## Acceptance criteria (Issue #12)

- [x] Record the source, license/access conditions, and retrieval instructions. The selected source is a published model (citation, DOI, and license above). Rejected Kaggle/Mendeley/Zenodo candidates are listed with licenses.
- [x] Identify supported food categories, labels, and product features. One category is supported: fresh poultry under aerobic storage. There is no training label; the outcome is the time to reach 7 log CFU/g. Product inputs are the initial contamination level and the lag-phase parameter.
- [x] Verify whether temperature-exposure histories can be associated with the labels, and record limitations. No reviewed dataset supports this association. The selected model takes a temperature history directly and was validated on fluctuating profiles. Limitations are recorded in #13.
