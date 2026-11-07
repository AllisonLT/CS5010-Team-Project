# Shelf-Life Feasibility and Supported Outputs (Issue #13)

## Issue and milestone context

- **Issue:** #13 — Resolve shelf-life feasibility and supported prediction outputs
- **Milestone:** M1: Requirements and dataset feasibility
- **Depends on:** #11 (temperature dataset), #12 (spoilage source, `docs/spoilage_dataset_selection.md`)
- **Status:** Proposed. Needs team approval of the decision below before M6 starts.

## Decision

1. **Remaining shelf life can be supported**, but only for **one product category: fresh poultry (breast or thigh fillets) under aerobic storage**. It is computed by the validated model of Tsaloumi et al. (2022), not learned from labels.
2. **Spoilage risk** is derived from the same model as the share of shelf life already used, and reported as Low, Medium, or High.
3. No other food category is supported. Requests for other categories are rejected with an explanation, not answered with a guessed value.

### Why not machine learning

The project PDF says Module 2 should "use machine learning to estimate food spoilage risk and remaining shelf life." That needs labeled examples of products whose temperature history and spoilage outcome were both measured. Issue #12 found none:

- The available Kaggle datasets are synthetic or inventory records.
- The one real fruit dataset covers 21–27 °C, has no time information, and labels room conditions rather than spoilage.
- The trout dataset kept every sample at 4 °C.

The PDF's scope section allows remaining shelf-life prediction "where suitable training labels or validated models are available," so a validated model is within scope. The functional requirement's wording ("use machine learning") is not met by this choice, and the team has to accept that explicitly.

### Options considered

| Option | What it means | Assessment |
|---|---|---|
| **A. Validated model (recommended)** | Implement the published square-root growth model in Python and serve it from FastAPI like the forecasting model | Honest, testable against published values, and works with our cold-room temperature histories. Deviates from the "machine learning" wording in Module 2. |
| B. ML trained on model-generated labels | Generate synthetic temperature histories, label them with Option A, then train a regressor on the result | Counts as "machine learning" on paper, but the regressor can only imitate the formula and adds error. The PDF's NFRs require such predictions to be identified as based on synthetic labels. Not recommended. |
| C. Classifier on the Mendeley fruit data | Train on the `Good`/`Bad` label | Rejected in #12: the label is about room conditions at 21–27 °C, not spoilage in cold storage. |

**Approval needed:** the team chooses Option A (or another option) in the #13 issue thread. If the course requires a learned model for Module 2, Option B is the fallback, and every Module 2 output must then be labeled as trained on simulated data.

## Model specification (Option A)

**Secondary model** (Tsaloumi et al. 2022, Eq. 3 and Table 3):

```
sqrt(mu_max(T)) = b * (T - Tmin)       for T > Tmin, otherwise mu_max = 0
b = 0.022,  Tmin = -6.18 °C,  mu_max in h^-1 (natural-log units)
```

**Growth under a temperature history.** The history arrives as consecutive readings T_1 … T_n at a fixed interval Δt (one minute, matching the forecasting preprocessing). With no lag (α0 = 1), the paper's assumption that the growth rate adapts instantly gives:

```
ln N(t_k) = ln N0 + Σ_{i ≤ k} mu_max(T_i) * Δt
```

Shelf life ends when log10 N reaches the spoilage level of 7. This is the exponential phase of the Baranyi model. The stationary phase is not needed because the spoilage level is reached first.

**Remaining shelf life.** After the history ends, we assume the product stays at a stated future storage temperature T_future (default: the last observed temperature; the user may enter another value). Then:

```
remaining_hours = (7 - log10 N(t_n)) * ln(10) / mu_max(T_future)
```

If log10 N(t_n) ≥ 7, the product is reported as past the modeled spoilage level and remaining shelf life is 0.

**Lag phase (optional input).** With α0 < 1, the lag is handled by the Baranyi adjustment-function formulation with h0 = −ln(α0). The default is α0 = 1, as in the paper's validation runs.

## Supported inputs

| Input | Source | Allowed values | Default |
|---|---|---|---|
| Product category | Product profile | `fresh_poultry_aerobic` only | — |
| Temperature history | Selected dataset, sensor, and time window, from preprocessed 1-minute readings | Celsius; at least 60 readings; gaps handled as below | — |
| Initial contamination N0 | Product profile | 1.0–4.0 log CFU/cm² (paper's tested range is 2–4) | 2.0 |
| Physiological state α0 | Product profile | 0.1–1.0 | 1.0 |
| Future storage temperature | Request | 0–30 °C | Last observed reading |

**Temperature unit.** The #11 dataset was logged by Arduino code that stores `temperatureC = dht.readTemperature()` (`temphum_logger.ino` in the Zenodo record), so its readings are degrees Celsius. This also settles the open unit question in the forecasting handoff notes.

**Gaps in the history.** The 1-minute preprocessing leaves bins with too little coverage as missing and never fills them. For Module 2:

- Gaps of up to 10 minutes are bridged by linear interpolation, and the number of interpolated minutes is reported.
- A history with any gap longer than 10 minutes is rejected with an error naming the gap.

These thresholds are our design choice and will be confirmed when the contract is written in #16.

## Supported outputs

| Output | Definition |
|---|---|
| `remaining_shelf_life_hours` | As defined above; 0 if already past the spoilage level |
| `shelf_life_used_fraction` | (log10 N(t_n) − log10 N0) / (7 − log10 N0), capped at 1 |
| `spoilage_risk` | `low` if used fraction < 0.5; `medium` if 0.5–< 0.8; `high` if ≥ 0.8 or past the spoilage level |
| `estimated_log_count` | log10 N(t_n), in log CFU/cm² |
| Factors | Minutes above 4 °C, peak temperature, mean temperature, and the share of growth that happened above 4 °C |
| `limitations` | Fixed text: modeled estimate for fresh poultry under aerobic storage, not a food safety determination; outside the 0–30 °C validated range the result is flagged |
| `model_version` | Model name, parameter set, and citation DOI |

The risk cut-offs (0.5 and 0.8) and the 4 °C reference for "time above" are our design choices, not values from the paper. They are documented as such in the UI help text.

## Limitations

- The model covers *Pseudomonas*-driven spoilage of aerobically stored fresh poultry. It says nothing about pathogens or safety, about other foods, or about vacuum or modified-atmosphere packs.
- Below 0 °C or above 30 °C the model is extrapolating. Readings outside this range are kept in the calculation but flag the result.
- N0 varies between batches, and shelf life is sensitive to it: 9.3, 7.5, and 5.6 d at 4 °C for N0 = 2, 3, and 4 log in the paper. The UI must show the N0 used.
- The cold-room readings measure air temperature, not product temperature.

## Evaluation plan (for M6 #33–#36)

- **Reference reproduction:** at constant temperature, with N0 = 2 log and α0 = 1, the computed shelf life is within 5% of the paper's 25.2 d (0 °C), 9.3 d (4 °C), and 4.8 d (8 °C). At 4 °C, the values for N0 = 3 and 4 log are within 5% of 7.5 d and 5.6 d. Published b = 0.022 gives errors of 2.7–3.0% because b is rounded (see #12).
- **Dynamic check:** a repeating profile of 12 h at 5 °C, 8 h at 10 °C, and 4 h at 15 °C (one of the paper's validation profiles) must give a shelf life between the constant 5 °C and constant 15 °C results.
- **Baseline:** a fixed use-by date that ignores the temperature history (constant-temperature shelf life at 4 °C, i.e., the label date). Report how far the model's estimate moves from this baseline for each test history, and show at least one demo history where the cold room ran warm.
- **Unit tests:** monotonic in temperature and in N0; rejection of unsupported categories and long gaps; spoiled products return 0 remaining hours.

## Acceptance criteria (Issue #13)

- [x] Document supported product categories, model inputs, outputs, and how temperature histories are associated. One category; inputs, outputs, and the gap policy are listed above.
- [x] Confirm whether labels or a validated model support remaining shelf life rather than risk alone. A validated model supports remaining shelf life; no labeled dataset was found (#12).
- [ ] If the dataset cannot support the promised scope, record the blocker and obtain approval for any reduced deliverable without fabricating shelf-life values. Blocker recorded above: no ML training data. **Approval from the team is still pending.**
